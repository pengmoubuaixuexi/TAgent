package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDatasetVersion;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    private final IAiEvalOpsDao dao;

    @Transactional
    public Map<String, Object> createDataset(String name, String description, String executionMode, String ownerUserId) {
        String normalizedName = requireText(name, "数据集名称不能为空");
        String mode = normalizeExecutionMode(executionMode);
        LocalDateTime now = LocalDateTime.now();
        AiEvalDataset dataset = AiEvalDataset.builder()
                .datasetId(UUID.randomUUID().toString())
                .name(normalizedName)
                .description(blankToNull(description))
                .executionMode(mode)
                .ownerUserId(blankToDefault(ownerUserId, "10001"))
                .status("ACTIVE")
                .latestVersionNo(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        dao.insertDataset(dataset);
        dao.insertVersion(newDraft(dataset.getDatasetId(), 1, "初始草稿", now));
        return datasetView(dataset);
    }

    public List<Map<String, Object>> listDatasets(String ownerUserId, int limit) {
        return dao.listDatasets(blankToNull(ownerUserId), Math.max(1, Math.min(limit, 200)))
                .stream().map(this::datasetView).toList();
    }

    public Map<String, Object> getDataset(String datasetId) {
        return datasetView(requireDataset(datasetId));
    }

    @Transactional
    public void deleteDataset(String datasetId) {
        AiEvalDataset dataset = requireDataset(datasetId);
        if (dao.countActiveRunsByDataset(dataset.getDatasetId()) > 0) {
            throw new IllegalArgumentException("数据集仍有关联的排队、运行或 Judge 中评测，请先中断或删除这些评测");
        }
        dao.deleteRunsByDataset(dataset.getDatasetId());
        if (dao.deleteDataset(dataset.getDatasetId()) != 1) {
            throw new IllegalArgumentException("数据集不存在或已被删除");
        }
    }

    @Transactional
    public Map<String, Object> updateDataset(String datasetId, String name, String description, String executionMode) {
        AiEvalDataset current = requireDataset(datasetId);
        int changed = dao.updateDataset(current.getDatasetId(),
                name == null ? current.getName() : requireText(name, "数据集名称不能为空"),
                description == null ? current.getDescription() : blankToNull(description),
                executionMode == null ? current.getExecutionMode() : normalizeExecutionMode(executionMode));
        if (changed != 1) throw new IllegalArgumentException("数据集不存在或已归档");
        return datasetView(requireDataset(datasetId));
    }

    public List<Map<String, Object>> listVersions(String datasetId) {
        requireDataset(datasetId);
        return dao.listVersions(datasetId).stream().map(this::versionView).toList();
    }

    public List<Map<String, Object>> listCases(String versionId, boolean enabledOnly) {
        requireVersion(versionId);
        return dao.listCases(versionId, enabledOnly).stream().map(this::caseView).toList();
    }

    @Transactional
    public Map<String, Object> saveCase(String datasetId, String caseId, CaseCommand command) {
        requireDataset(datasetId);
        AiEvalDatasetVersion draft = requireDraft(datasetId);
        if (command == null) throw new IllegalArgumentException("题目配置不能为空");
        String stableKey = requireText(command.getStableKey(), "stableKey 不能为空");
        String question = requireText(command.getQuestion(), "题目不能为空");
        int sequenceNo = command.getSequenceNo() == null
                ? dao.countCases(draft.getVersionId(), false) + 1 : Math.max(1, command.getSequenceNo());
        EvalCaseConfig config = command.getConfig() == null ? suggestConfig(sequenceNo, command.getCategory(), question)
                : command.getConfig().normalized();
        LocalDateTime now = LocalDateTime.now();
        AiEvalCase evalCase;
        if (caseId == null || caseId.isBlank()) {
            evalCase = AiEvalCase.builder()
                    .caseId(UUID.randomUUID().toString())
                    .versionId(draft.getVersionId())
                    .stableKey(stableKey)
                    .sequenceNo(sequenceNo)
                    .conversationGroup(blankToDefault(command.getConversationGroup(), "default"))
                    .category(blankToNull(command.getCategory()))
                    .tagsJson(JSON.toJSONString(command.getTags() == null ? List.of() : command.getTags()))
                    .question(question)
                    .configJson(JSON.toJSONString(config))
                    .referenceAnswer(blankToNull(command.getReferenceAnswer()))
                    .enabled(command.getEnabled() == null || command.getEnabled())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            dao.insertCase(evalCase);
        } else {
            AiEvalCase existing = dao.findCase(caseId);
            if (existing == null || !draft.getVersionId().equals(existing.getVersionId())) {
                throw new IllegalArgumentException("只能编辑当前草稿版本中的题目");
            }
            evalCase = AiEvalCase.builder()
                    .caseId(existing.getCaseId()).versionId(existing.getVersionId())
                    .stableKey(stableKey).sequenceNo(sequenceNo)
                    .conversationGroup(blankToDefault(command.getConversationGroup(), "default"))
                    .category(blankToNull(command.getCategory()))
                    .tagsJson(JSON.toJSONString(command.getTags() == null ? List.of() : command.getTags()))
                    .question(question).configJson(JSON.toJSONString(config))
                    .referenceAnswer(blankToNull(command.getReferenceAnswer()))
                    .enabled(command.getEnabled() == null || command.getEnabled())
                    .build();
            dao.updateCase(evalCase);
        }
        return caseView(evalCase);
    }

    @Transactional
    public void deleteCase(String datasetId, String caseId) {
        AiEvalDatasetVersion draft = requireDraft(requireDataset(datasetId).getDatasetId());
        if (dao.deleteCase(requireText(caseId, "caseId 不能为空"), draft.getVersionId()) != 1) {
            throw new IllegalArgumentException("题目不存在或不属于当前草稿");
        }
    }

    @Transactional
    public Map<String, Object> publish(String datasetId, String description) {
        AiEvalDataset dataset = requireDataset(datasetId);
        AiEvalDatasetVersion draft = requireDraft(datasetId);
        List<AiEvalCase> cases = dao.listCases(draft.getVersionId(), false);
        if (cases.stream().noneMatch(c -> Boolean.TRUE.equals(c.getEnabled()))) {
            throw new IllegalArgumentException("至少需要一条启用的题目才能发布");
        }
        String checksum = checksum(cases);
        LocalDateTime now = LocalDateTime.now();
        if (dao.publishVersion(draft.getVersionId(), cases.size(), checksum, now) != 1) {
            throw new IllegalArgumentException("草稿已经发布，请刷新后重试");
        }
        dao.updateDatasetLatestVersion(datasetId, draft.getVersionNo());

        AiEvalDatasetVersion next = newDraft(datasetId, draft.getVersionNo() + 1,
                blankToDefault(description, "基于 v" + draft.getVersionNo() + " 创建的草稿"), now);
        dao.insertVersion(next);
        for (AiEvalCase source : cases) dao.insertCase(copyCase(source, next.getVersionId(), now));
        return versionView(dao.findVersion(draft.getVersionId()));
    }

    @Transactional
    public Map<String, Object> importE2E100(String ownerUserId) {
        for (AiEvalDataset existing : dao.listDatasets(blankToDefault(ownerUserId, "10001"), 200)) {
            if ("E2E100".equalsIgnoreCase(existing.getName())) {
                throw new IllegalArgumentException("E2E100 数据集已经存在");
            }
        }
        Map<String, Object> created = createDataset("E2E100", "从原 E2E100QuestionTest 迁移的 100 轮有状态全链路评测", "SCENARIO", ownerUserId);
        String datasetId = String.valueOf(created.get("datasetId"));
        try {
            ClassPathResource resource = new ClassPathResource("eval/e2e100.json");
            JSONArray rows = JSON.parseArray(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                CaseCommand command = new CaseCommand();
                command.setStableKey("Q" + row.getIntValue("no"));
                command.setSequenceNo(row.getIntValue("no"));
                command.setConversationGroup("e2e100-main");
                command.setCategory(row.getString("category"));
                command.setTags(List.of(row.getString("category")));
                command.setQuestion(row.getString("question"));
                command.setConfig(suggestConfig(row.getIntValue("no"), row.getString("category"), row.getString("question")));
                command.setEnabled(true);
                saveCase(datasetId, null, command);
            }
        } catch (Exception error) {
            throw new IllegalStateException("导入 E2E100 资源失败：" + error.getMessage(), error);
        }
        publish(datasetId, "E2E100 v1");
        return getDataset(datasetId);
    }

    @Transactional
    public Map<String, Object> importQualityBenchmark(String ownerUserId) {
        String normalizedOwner = blankToDefault(ownerUserId, "10001");
        String datasetName = "Agent 14维场景基准集（80题版）";
        for (AiEvalDataset existing : dao.listDatasets(normalizedOwner, 200)) {
            if (datasetName.equalsIgnoreCase(existing.getName())) {
                throw new IllegalArgumentException(datasetName + "已经存在");
            }
        }
        Map<String, Object> created = createDataset(datasetName,
                "80题、16个会话组，针对规则9维与LLM Judge 5维设计；RAG场景建议使用FIXED_USER 10001",
                "SCENARIO", normalizedOwner);
        String datasetId = String.valueOf(created.get("datasetId"));
        try {
            ClassPathResource resource = new ClassPathResource("eval/agent-14d-scenario.json");
            JSONArray rows = JSON.parseArray(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                CaseCommand command = new CaseCommand();
                command.setStableKey(requireText(row.getString("stableKey"), "基准题 stableKey 不能为空"));
                command.setSequenceNo(row.getIntValue("sequenceNo"));
                command.setConversationGroup(requireText(row.getString("conversationGroup"), "基准题 conversationGroup 不能为空"));
                command.setCategory(row.getString("category"));
                command.setTags(row.getJSONArray("tags") == null ? List.of()
                        : row.getJSONArray("tags").toJavaList(String.class));
                command.setQuestion(requireText(row.getString("question"), "基准题 question 不能为空"));
                command.setReferenceAnswer(row.getString("referenceAnswer"));
                command.setConfig(benchmarkConfig(row));
                command.setEnabled(true);
                saveCase(datasetId, null, command);
            }
        } catch (Exception error) {
            throw new IllegalStateException("导入 Agent 14维场景基准集失败：" + error.getMessage(), error);
        }
        publish(datasetId, "Agent 14维场景基准集（80题版）v1");
        return getDataset(datasetId);
    }

    static EvalCaseConfig benchmarkConfig(JSONObject row) {
        EvalCaseConfig config = row.getObject("config", EvalCaseConfig.class);
        if (config == null) {
            config = new EvalCaseConfig();
            String profile = blankToDefault(row.getString("profile"), "simple");
            switch (profile) {
                case "memory" -> { config.setExpectMemory(true); config.setSimpleTask(true); config.setMaxSteps(2); config.setMaxLatencyMs(120_000); }
                case "domain" -> { config.setAllowGeneralFallback(false); config.setMaxSteps(4); config.setMaxLatencyMs(180_000); }
                case "tool" -> { config.setAllowGeneralFallback(false); config.setExpectTools(true); config.setAllowTools(true); config.setMaxSteps(6); config.setMaxLatencyMs(420_000); }
                case "rag" -> { config.setAllowGeneralFallback(false); config.setExpectRag(true); config.setMaxSteps(5); config.setMaxLatencyMs(160_000); }
                case "finance" -> { config.setAllowGeneralFallback(false); config.setFinancialSafety(true); config.setMaxSteps(5); config.setMaxLatencyMs(240_000); }
                case "complex" -> { config.setAllowGeneralFallback(false); config.setMaxSteps(8); config.setMaxLatencyMs(600_000); }
                case "robust" -> { config.setSimpleTask(true); config.setMaxSteps(2); config.setMaxLatencyMs(120_000); }
                case "simple" -> { config.setSimpleTask(true); config.setMaxSteps(2); config.setMaxLatencyMs(90_000); }
                default -> throw new IllegalArgumentException("未知基准题配置档案：" + profile);
            }
        }
        JSONArray capabilities = row.getJSONArray("expectedCapabilities");
        JSONArray mustMention = row.getJSONArray("mustMention");
        JSONArray mustNotMention = row.getJSONArray("mustNotMention");
        if (capabilities != null) config.setExpectedCapabilities(new LinkedHashSet<>(capabilities.toJavaList(String.class)));
        if (mustMention != null) config.setMustMention(new LinkedHashSet<>(mustMention.toJavaList(String.class)));
        if (mustNotMention != null) config.setMustNotMention(new LinkedHashSet<>(mustNotMention.toJavaList(String.class)));
        if (row.containsKey("allowGeneralFallback")) config.setAllowGeneralFallback(row.getBooleanValue("allowGeneralFallback"));
        if (row.containsKey("expectRag")) config.setExpectRag(row.getBooleanValue("expectRag"));
        if (row.containsKey("expectMemory")) config.setExpectMemory(row.getBooleanValue("expectMemory"));
        if (row.containsKey("expectTools")) config.setExpectTools(row.getBooleanValue("expectTools"));
        if (row.containsKey("allowTools")) config.setAllowTools(row.getBooleanValue("allowTools"));
        if (row.containsKey("simpleTask")) config.setSimpleTask(row.getBooleanValue("simpleTask"));
        if (row.containsKey("financialSafety")) config.setFinancialSafety(row.getBooleanValue("financialSafety"));
        if (row.containsKey("maxSteps")) config.setMaxSteps(row.getIntValue("maxSteps"));
        if (row.containsKey("minAnswerLength")) config.setMinAnswerLength(row.getIntValue("minAnswerLength"));
        if (row.containsKey("maxLatencyMs")) config.setMaxLatencyMs(row.getLongValue("maxLatencyMs"));
        return config.normalized();
    }

    private Map<String, Object> datasetView(AiEvalDataset dataset) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("datasetId", dataset.getDatasetId());
        view.put("name", dataset.getName());
        view.put("description", dataset.getDescription());
        view.put("executionMode", dataset.getExecutionMode());
        view.put("ownerUserId", dataset.getOwnerUserId());
        view.put("status", dataset.getStatus());
        view.put("latestVersionNo", dataset.getLatestVersionNo());
        AiEvalDatasetVersion draft = dao.findDraftVersion(dataset.getDatasetId());
        if (draft != null) {
            view.put("draftVersionId", draft.getVersionId());
            view.put("draftVersionNo", draft.getVersionNo());
            view.put("draftCaseCount", dao.countCases(draft.getVersionId(), false));
        }
        List<AiEvalDatasetVersion> versions = dao.listVersions(dataset.getDatasetId());
        versions.stream().filter(v -> "PUBLISHED".equals(v.getStatus())).findFirst().ifPresent(v -> {
            view.put("latestPublishedVersionId", v.getVersionId());
            view.put("publishedCaseCount", v.getCaseCount());
        });
        view.put("createdAt", dataset.getCreatedAt());
        view.put("updatedAt", dataset.getUpdatedAt());
        return view;
    }

    private Map<String, Object> versionView(AiEvalDatasetVersion version) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("versionId", version.getVersionId());
        view.put("datasetId", version.getDatasetId());
        view.put("versionNo", version.getVersionNo());
        view.put("status", version.getStatus());
        view.put("description", version.getDescription());
        view.put("caseCount", "DRAFT".equals(version.getStatus()) ? dao.countCases(version.getVersionId(), false) : version.getCaseCount());
        view.put("evaluatorVersion", version.getEvaluatorVersion());
        view.put("checksum", version.getChecksum());
        view.put("publishedAt", version.getPublishedAt());
        view.put("createdAt", version.getCreatedAt());
        return view;
    }

    Map<String, Object> caseView(AiEvalCase evalCase) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("caseId", evalCase.getCaseId());
        view.put("versionId", evalCase.getVersionId());
        view.put("stableKey", evalCase.getStableKey());
        view.put("sequenceNo", evalCase.getSequenceNo());
        view.put("conversationGroup", evalCase.getConversationGroup());
        view.put("category", evalCase.getCategory());
        view.put("tags", parseArray(evalCase.getTagsJson()));
        view.put("question", evalCase.getQuestion());
        view.put("config", parseConfig(evalCase.getConfigJson()));
        view.put("referenceAnswer", evalCase.getReferenceAnswer());
        view.put("enabled", evalCase.getEnabled());
        return view;
    }

    EvalCaseConfig parseConfig(String json) {
        try {
            EvalCaseConfig config = JSON.parseObject(json, EvalCaseConfig.class);
            return config == null ? new EvalCaseConfig().normalized() : config.normalized();
        } catch (Exception ignored) {
            return new EvalCaseConfig().normalized();
        }
    }

    private static List<Object> parseArray(String json) {
        try {
            JSONArray array = JSON.parseArray(json);
            return array == null ? List.of() : new ArrayList<>(array);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static AiEvalDatasetVersion newDraft(String datasetId, int versionNo, String description, LocalDateTime now) {
        return AiEvalDatasetVersion.builder()
                .versionId(UUID.randomUUID().toString()).datasetId(datasetId).versionNo(versionNo)
                .status("DRAFT").description(description).caseCount(0)
                .evaluatorVersion(EvalRuleEngine.VERSION)
                .evaluatorConfigJson("{\"weights\":{\"route\":0.16,\"answer\":0.22,\"step\":0.12,\"tool\":0.14,\"grounding\":0.12,\"memory\":0.10,\"stability\":0.08,\"efficiency\":0.04,\"safety\":0.02}}")
                .createdAt(now).updatedAt(now).build();
    }

    private static AiEvalCase copyCase(AiEvalCase source, String versionId, LocalDateTime now) {
        return AiEvalCase.builder()
                .caseId(UUID.randomUUID().toString()).versionId(versionId).stableKey(source.getStableKey())
                .sequenceNo(source.getSequenceNo()).conversationGroup(source.getConversationGroup())
                .category(source.getCategory()).tagsJson(source.getTagsJson()).question(source.getQuestion())
                .configJson(source.getConfigJson()).referenceAnswer(source.getReferenceAnswer()).enabled(source.getEnabled())
                .createdAt(now).updatedAt(now).build();
    }

    static EvalCaseConfig suggestConfig(int no, String category, String question) {
        EvalCaseConfig config = new EvalCaseConfig();
        String q = question == null ? "" : question;
        if ("RAG知识库".equals(category)) { config.setExpectRag(true); config.setMaxLatencyMs(160_000); }
        if ("MCP工具".equals(category)) { config.setExpectTools(true); config.setAllowTools(true); config.setMaxLatencyMs(420_000); config.setMaxSteps(6); }
        if (List.of("记忆累积", "多轮深度", "总结回顾").contains(category) || List.of(10, 21, 30, 60).contains(no)) config.setExpectMemory(true);
        if (q.contains("张伟") || q.contains("叫什么名字")) config.getMustMention().add("张伟");
        if (q.contains("杭州") || q.contains("哪个城市")) config.getMustMention().add("杭州");
        if (q.contains("Java") || q.contains("做什么工作")) config.getMustMention().add("Java");
        if (q.contains("Python") || q.contains("想学Python")) config.getMustMention().add("Python");
        if (containsAny(q, "菜", "红烧肉", "番茄", "蛋糕", "菜谱")) config.getExpectedCapabilities().add("cooking");
        if (containsAny(q, "减脂", "健身", "深蹲", "肩膀", "体重", "睡眠")) config.getExpectedCapabilities().add("fitness");
        if (containsAny(q, "理财", "基金", "预算", "定投", "复利", "投资")) { config.getExpectedCapabilities().add("finance"); config.setFinancialSafety(true); }
        if (containsAny(q, "旅行", "成都", "日本", "自驾", "高铁", "天气")) { config.getExpectedCapabilities().add("travel"); config.setAllowTools(true); }
        if (containsAny(q, "今天", "天气", "高铁", "最新", "搜索", "网上", "CSDN", "最近")) { config.setExpectTools(true); config.setAllowTools(true); config.setMaxLatencyMs(420_000); }
        if (containsAny(q, "博客", "Spring Boot", "微服务", "Redis", "Docker", "Kubernetes")) { config.getExpectedCapabilities().add("tech_blog"); config.setAllowTools(true); }
        if (containsAny(q, "SQL", "Python脚本", "贪吃蛇", "代码", "日志监控")) { config.getExpectedCapabilities().add("code"); config.setAllowTools(true); }
        if (containsAny(q, "学习", "Python", "英语", "PPT", "职业发展", "OKR", "目标")) config.getExpectedCapabilities().add("learning_path");
        if (containsAny(q, "读书", "人类简史", "思考，快与慢", "笔记")) config.getExpectedCapabilities().add("reading");
        if (containsAny(q, "诗", "故事", "年终总结", "请假邮件", "自我介绍")) config.getExpectedCapabilities().add("writing");
        if (q.contains("翻译")) config.getExpectedCapabilities().add("translation");
        if (q.contains("量子")) config.getExpectedCapabilities().add("science");
        if (containsAny(q, "时间安排", "时间分配")) config.getExpectedCapabilities().add("time_management");
        if (containsAny(q, "心情", "压力", "副业", "个人影响力")) config.getExpectedCapabilities().add("personal_growth");
        boolean simple = List.of(24, 40, 81, 87, 89).contains(no)
                || containsAny(q, "叫什么名字", "在哪个城市", "推荐几本", "准备哪些食材", "翻译这段英文")
                || ("初次认识".equals(category) && !List.of(2, 7, 9).contains(no));
        config.setSimpleTask(simple);
        if (simple) { config.setMaxSteps(2); config.setMaxLatencyMs(120_000); }
        if (List.of("复杂任务", "多轮深度", "总结回顾").contains(category)) { config.setMaxSteps(Math.max(5, config.getMaxSteps())); config.setMaxLatencyMs(Math.max(300_000, config.getMaxLatencyMs())); }
        return config.normalized();
    }

    private AiEvalDataset requireDataset(String datasetId) {
        AiEvalDataset dataset = dao.findDataset(requireText(datasetId, "datasetId 不能为空"));
        if (dataset == null) throw new IllegalArgumentException("数据集不存在");
        return dataset;
    }

    private AiEvalDatasetVersion requireDraft(String datasetId) {
        AiEvalDatasetVersion version = dao.findDraftVersion(datasetId);
        if (version == null) throw new IllegalArgumentException("数据集没有可编辑草稿");
        return version;
    }

    private AiEvalDatasetVersion requireVersion(String versionId) {
        AiEvalDatasetVersion version = dao.findVersion(requireText(versionId, "versionId 不能为空"));
        if (version == null) throw new IllegalArgumentException("数据集版本不存在");
        return version;
    }

    private static String checksum(List<AiEvalCase> cases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AiEvalCase item : cases) {
                digest.update((item.getStableKey() + "\n" + item.getQuestion() + "\n" + item.getConfigJson() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalStateException("计算数据集校验和失败", error);
        }
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String normalizeExecutionMode(String value) {
        String mode = blankToDefault(value, "INDEPENDENT").toUpperCase(Locale.ROOT);
        if (!List.of("INDEPENDENT", "SCENARIO").contains(mode)) throw new IllegalArgumentException("executionMode 只支持 INDEPENDENT 或 SCENARIO");
        return mode;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    @lombok.Data
    public static class CaseCommand {
        private String stableKey;
        private Integer sequenceNo;
        private String conversationGroup;
        private String category;
        private List<String> tags;
        private String question;
        private EvalCaseConfig config;
        private String referenceAnswer;
        private Boolean enabled;
    }
}
