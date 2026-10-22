package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Per-user knowledge base limits, checked before a source is admitted or a retry is queued.
 * Administrators are exempt. Concurrent uploads may overshoot a limit by one file; that is accepted.
 */
@Component
public class RagIngestionQuota {
    public record Limits(boolean enabled, int maxFiles, long maxBytes, int dailyIngestions, int maxPending, int maxParentsPerFile) {
        public static final Limits DISABLED = new Limits(false, Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static final long MB = 1024L * 1024L;
    private final RagIngestionRepository tasks;
    private final Predicate<String> exempt;
    private final Limits limits;

    @Autowired
    public RagIngestionQuota(RagIngestionRepository tasks, IAdminUserDao users,
                             @Value("${agent.rag.quota.enabled:true}") boolean enabled,
                             @Value("${agent.rag.quota.max-files:50}") int maxFiles,
                             @Value("${agent.rag.quota.max-total-mb:200}") long maxTotalMb,
                             @Value("${agent.rag.quota.daily-ingestions:20}") int dailyIngestions,
                             @Value("${agent.rag.quota.max-pending:3}") int maxPending,
                             @Value("${agent.rag.quota.max-parents-per-file:300}") int maxParentsPerFile) {
        this(tasks, owner -> {
            AdminUser user = users.queryByUserId(owner);
            return user != null && "ADMIN".equals(user.getRole());
        }, new Limits(enabled, maxFiles, maxTotalMb * MB, dailyIngestions, maxPending, maxParentsPerFile));
    }

    public RagIngestionQuota(RagIngestionRepository tasks, Predicate<String> exempt, Limits limits) {
        this.tasks = tasks;
        this.exempt = exempt;
        this.limits = limits;
    }

    public void checkSubmit(String owner, long bytes) {
        if (exempt(owner)) return;
        RagIngestionRepository.Usage usage = tasks.usage(owner);
        requireCapacity(usage);
        if (usage.files() >= limits.maxFiles()) {
            throw new RagStorageException("知识库文件已达上限（" + limits.maxFiles() + " 个），请删除不需要的文件后再上传。");
        }
        if (usage.bytes() + bytes > limits.maxBytes()) {
            throw new RagStorageException("知识库空间不足：已用 " + mb(usage.bytes()) + " MB / " + mb(limits.maxBytes())
                    + " MB，本文件 " + mb(bytes) + " MB。请删除不需要的文件后再上传。");
        }
    }

    public void checkRetry(String owner) {
        if (!exempt(owner)) requireCapacity(tasks.usage(owner));
    }

    public int maxParents(String owner) {
        return exempt(owner) ? Integer.MAX_VALUE : limits.maxParentsPerFile();
    }

    public Map<String, Object> view(String owner) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean unlimited = exempt(owner);
        out.put("unlimited", unlimited);
        if (unlimited) return out;
        RagIngestionRepository.Usage usage = tasks.usage(owner);
        out.put("files", usage.files()); out.put("maxFiles", limits.maxFiles());
        out.put("bytes", usage.bytes()); out.put("maxBytes", limits.maxBytes());
        out.put("today", usage.today()); out.put("dailyIngestions", limits.dailyIngestions());
        out.put("pending", usage.pending()); out.put("maxPending", limits.maxPending());
        out.put("maxParentsPerFile", limits.maxParentsPerFile());
        return out;
    }

    private void requireCapacity(RagIngestionRepository.Usage usage) {
        if (usage.pending() >= limits.maxPending()) {
            throw new RagStorageException("你已有 " + usage.pending() + " 个文件在排队或处理中（上限 " + limits.maxPending()
                    + " 个），请等它们完成后再提交。");
        }
        if (usage.today() >= limits.dailyIngestions()) {
            throw new RagStorageException("今天的入库次数已用完（每天 " + limits.dailyIngestions() + " 次，重试也计入），请明天再试。");
        }
    }

    private boolean exempt(String owner) { return !limits.enabled() || exempt.test(owner); }

    private static String mb(long bytes) { return String.format("%.1f", bytes / (double) MB); }
}
