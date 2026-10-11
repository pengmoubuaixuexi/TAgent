package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.rag.IRagFileDeletionService;
import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

/** Durable queue with one bounded ingestion worker. No automatic retry of model-consuming failures. */
@Slf4j
@Service
public class RagIngestionService implements AutoCloseable {
    private final RagIngestionRepository tasks;
    private final RagUploadStorage sources;
    private final IRagService rag;
    private final IRagFileDeletionService deletion;
    private final RunBudgetRegistry budgets;
    private final ModelProviderAccess providers;
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "rag-ingestion-coordinator"));
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> daemon(r, "rag-ingestion-worker"), new ThreadPoolExecutor.AbortPolicy());
    private volatile Active active;
    private volatile boolean closed;
    private volatile long lastFailureLog;
    private static final class Active {
        final RagIngestionTask task;
        volatile Thread thread;
        volatile RunBudgetRegistry.State budget;
        Active(RagIngestionTask task) { this.task = task; }
        void cancel() { if (budget != null) budget.cancel(); if (thread != null) thread.interrupt(); }
    }

    public RagIngestionService(RagIngestionRepository tasks, RagUploadStorage sources, IRagService rag,
            IRagFileDeletionService deletion, RunBudgetRegistry budgets, ModelProviderAccess providers) {
        this.tasks = tasks; this.sources = sources; this.rag = rag; this.deletion = deletion;
        this.budgets = budgets; this.providers = providers;
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }

    @PostConstruct public void start() { coordinator.scheduleWithFixedDelay(this::tick, 1, 5, TimeUnit.SECONDS); }

    public RagIngestionTask submit(String owner, String knowledge, MultipartFile file) {
        requireOwner(owner);
        if (knowledge == null || knowledge.isBlank() || knowledge.length() > 128 || knowledge.chars().anyMatch(Character::isISOControl)) {
            throw new RagStorageException("请填写 1–128 字的知识库标签。");
        }
        String tag = knowledge.trim();
        providers.requirePlatformAuxiliary(owner, "EMBEDDING");
        String id = UUID.randomUUID().toString();
        RagUploadStorage.Stored source = sources.save(id, file);
        try {
            String existing = tasks.existingFileId(owner, tag, source.hash());
            String state = existing == null ? "QUEUED" : "SUCCEEDED";
            RagIngestionTask proposed = new RagIngestionTask(id, owner,
                    existing == null ? RagFileScope.stableId(owner, tag, source.hash()) : existing,
                    tag, file.getOriginalFilename(), source.hash(), source.bytes(), source.path(),
                    digest(owner + "\u0000" + tag + "\u0000" + source.hash()), state, state, existing == null ? 0 : 100,
                    0, 0, null, null, null, null, null);
            RagIngestionTask saved = tasks.insert(proposed);
            if (!id.equals(saved.taskId())) sources.delete(source.path());
            return saved;
        } catch (RuntimeException error) {
            try { sources.delete(source.path()); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public RagIngestionTask requireTask(String owner, String id) {
        requireOwner(owner);
        RagIngestionTask task = tasks.owned(owner, id);
        if (task == null) throw new RagStorageException("文件任务不存在或无权访问。");
        return task;
    }

    public Map<String, Object> list(String owner) {
        requireOwner(owner);
        return Map.of("tasks", tasks.list(owner).stream().map(this::view).toList(), "files", tasks.files(owner),
                "indexProvider", "平台向量索引服务", "retryPolicy", "失败保留原件，重试会重新执行入库并可能产生模型用量。");
    }

    public Map<String, Object> view(RagIngestionTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.taskId()); out.put("fileId", task.ragId()); out.put("fileName", task.fileName());
        out.put("knowledgeTag", task.knowledgeTag()); out.put("fileSize", task.fileSize());
        out.put("status", task.status()); out.put("stage", task.stage()); out.put("progress", task.progress());
        out.put("chunkCount", task.chunkCount()); out.put("attempt", task.attempt()); out.put("error", task.error());
        out.put("updatedAt", task.updatedAt() == null ? null : task.updatedAt().atOffset(ZoneOffset.UTC).toString());
        return out;
    }

    public RagIngestionTask retry(String owner, String id) {
        RagIngestionTask task = requireTask(owner, id);
        if (!"FAILED".equals(task.status())) throw new RagStorageException("只有失败的任务可以重试；正在取消或删除的任务请先完成清理。");
        providers.requirePlatformAuxiliary(owner, "EMBEDDING");
        sources.open(task); // Verify the retained file before admitting any new model work.
        if (!tasks.retry(owner, id)) throw new RagStorageException("任务状态已变化，请刷新后重试。");
        return requireTask(owner, id);
    }

    public RagIngestionTask deleteTask(String owner, String id) {
        RagIngestionTask task = requireTask(owner, id);
        if ("DELETED".equals(task.status())) return task;
        if ("PROCESSING".equals(task.status()) || "CANCEL_REQUESTED".equals(task.status())) {
            tasks.requestCancel(owner, id);
            Active running = active;
            if (running != null && id.equals(running.task.taskId()) && owner.equals(running.task.owner())) running.cancel();
            return requireTask(owner, id); // Keep all files until the worker has actually left its storage calls.
        }
        if (!tasks.beginDelete(owner, id)) throw new RagStorageException("任务仍在入库或清理，请稍后刷新；不会并发删除正在写入的资料。");
        try {
            if (!("QUEUED".equals(task.status()) && task.attempt() == 0)) {
                deletion.deleteIngestionFile(new RagFileScope(task.ragId(), owner, task.knowledgeTag(), task.fileName(),
                        !task.ragId().startsWith(RagFileScope.FILE_ID_PREFIX)));
            }
            sources.delete(task.sourcePath());
            tasks.deleted(owner, id);
        } catch (Exception error) {
            tasks.deleteFailed(owner, id, safeMessage(error, "删除未完成，任务及原件已保留；请恢复存储后重试删除。"));
            throw new RagStorageException("删除未完成，请查看任务原因后重试。", error);
        }
        return requireTask(owner, id);
    }

    public void deleteExistingFile(String owner, String fileId) {
        requireOwner(owner);
        RagIngestionTask task = tasks.forFile(owner, fileId);
        if (task != null) { deleteTask(owner, task.taskId()); return; }
        Map<String, Object> file = tasks.ownedFile(owner, fileId);
        if (file == null) throw new RagStorageException("知识库文件不存在或无权访问。");
        deletion.deleteById(((Number) file.get("id")).longValue());
    }

    /** Compatibility adapter: the old chat upload reports success only after indexing actually succeeds. */
    public void submitAndWait(String owner, String knowledge, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new RagStorageException("请选择要上传的文件。");
        for (MultipartFile file : files) {
            RagIngestionTask task = submit(owner, knowledge, file);
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (Set.of("QUEUED", "PROCESSING").contains(task.status()) && System.nanoTime() < deadline) {
                try { Thread.sleep(250); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RagStorageException("上传请求已断开，任务仍会保留；请到知识库页面查看处理结果。");
                }
                task = requireTask(owner, task.taskId());
            }
            if (!"SUCCEEDED".equals(task.status())) throw new RagStorageException(
                    "FAILED".equals(task.status()) ? task.error() : "文件仍在处理或清理中，请到知识库页面查看进度。");
        }
    }

    /** Public for deterministic offline tests; scheduled calls are serialized by the coordinator. */
    public synchronized void tick() {
        if (closed) return;
        try {
            Active running = active;
            if (running != null) {
                RagIngestionTask current = tasks.owned(running.task.owner(), running.task.taskId());
                if (current == null || !Objects.equals(current.leaseToken(), running.task.leaseToken())
                        || !Set.of("PROCESSING", "CANCEL_REQUESTED").contains(current.status())) running.cancel();
                else { tasks.heartbeat(running.task); if ("CANCEL_REQUESTED".equals(current.status())) running.cancel(); }
                return;
            }
            tasks.recoverExpired();
            RagIngestionTask next = tasks.claimNext();
            if (next == null) return;
            Active accepted = new Active(next); active = accepted;
            try { worker.submit(() -> execute(accepted)); }
            catch (RejectedExecutionException rejected) { active = null; tasks.fail(next, "入库队列繁忙，请稍后重试。"); }
        } catch (Exception error) {
            if (System.currentTimeMillis() - lastFailureLog > 60_000) {
                lastFailureLog = System.currentTimeMillis();
                log.warn("Knowledge ingestion coordinator unavailable; verify V066 and storage connectivity: {}", error.getClass().getSimpleName());
            }
        }
    }

    private void execute(Active accepted) {
        RagIngestionTask task = accepted.task;
        accepted.thread = Thread.currentThread();
        try (var scope = ModelInvocationScope.open(budgets, task.owner(), "KNOWLEDGE_INGESTION")) {
            accepted.budget = RunBudgetRegistry.find(Map.of());
            providers.requirePlatformAuxiliary(task.owner(), "EMBEDDING");
            var file = sources.open(task);
            // A failed attempt may have written only part of its stable file scope. Clear that scope
            // before rebuilding, so a changed splitter cannot leave obsolete child chunks behind.
            // If publication already completed before the task state update failed, preserve it:
            // RagService's content hash check will finish the task without calling models again.
            if (task.attempt() > 1 && tasks.existingFileId(task.owner(), task.knowledgeTag(), task.fileHash()) == null) {
                if (!tasks.progress(task, "CLEANING", 5, 0)) throw new CancellationException("Ingestion lease no longer active");
                accepted.budget.checkActive();
                deletion.deleteIngestionFile(new RagFileScope(task.ragId(), task.owner(), task.knowledgeTag(), task.fileName(), false));
            }
            rag.storeRagFile(task.fileName(), task.knowledgeTag(), List.of(file), task.owner(), (stage, progress, chunks) -> {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Ingestion cancelled");
                accepted.budget.checkActive();
                if (!tasks.progress(task, stage, progress, chunks)) throw new CancellationException("Ingestion lease no longer active");
            });
            tasks.complete(task);
        } catch (Exception error) {
            log.warn("Knowledge ingestion failed task={} stage retained: {}", task.taskId(), error.getClass().getSimpleName());
            Thread.interrupted(); // Permit the final durable state update after a cancelled transport call.
            tasks.fail(task, safeMessage(error, "入库未完成，可能有部分片段已写入；原件已保留，请重试或删除。"));
        } finally {
            Thread.interrupted();
            try { tasks.releaseCancelled(task); }
            finally { if (active == accepted) active = null; }
        }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new SecurityException("请先登录。");
    }
    private static String safeMessage(Exception error, String fallback) {
        String message = error instanceof RagStorageException || error instanceof SecurityException || error instanceof RunBudgetExceededException
                ? error.getMessage() : fallback;
        return message == null ? fallback : message.substring(0, Math.min(900, message.length()));
    }

    @Override @PreDestroy public void close() {
        closed = true; coordinator.shutdownNow();
        Active running = active;
        if (running != null) running.cancel();
        worker.shutdownNow();
    }
}
