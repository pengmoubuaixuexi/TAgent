package cn.bugstack.ai.trigger.knowledge;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.RagFilePreviewService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.RagIngestionRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.RagIngestionService;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** All identifiers are resolved under the logged-in principal, never X-User-Id or a request body owner. */
@RestController
@RequestMapping(value = "/api/v1/knowledge", produces = "application/json")
public class KnowledgeController {
    private final RagIngestionService ingestion;
    private final RagIngestionRepository records;
    private final RagFilePreviewService previews;
    private final HybridRetriever retrieval;
    private final RunBudgetRegistry budgets;
    private final ModelProviderAccess providers;

    public KnowledgeController(RagIngestionService ingestion, RagIngestionRepository records, RagFilePreviewService previews,
                               HybridRetriever retrieval, RunBudgetRegistry budgets, ModelProviderAccess providers) {
        this.ingestion = ingestion; this.records = records; this.previews = previews; this.retrieval = retrieval;
        this.budgets = budgets; this.providers = providers;
    }
    @GetMapping public Object list(Authentication auth) { return ok(ingestion.list(user(auth))); }
    @PostMapping(value = "/uploads", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(Authentication auth, @RequestParam String knowledgeTag, @RequestParam MultipartFile file) {
        var task = ingestion.submit(user(auth), knowledgeTag, file);
        return ResponseEntity.accepted().body(ok(ingestion.view(task)));
    }
    @GetMapping("/tasks/{id}") public Object task(Authentication auth, @PathVariable String id) {
        return ok(ingestion.view(ingestion.requireTask(user(auth), id)));
    }
    @PostMapping("/tasks/{id}/retry") public ResponseEntity<?> retry(Authentication auth, @PathVariable String id) {
        return ResponseEntity.accepted().body(ok(ingestion.view(ingestion.retry(user(auth), id))));
    }
    @DeleteMapping("/tasks/{id}") public Object deleteTask(Authentication auth, @PathVariable String id) {
        return ok(ingestion.view(ingestion.deleteTask(user(auth), id)));
    }
    @DeleteMapping("/files/{id}") public Object deleteFile(Authentication auth, @PathVariable String id) {
        ingestion.deleteExistingFile(user(auth), id); return ok(Map.of("requested", true));
    }
    @GetMapping("/files/{id}/chunks") public Object preview(Authentication auth, @PathVariable String id,
                                                          @RequestParam(defaultValue = "0") int page) {
        return ok(previews.preview(user(auth), id, page));
    }

    public record SearchTest(String knowledgeTag, String query) { }
    @PostMapping("/search-test") public Object search(Authentication auth, @RequestBody SearchTest body) {
        String owner = user(auth);
        if (body == null || body.knowledgeTag() == null || body.knowledgeTag().isBlank() || body.knowledgeTag().length() > 128
                || body.query() == null || body.query().isBlank() || body.query().length() > 1000) {
            throw new RagStorageException("请选择知识库，并填写不超过 1000 字的检索问题。");
        }
        if (!records.ownsKnowledge(owner, body.knowledgeTag())) throw new RagStorageException("知识库不存在、未启用或无权访问。");
        try (var scope = ModelInvocationScope.open(budgets, owner, "KNOWLEDGE_RETRIEVAL")) {
            providers.requirePlatformAuxiliary(owner, "EMBEDDING");
            Filter.Expression filter = new Filter.Expression(Filter.ExpressionType.AND,
                    new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("user_id"), new Filter.Value(owner)),
                    new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("knowledge"), new Filter.Value(body.knowledgeTag())));
            var result = retrieval.retrieveDetailed(body.query(), SearchRequest.builder().topK(5).filterExpression(filter).build(), body.knowledgeTag(), 5);
            if (!result.available()) throw new RagStorageException("检索服务暂不可用，两个检索分支均未返回有效结果；请稍后重试。");
            return ok(Map.of("branches", result.branches(), "degraded", result.degraded(), "latencyMs", result.latencyMs(),
                    "documents", result.documents().stream().map(document -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        String text = document.getText() == null ? "" : document.getText();
                        row.put("content", text.substring(0, Math.min(text.length(), 5000)));
                        row.put("truncated", text.length() > 5000);
                        row.put("source", document.getMetadata().get("source")); row.put("title", document.getMetadata().get("title"));
                        return row;
                    }).toList()));
        }
    }

    private static String user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return auth.getName();
    }
    private static Object ok(Object data) { return Map.of("code", "0000", "info", "成功", "data", data); }
    @ExceptionHandler(RagStorageException.class) public ResponseEntity<?> invalid(RagStorageException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "400", "info", error.getMessage()));
    }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> denied(SecurityException error) {
        return ResponseEntity.status(403).body(Map.of("code", "403", "info", error.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> storage(DataAccessException error) {
        return ResponseEntity.status(503).body(Map.of("code", "503", "info", "知识库存储暂不可用，请检查服务连接和数据库迁移。"));
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code", String.valueOf(error.getStatusCode().value()), "info", error.getReason()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception error) {
        return ResponseEntity.status(503).body(Map.of("code", "503", "info", "知识库操作未完成，请稍后重试；正在处理的任务可在列表查看。"));
    }
}
