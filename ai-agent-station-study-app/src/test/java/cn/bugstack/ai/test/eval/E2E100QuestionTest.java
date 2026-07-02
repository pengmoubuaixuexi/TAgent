package cn.bugstack.ai.test.eval;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.armory.ArmoryService;
import cn.bugstack.ai.domain.agent.service.router.UnifiedAgentRouter;
import cn.bugstack.ai.domain.agent.service.router.RouteDecision;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * E2E 100 问全链路测试
 * <p>
 * 模拟一个真实用户（userId=10001）按时间顺序的 100 次提问，
 * 测试从意图路由到最终回答的完整链路效果。
 * <p>
 * 覆盖模块：UnifiedAgentRouter / Fixed+Auto+Flow 策略 /
 * ChatMemory / ChatMemory Summary / Episodic Memory / Long-Term Memory / RAG / MCP
 * <p>
 * 测试前需手动清空 userId=10001 的所有记忆数据（SQL 见报告顶部注释）。
 */
@Slf4j
@RunWith(SpringRunner.class)
// DEFINED_PORT：测试期间真正起 embedded Tomcat 监听 server.port(8099)，与测试同 JVM 共享 MeterRegistry，
// 所以跑测试时可直接开 http://localhost:8099/observe.html / observe-mcp.html 看实时指标（测试 JVM 退出后即关闭）。
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
        "spring.test.mockito.reset=none",
        "logging.config=classpath:logback-e2e.xml"
})
public class E2E100QuestionTest {

    @Resource
    private UnifiedAgentRouter unifiedAgentRouter;

    @Resource
    private ArmoryService armoryService;

    /** 退休 dynamicMissingToolDesc 字段后，need 改为按 sessionId 预置进此 service。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService mcpToolCatalogService;

    @Resource(name = "fixedAgentExecuteStrategy")
    private IExecuteStrategy fixedStrategy;

    @Resource(name = "autoAgentExecuteStrategy")
    private IExecuteStrategy autoStrategy;

    @Resource(name = "flowAgentExecuteStrategy")
    private IExecuteStrategy flowStrategy;

    @Resource
    private IAgentRepository repository;

    @Resource
    private ApprovalChannelRegistry approvalChannelRegistry;

    /** 2026-06-23：评估采集补缺——每题执行前抓 STM 注入快照(对话历史摘要 + 未摘要会话窗口)写进 trace，
     *  否则判官看不到 agent 当时真正注入的对话上下文，会把"基于摘要/窗口的正确复述"误判成幻觉。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.infrastructure.adapter.repository.SummarizingChatMemory summarizingChatMemory;

    static final String USER_ID = "10001";
    // 2026-06-22：chat_memory 不清，改用全新 sessionId 隔离——旧 e2e-session-12 的历史不会被加载，等价从零累积。
    // userId 固定 10001（RAG 知识库只对 10001 开）；episodic 旧数据已挪到 10004、LTM 已删，10001 侧均等价为空。
    static final String SESSION_ID = "e2e-session-20260702";
    static final String TENANT_ID = "default";
    static final String RUN_DATE = System.getProperty("e2e.runDate", "2026-07-02");
    static final Path PROJECT_ROOT = resolveProjectRoot();

    /**
     * 2026-06-22 评估采集：每题结构化 trace 落盘目录(逐题 Q{no}.json)。
     * 含路由/各 step 信号/工具调用(开 result-preview 带真实返回)/RAG evidence(含 score)/记忆 evidence/最终答案 + 起止时间戳,
     * 事后据此 + 时间窗拉 ai_event_log(step 全 prompt/输出 + unified_router/rag_router 原文)做全面评估。
     * 注意：换跑日要把这里和 reportPath 的日期一并改。
     */
    static final String TRACE_DIR = PROJECT_ROOT.resolve("docs/dev-ops/test/e2e-100-trace-" + RUN_DATE).toString();

    // ==================== SSE 捕获 ====================

    static class TestSseEmitter extends ResponseBodyEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void send(Object data, org.springframework.http.MediaType mediaType) throws IOException {
            events.add(String.valueOf(data));
        }

        @Override
        public void send(Object data) throws IOException {
            events.add(String.valueOf(data));
        }

        String getSummaryContent() {
            for (int i = events.size() - 1; i >= 0; i--) {
                String ev = events.get(i);
                String json = ev.startsWith("data:") ? ev.substring(5).trim() : ev;
                if (json.contains("\"type\":\"summary\"") || json.contains("\"type\": \"summary\"")) {
                    return extractContent(json);
                }
            }
            for (int i = events.size() - 1; i >= 0; i--) {
                String ev = events.get(i);
                String json = ev.startsWith("data:") ? ev.substring(5).trim() : ev;
                if (json.contains("\"content\":") && !json.contains("\"type\":\"complete\"")) {
                    return extractContent(json);
                }
            }
            return "(无响应)";
        }

        int countEvent(String eventName) {
            int count = 0;
            for (String ev : events) {
                if (eventName.equals(extractEventName(ev))) count++;
            }
            return count;
        }

        List<JSONObject> payloads(String eventName) {
            List<JSONObject> list = new ArrayList<>();
            for (String ev : events) {
                if (!eventName.equals(extractEventName(ev))) continue;
                JSONObject obj = parseDataObject(ev);
                if (obj != null) list.add(obj);
            }
            return list;
        }

        int sumItems(String eventName) {
            int total = 0;
            for (JSONObject obj : payloads(eventName)) {
                JSONArray items = obj.getJSONArray("items");
                if (items != null) total += items.size();
            }
            return total;
        }

        Set<String> toolNames() {
            Set<String> names = new LinkedHashSet<>();
            for (String event : List.of("tool_call_start", "tool_call_end", "tool_call_error")) {
                for (JSONObject obj : payloads(event)) {
                    String toolName = obj.getString("toolName");
                    if (toolName != null && !toolName.isBlank()) names.add(toolName);
                }
            }
            return names;
        }

        int duplicateToolCallCount() {
            Map<String, Integer> counts = new ConcurrentHashMap<>();
            for (JSONObject obj : payloads("tool_call_start")) {
                String key = safe(obj.getString("toolName")) + "|" + safe(obj.getString("inputPreview"));
                counts.merge(key, 1, Integer::sum);
            }
            int duplicates = 0;
            for (Integer n : counts.values()) {
                if (n != null && n > 1) duplicates += n - 1;
            }
            return duplicates;
        }

        // 只统计"非执行步"调工具（=越权）。合法执行步不计入。
        // 注意：SSE 的 step 字段是 MeteredToolCallback 取的 toolContext"stepLabel"（=各步的 displayName），
        // 不是 stepId！所以要按 displayName 排除：auto 执行步含"执行"(精准执行/执行当前任务步骤)、
        // flow 执行子步"执行 步骤N/第N步"也含"执行"、flow EXECUTOR 最终合成"最终合成"、fixed 单步"回答"。
        // 其余 displayName（需求分析/MCP工具分析/步骤规划/质量评审/最终总结）均不含"执行"，调工具即越权。
        // 同时保留 stepId 片段做兜底（MDC.get("step") 回退分支可能给的是 stepId）。
        int earlyStepToolCallCount() {
            int count = 0;
            for (JSONObject obj : payloads("tool_call_start")) {
                String step = safe(obj.getString("step")).toLowerCase();
                if (step.isEmpty()) continue;
                boolean executorStep = step.contains("执行") || step.contains("回答") || step.contains("最终合成")
                        || step.contains("precision_executor") || step.contains("execute_step")
                        || step.contains("execute_steps") || step.contains("final_synthesis") || step.contains("fixed");
                if (!executorStep) count++;
            }
            return count;
        }

        /**
         * 2026-06-22 评估采集：把本题完整 SSE 事件流结构化(剔除高频 token 噪音,最终答案另存 finalAnswer),
         * 供事后逐题分析——含 step_start/tool_call_*(开 result-preview 后带 resultPreview)/rag_evidence(含 score)/
         * memory_evidence/approval 等全部观察事件。
         */
        JSONArray structuredEvents() {
            JSONArray arr = new JSONArray();
            for (String ev : events) {
                String name = extractEventName(ev);
                if ("token".equals(name)) continue; // 逐 token 流式噪音跳过
                JSONObject o = new JSONObject(true);
                o.put("event", name.isEmpty() ? "(unnamed)" : name);
                JSONObject data = parseDataObject(ev);
                if (data != null) {
                    o.put("data", data);
                } else {
                    int idx = ev == null ? -1 : ev.indexOf("data:");
                    String raw = idx >= 0 ? ev.substring(idx + 5).trim() : (ev == null ? "" : ev.trim());
                    if (!raw.isBlank()) o.put("raw", raw.length() > 2000 ? raw.substring(0, 2000) + "...(truncated)" : raw);
                }
                arr.add(o);
            }
            return arr;
        }

        private static String extractEventName(String ev) {
            if (ev == null) return "";
            for (String line : ev.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("event:")) {
                    return trimmed.substring("event:".length()).trim();
                }
            }
            return "";
        }

        private static JSONObject parseDataObject(String ev) {
            if (ev == null) return null;
            for (String line : ev.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String data = trimmed.substring("data:".length()).trim();
                if (data.isBlank() || !data.startsWith("{")) return null;
                try {
                    return JSON.parseObject(data);
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }

        private String extractContent(String json) {
            int idx = json.indexOf("\"content\":");
            if (idx < 0) return json;
            int start = json.indexOf("\"", idx + 10);
            if (start < 0) return json;
            start++;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == '"') { sb.append('"'); i++; }
                    else if (next == 'n') { sb.append('\n'); i++; }
                    else if (next == '\\') { sb.append('\\'); i++; }
                    else { sb.append(c); }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    // ==================== 测试用例 ====================

    static class TestCase {
        final int no;
        final String category;
        final String question;
        final EvalSpec evalSpec;

        TestCase(int no, String category, String question) {
            this.no = no;
            this.category = category;
            this.question = question;
            this.evalSpec = EvalSpec.forCase(no, category, question);
        }
    }

    static class ExecutionResult {
        final String strategy;
        final String response;
        final String status;
        final TestSseEmitter emitter;

        ExecutionResult(String strategy, String response, String status, TestSseEmitter emitter) {
            this.strategy = strategy;
            this.response = response;
            this.status = status;
            this.emitter = emitter;
        }
    }

    static class TestResult {
        final TestCase testCase;
        final String agentId;
        final String strategy;
        final String response;
        final String status;
        final long costMs;
        final AgentEval eval;

        TestResult(TestCase testCase, String agentId, String strategy, String response, String status,
                   long costMs, AgentEval eval) {
            this.testCase = testCase;
            this.agentId = agentId;
            this.strategy = strategy;
            this.response = response;
            this.status = status;
            this.costMs = costMs;
            this.eval = eval;
        }
    }

    static class EvalSpec {
        final Set<String> expectedCapabilities = new LinkedHashSet<>();
        final Set<String> mustMention = new LinkedHashSet<>();
        boolean allowGeneralFallback = true;
        boolean expectRag;
        boolean expectMemory;
        boolean expectTools;
        boolean allowTools;
        boolean simpleTask;
        boolean financialSafety;
        int maxSteps = 4;
        long maxLatencyMs = 180_000;

        static EvalSpec forCase(int no, String category, String question) {
            EvalSpec spec = new EvalSpec();
            String q = question == null ? "" : question;
            if ("RAG知识库".equals(category)) {
                spec.expectRag = true;
                spec.maxLatencyMs = 160_000;
                spec.expectedCapabilities.add("general");
            }
            if ("MCP工具".equals(category)) {
                spec.expectTools = true;
                spec.allowTools = true;
                spec.maxLatencyMs = 420_000;
            }
            if ("记忆累积".equals(category) || "多轮深度".equals(category) || "总结回顾".equals(category)
                    || no == 10 || no == 21 || no == 30 || no == 60) {
                spec.expectMemory = true;
            }
            if (q.contains("张伟") || q.contains("叫什么名字")) spec.mustMention.add("张伟");
            if (q.contains("杭州") || q.contains("哪个城市")) spec.mustMention.add("杭州");
            if (q.contains("Java") || q.contains("做什么工作")) spec.mustMention.add("Java");
            if (q.contains("Python") || q.contains("想学Python")) spec.mustMention.add("Python");
            if (q.contains("3天") || q.contains("三天")) spec.mustMention.add("3");
            if (q.contains("成都")) spec.mustMention.add("成都");
            if (q.contains("五一")) spec.mustMention.add("五一");

            if (containsAny(q, "菜", "红烧肉", "番茄", "蛋糕", "菜谱")) spec.expectedCapabilities.add("cooking");
            if (containsAny(q, "减脂", "健身", "深蹲", "肩膀", "体重", "睡眠")) spec.expectedCapabilities.add("fitness");
            if (containsAny(q, "理财", "基金", "预算", "定投", "复利", "投资")) {
                spec.expectedCapabilities.add("finance");
                spec.financialSafety = true;
            }
            if (containsAny(q, "旅行", "成都", "日本", "自驾", "高铁", "天气")) {
                spec.expectedCapabilities.add("travel");
                if (containsAny(q, "今天", "天气", "高铁", "最新", "搜索", "网上", "CSDN", "最近")) {
                    spec.expectTools = true;
                    spec.allowTools = true;
                } else {
                    spec.allowTools = true;
                }
            }
            if (containsAny(q, "博客", "Spring Boot", "微服务", "Redis", "Docker", "Kubernetes")) {
                spec.expectedCapabilities.add("tech_blog");
                spec.allowTools = true;
            }
            if (containsAny(q, "SQL", "Python脚本", "贪吃蛇", "代码", "日志监控")) {
                spec.expectedCapabilities.add("code");
                spec.allowTools = true;
            }
            if (containsAny(q, "学习", "Python", "英语", "PPT", "职业发展", "OKR", "目标")) {
                spec.expectedCapabilities.add("learning_path");
                spec.allowTools = true;
            }
            if (containsAny(q, "读书", "人类简史", "思考，快与慢", "笔记")) spec.expectedCapabilities.add("reading");
            if (containsAny(q, "诗", "故事", "年终总结", "请假邮件", "自我介绍")) spec.expectedCapabilities.add("writing");
            if (containsAny(q, "翻译")) spec.expectedCapabilities.add("translation");
            if (containsAny(q, "量子")) spec.expectedCapabilities.add("science");
            if (containsAny(q, "时间安排", "时间分配")) spec.expectedCapabilities.add("time_management");
            if (containsAny(q, "心情", "压力", "副业", "个人影响力")) spec.expectedCapabilities.add("personal_growth");
            if (spec.expectedCapabilities.isEmpty()) spec.expectedCapabilities.add("general");

            spec.simpleTask = isSimpleTask(no, category, q);
            if (spec.simpleTask) {
                spec.maxSteps = 2;
                spec.maxLatencyMs = 120_000;
            }
            if (spec.expectTools) {
                spec.allowTools = true;
                spec.maxSteps = Math.max(spec.maxSteps, 6);
                spec.maxLatencyMs = Math.max(spec.maxLatencyMs, 420_000);
            }
            if ("复杂任务".equals(category) || "多轮深度".equals(category) || "总结回顾".equals(category)) {
                spec.maxSteps = Math.max(spec.maxSteps, 5);
                spec.maxLatencyMs = Math.max(spec.maxLatencyMs, 300_000);
            }
            return spec;
        }

        private static boolean isSimpleTask(int no, String category, String q) {
            if (no == 24 || no == 40 || no == 81 || no == 87 || no == 89) return true;
            if (containsAny(q, "叫什么名字", "在哪个城市", "推荐几本", "准备哪些食材", "翻译这段英文")) return true;
            return "初次认识".equals(category) && no != 2 && no != 7 && no != 9;
        }

        private static boolean containsAny(String text, String... keywords) {
            if (text == null) return false;
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && text.contains(keyword)) return true;
            }
            return false;
        }

        String summary() {
            return "expectedCapabilities=" + expectedCapabilities
                    + ", expectRag=" + expectRag
                    + ", expectMemory=" + expectMemory
                    + ", expectTools=" + expectTools
                    + ", allowTools=" + allowTools
                    + ", simpleTask=" + simpleTask
                    + ", financialSafety=" + financialSafety
                    + ", mustMention=" + mustMention
                    + ", maxSteps=" + maxSteps
                    + ", maxLatencyMs=" + maxLatencyMs;
        }
    }

    static class AgentEval {
        double routeScore;
        double answerScore;
        double stepScore;
        double toolScore;
        double groundingScore;
        double memoryScore;
        double stabilityScore;
        double efficiencyScore;
        double safetyScore;
        double overallScore;
        int stepStartCount;
        int toolCallCount;
        int toolErrorCount;
        int duplicateToolCalls;
        int ragEvidenceCount;
        int memoryEvidenceEventCount;
        int memoryEvidenceCount;
        int earlyStepToolCalls;
        final List<String> warnings = new ArrayList<>();

        static AgentEval evaluate(TestCase tc, String agentId, String strategy, String status,
                                  String response, long costMs, TestSseEmitter emitter) {
            AgentEval eval = new AgentEval();
            TestSseEmitter safeEmitter = emitter == null ? new TestSseEmitter() : emitter;
            EvalSpec spec = tc.evalSpec;

            eval.stepStartCount = safeEmitter.countEvent("step_start");
            eval.toolCallCount = safeEmitter.countEvent("tool_call_start");
            eval.toolErrorCount = safeEmitter.countEvent("tool_call_error");
            eval.duplicateToolCalls = safeEmitter.duplicateToolCallCount();
            eval.ragEvidenceCount = safeEmitter.sumItems("rag_evidence");
            eval.memoryEvidenceEventCount = safeEmitter.countEvent("memory_evidence");
            eval.memoryEvidenceCount = safeEmitter.sumItems("memory_evidence");
            eval.earlyStepToolCalls = safeEmitter.earlyStepToolCallCount();

            eval.routeScore = scoreRoute(spec, agentId);
            eval.answerScore = scoreAnswer(spec, response);
            eval.stepScore = scoreSteps(spec, eval.stepStartCount, eval.earlyStepToolCalls);
            eval.toolScore = scoreTools(spec, eval.toolCallCount, eval.toolErrorCount, eval.duplicateToolCalls);
            eval.groundingScore = scoreGrounding(spec, eval.ragEvidenceCount, eval.toolCallCount);
            eval.memoryScore = scoreMemory(spec, response, eval.memoryEvidenceCount);
            eval.stabilityScore = scoreStability(status, eval.toolCallCount, eval.toolErrorCount);
            eval.efficiencyScore = scoreEfficiency(spec, costMs);
            eval.safetyScore = scoreSafety(spec, response);

            collectWarnings(eval, spec, tc, agentId, strategy, response, costMs);
            eval.overallScore = round2(
                    0.16 * eval.routeScore
                            + 0.22 * eval.answerScore
                            + 0.12 * eval.stepScore
                            + 0.14 * eval.toolScore
                            + 0.12 * eval.groundingScore
                            + 0.10 * eval.memoryScore
                            + 0.08 * eval.stabilityScore
                            + 0.04 * eval.efficiencyScore
                            + 0.02 * eval.safetyScore);
            return eval;
        }

        String grade() {
            if (overallScore >= 0.85) return "A";
            if (overallScore >= 0.70) return "B";
            if (overallScore >= 0.55) return "C";
            return "D";
        }

        String warningText() {
            if (warnings.isEmpty()) return "无";
            return String.join("；", warnings);
        }

        private static double scoreRoute(EvalSpec spec, String agentId) {
            Map<String, Double> fit = capabilityFit(agentId);
            // 按"适配度"打分：取期望能力里 agent 拟合度最高的那个（主业=1.0、相关副业=0.7~0.8）
            double best = 0.0;
            for (String c : spec.expectedCapabilities) {
                best = Math.max(best, fit.getOrDefault(c, 0.0));
            }
            if (best > 0) return best;
            // 没命中期望能力：通用题路由到任何专精 agent，或路由到通用 catch-all agent → 兜底分(不算错域)
            if (spec.expectedCapabilities.contains("general")) return 0.6;
            if (fit.containsKey("general") && spec.allowGeneralFallback) return 0.6;
            return 0.0;
        }

        private static double scoreAnswer(EvalSpec spec, String response) {
            String r = response == null ? "" : response;
            if (r.isBlank() || r.contains("(无响应)") || r.contains("(执行异常") || r.contains("(超时")) return 0.0;
            double score = r.length() >= 80 ? 1.0 : 0.65;
            for (String keyword : spec.mustMention) {
                if (!r.contains(keyword)) score -= 0.18;
            }
            return clamp(score);
        }

        private static double scoreSteps(EvalSpec spec, int stepStartCount, int earlyStepToolCalls) {
            double score = 1.0;
            if (earlyStepToolCalls > 0) score -= Math.min(0.5, 0.2 * earlyStepToolCalls);
            if (spec.simpleTask && stepStartCount > spec.maxSteps) score -= 0.35;
            else if (stepStartCount > spec.maxSteps + 2) score -= 0.2;
            return clamp(score);
        }

        private static double scoreTools(EvalSpec spec, int toolCallCount, int toolErrorCount, int duplicateToolCalls) {
            double score;
            if (spec.expectTools) {
                score = toolCallCount > 0 ? 1.0 : 0.2;
            } else if (spec.allowTools) {
                score = toolCallCount <= 8 ? 1.0 : 0.8;
            } else {
                score = toolCallCount == 0 ? 1.0 : (toolCallCount <= 2 ? 0.75 : 0.45);
            }
            if (duplicateToolCalls > 0) score -= Math.min(0.3, duplicateToolCalls * 0.08);
            if (toolErrorCount > 0) score -= Math.min(0.2, toolErrorCount * 0.05);
            return clamp(score);
        }

        private static double scoreGrounding(EvalSpec spec, int ragEvidenceCount, int toolCallCount) {
            if (spec.expectRag && ragEvidenceCount == 0) return 0.45;
            if (spec.expectTools && toolCallCount == 0) return 0.35;
            if (!spec.expectRag && ragEvidenceCount > 8) return 0.75;
            return 1.0;
        }

        private static double scoreMemory(EvalSpec spec, String response, int memoryEvidenceCount) {
            String r = response == null ? "" : response;
            double score = 1.0;
            if (spec.expectMemory) {
                boolean keywordHit = spec.mustMention.stream().anyMatch(r::contains);
                score = memoryEvidenceCount > 0 || keywordHit ? 1.0 : 0.35;
            }
            return clamp(score);
        }

        private static double scoreStability(String status, int toolCallCount, int toolErrorCount) {
            if (status == null) return 0.0;
            if (status.startsWith("PASS")) return toolErrorCount > 0 && toolCallCount > 0 ? 0.85 : 1.0;
            if ("RETRY_PASS".equals(status)) return 0.85;
            return 0.0;
        }

        private static double scoreEfficiency(EvalSpec spec, long costMs) {
            if (costMs <= spec.maxLatencyMs) return 1.0;
            if (costMs <= spec.maxLatencyMs * 2) return 0.65;
            return 0.35;
        }

        private static double scoreSafety(EvalSpec spec, String response) {
            if (!spec.financialSafety) return 1.0;
            String r = response == null ? "" : response;
            return (r.contains("不构成投资建议") || r.contains("投资有风险") || r.contains("仅供参考")) ? 1.0 : 0.55;
        }

        private static void collectWarnings(AgentEval eval, EvalSpec spec, TestCase tc, String agentId,
                                            String strategy, String response, long costMs) {
            if (eval.routeScore < 1.0) eval.warnings.add("路由能力不完全匹配 expected=" + spec.expectedCapabilities + ", actual=" + capabilityFit(agentId).keySet());
            if (eval.answerScore < 0.8) eval.warnings.add("最终答案为空/过短/缺少关键字");
            if (eval.earlyStepToolCalls > 0) eval.warnings.add("非执行步(分析/规划/质检/汇总)越权调用工具 " + eval.earlyStepToolCalls + " 次");
            if (spec.simpleTask && eval.stepStartCount > spec.maxSteps) eval.warnings.add("简单任务链路过重 steps=" + eval.stepStartCount);
            if (spec.expectTools && eval.toolCallCount == 0) eval.warnings.add("预期工具题但未捕获 tool_call 事件");
            if (!spec.expectTools && !spec.allowTools && eval.toolCallCount > 2) eval.warnings.add("不应使用工具的题目工具调用偏多 tools=" + eval.toolCallCount);
            if (eval.duplicateToolCalls > 0) eval.warnings.add("重复工具调用 " + eval.duplicateToolCalls + " 次");
            if (spec.expectRag && eval.ragEvidenceCount == 0) eval.warnings.add("预期 RAG evidence 但未捕获");
            if (costMs > spec.maxLatencyMs) eval.warnings.add("耗时超预算 " + costMs + "ms > " + spec.maxLatencyMs + "ms");
            if (spec.financialSafety && eval.safetyScore < 1.0) eval.warnings.add("理财类回答缺少风险声明");
        }

        /**
         * agent → 它覆盖的能力及<b>适配度</b>（主业=1.0、相关副业=0.7~0.8），按 DB 描述真实领域校正（2026-06-07）。
         * 旧版每 agent 只给一个能力、且 8006/8012/8016 标错（科学/个人成长/时间管理），且命中即一刀切满分；
         * 现在按拟合度分级打分。"general" 只给真·通用 catch-all（8011/8012/8013），专精 agent 不给。
         */
        private static Map<String, Double> capabilityFit(String agentId) {
            if (agentId == null) return Map.of();
            return switch (agentId) {
                case "8001" -> Map.of("time_management", 0.8, "personal_growth", 0.8, "travel", 0.6); // 生活教练：天气/出行/习惯/情绪/时间
                case "8002" -> Map.of("cooking", 1.0, "fitness", 0.7);                  // 美食烹饪：菜谱(主) + 营养/热量(副)
                case "8003" -> Map.of("reading", 1.0, "learning_path", 0.75);           // 读书顾问：书籍/阅读(主) + 学习(副)
                case "8004" -> Map.of("fitness", 1.0);                                  // 健身教练
                case "8005" -> Map.of("translation", 1.0, "writing", 0.75);             // 翻译润色
                case "8006" -> Map.of("time_management", 1.0);                          // 日程规划助手（非科学！）
                case "8007" -> Map.of("finance", 1.0);                                  // 记账理财
                case "8008" -> Map.of("learning_path", 1.0, "code", 0.8, "reading", 0.7); // 学习路径规划师
                case "8009" -> Map.of("writing", 1.0, "personal_growth", 0.7);          // 创意写作
                case "8010" -> Map.of("travel", 1.0, "time_management", 0.6);           // 旅行规划：行程/路线/天气
                case "8011" -> Map.of("general", 1.0);                                  // 通用问答 Fixed：纯 catch-all
                case "8012" -> Map.of("general", 1.0, "personal_growth", 0.8, "learning_path", 0.8, "science", 0.8); // 通用 Auto：深入分析
                case "8013" -> Map.of("general", 1.0, "code", 0.8, "tech_blog", 0.8);   // 通用 Flow：复合任务
                case "8014" -> Map.of("tech_blog", 1.0, "code", 0.8);                   // 技术博客
                case "8015" -> Map.of("code", 0.7);                                     // 日志分析（专精，不给 general）
                case "8016" -> Map.of("code", 0.7);                                     // 监控运维（非时间管理！专精，不给 general）
                default -> Map.of();
            };
        }

        private static double clamp(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }

        private static double round2(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    // ==================== 主测试 ====================

    static final long QUESTION_TIMEOUT_SECONDS = 1800; // 每题硬上限 30 分钟（让 GitHub MCP 等极慢工具自己撞 reactor 30s 内置超时退出，避免单题永久卡死拖死后续 99 题）
    static final ExecutorService questionExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "e2e-q-executor");
        t.setDaemon(true);
        return t;
    });

    @Test
    public void runE2E100() throws Exception {
        log.info("========== E2E 100 问全链路测试开始 ==========");
        log.info("userId={}, sessionId={}, tenantId={}", USER_ID, SESSION_ID, TENANT_ID);

        List<TestCase> cases = build100Questions();
        List<TestResult> results = new CopyOnWriteArrayList<>();
        int[] passFail = {0, 0}; // [pass, fail]
        Map<String, Integer> strategyCount = new ConcurrentHashMap<>();
        Map<String, Integer> agentCount = new ConcurrentHashMap<>();
        long[] totalCost = {0};

        // 增量写报告的路径
        String reportPath = PROJECT_ROOT.resolve("docs/dev-ops/test/e2e-100-report-" + RUN_DATE + ".md").toString();
        Files.createDirectories(Paths.get(reportPath).getParent());
        // 2026-06-22 评估采集：逐题结构化 trace 落盘目录
        Files.createDirectories(Paths.get(TRACE_DIR));

        for (TestCase tc : cases) {
            log.info("[Q{}] {} 开始: {}", tc.no, tc.category, tc.question.length() > 40 ? tc.question.substring(0, 40) + "..." : tc.question);
            long start = System.currentTimeMillis();

            // 设置 MDC（记忆模块从 MDC 读取 userId/tenantId/sessionId）
            MDC.put("userId", USER_ID);
            MDC.put("tenantId", TENANT_ID);
            MDC.put("sessionId", SESSION_ID);
            // 2026-05-20：给每题打稳定 traceId/qid，sessionId 保持不变让 ChatMemory 累积，
            // ES 里按 traceId:"Q27" 可单独筛出该题完整链路（含 llm.wire / step.transition / mcp.tool.call）
            MDC.put("traceId", "Q" + tc.no);
            MDC.put("qid", "Q" + tc.no);

            String agentId = "(未路由)";
            String strategy = "(未知)";
            String response = "(未执行)";
            String status = "PASS";
            TestSseEmitter currentEmitter = new TestSseEmitter();
            // 执行前抓 STM 注入快照（此刻 = agent 即将看到的对话上下文；本题 Q&A 尚未写入，不会污染）
            com.alibaba.fastjson.JSONObject stmSnapshot = captureStmSnapshot();

            try {
                // 1. 路由（走 routeDecision，与 AgentDispatchDispatchService 一致：拿 agentId + 可能缺失的工具描述，
                //    让动态补工具(PgVector embedding)链路被真正触发。route() 只返回 agentId，会把 missingToolDesc 丢掉，导致补工具永不触发。）
                RouteDecision routeDecision = unifiedAgentRouter.routeDecision(tc.question);
                agentId = routeDecision != null ? routeDecision.agentId() : null;
                // 多条 need 用换行连成单串（与 AgentDispatchDispatchService 一致），下游拆开各取 top-k 再并集
                String missingToolDesc = (routeDecision != null && routeDecision.hasMissingToolDesc())
                        ? routeDecision.missingToolDescJoined() : null;
                if (agentId == null) {
                    agentId = "(路由失败)";
                    status = "FAIL";
                    passFail[1]++;
                } else {
                    // 2+3+4. 装配 + 查策略 + 执行（全部带超时，防止 MCP 初始化卡死）
                    final String finalAgentId = agentId;
                    ExecutionResult result = executeWithRetry(tc, finalAgentId, missingToolDesc, 0);
                    strategy = result.strategy;
                    response = result.response;
                    status = result.status;
                    currentEmitter = result.emitter;
                    if (isPassStatus(status)) passFail[0]++;
                    else passFail[1]++;

                    strategyCount.merge(strategy, 1, Integer::sum);
                    agentCount.merge(agentId, 1, Integer::sum);
                }
            } catch (Exception e) {
                response = "(执行异常: " + e.getMessage() + ")";
                status = "ERROR";
                passFail[1]++;
                log.error("[Q{}] 执行异常: {}", tc.no, e.getMessage(), e);
            } finally {
                MDC.clear();
            }

            long cost = System.currentTimeMillis() - start;
            totalCost[0] += cost;

            log.info("[Q{}] {} 耗时={}ms agent={} strategy={}", tc.no, status, cost, agentId, strategy);

            // 截取回答前 300 字用于报告
            String shortResponse = response.length() > 300 ? response.substring(0, 300) + "..." : response;
            AgentEval eval = AgentEval.evaluate(tc, agentId, strategy, status, response, cost, currentEmitter);
            results.add(new TestResult(tc, agentId, strategy, shortResponse, status, cost, eval));

            // 2026-06-22 评估采集：每题完成即落一份完整结构化 trace（full response + 全事件），防崩溃丢失
            writeQuestionTrace(tc, agentId, strategy, status, response, start, cost, currentEmitter, eval, stmSnapshot);

            // 每完成一个问题就增量写一次报告（防崩溃丢失进度）
            writeIncrementalReport(reportPath, results, passFail[0], passFail[1], totalCost[0], strategyCount, agentCount);

            // 等 60s 让 LTM 异步提取完成，避免下一题的路由 LLM 调用和 LTM 提取并发争抢网关
            if (tc.no < cases.size()) {
                log.info("[Q{}] 等待 60s 让 LTM 异步提取完成...", tc.no);
                Thread.sleep(30_000);
            }
        }

        log.info("========== E2E 100 问完成: PASS={}, FAIL={}, 平均耗时={}ms ==========",
                passFail[0], passFail[1], cases.size() > 0 ? (totalCost[0] / cases.size()) : 0);

        questionExecutor.shutdownNow();
    }

    /**
     * 2026-06-22 评估采集：逐题完整结构化 trace 落盘 {@code TRACE_DIR/Q{no}.json}。
     * <p>含元数据 + 起止时间戳(供时间窗 join ai_event_log) + 评估信号 + 最终答案(full) + 全 SSE 观察事件
     * (step/tool_call(开 result-preview 带 resultPreview)/rag_evidence(含 score)/memory_evidence/approval)。
     * 任何异常都吞掉，绝不影响主测试流程。</p>
     */
    private void writeQuestionTrace(TestCase tc, String agentId, String strategy, String status,
                                    String fullResponse, long startTs, long costMs,
                                    TestSseEmitter emitter, AgentEval eval,
                                    com.alibaba.fastjson.JSONObject stmSnapshot) {
        try {
            DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            JSONObject root = new JSONObject(true);
            root.put("no", tc.no);
            root.put("category", tc.category);
            root.put("question", tc.question);
            root.put("agentId", agentId);
            root.put("strategy", strategy);
            root.put("status", status);
            root.put("startTs", startTs);
            root.put("endTs", startTs + costMs);
            root.put("startTime", java.time.Instant.ofEpochMilli(startTs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(tsFmt));
            root.put("endTime", java.time.Instant.ofEpochMilli(startTs + costMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(tsFmt));
            root.put("costMs", costMs);
            root.put("evalSpec", tc.evalSpec.summary());

            JSONObject signals = new JSONObject(true);
            signals.put("steps", eval.stepStartCount);
            signals.put("tools", eval.toolCallCount);
            signals.put("toolErrors", eval.toolErrorCount);
            signals.put("duplicateTools", eval.duplicateToolCalls);
            signals.put("ragEvidence", eval.ragEvidenceCount);
            signals.put("memoryEvents", eval.memoryEvidenceEventCount);
            signals.put("memoryEvidence", eval.memoryEvidenceCount);
            signals.put("earlyStepTools", eval.earlyStepToolCalls);
            signals.put("overallScore", eval.overallScore);
            signals.put("grade", eval.grade());
            signals.put("warnings", eval.warningText());
            root.put("signals", signals);

            // STM 注入快照（对话历史摘要 + 未摘要会话窗口）——判官据此区分"基于上下文的正确复述 vs 真编造"
            root.put("stmSnapshot", stmSnapshot != null ? stmSnapshot : new JSONObject(true));

            root.put("finalAnswer", fullResponse);
            root.put("events", emitter.structuredEvents());

            Files.writeString(Paths.get(TRACE_DIR, "Q" + tc.no + ".json"),
                    JSON.toJSONString(root, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Q{}] 写 trace 失败: {}", tc.no, e.getMessage());
        }
    }

    /**
     * 执行前抓 STM 注入快照：{@code SummarizingChatMemory.get(conversationId)} 返回的就是即将注入 agent 的内容——
     * 首条 SystemMessage【对话历史摘要】= STM 摘要，其余 = 未摘要会话窗口（最近若干轮原文）。
     * 两者都要采：最近 1-2 轮可能还没进摘要但在窗口里，缺了判官同样会误判。
     */
    private com.alibaba.fastjson.JSONObject captureStmSnapshot() {
        com.alibaba.fastjson.JSONObject snap = new com.alibaba.fastjson.JSONObject(true);
        try {
            if (summarizingChatMemory == null) { snap.put("available", false); snap.put("reason", "no SummarizingChatMemory bean"); return snap; }
            String conversationId = TENANT_ID + ":" + USER_ID + ":" + SESSION_ID;
            java.util.List<org.springframework.ai.chat.messages.Message> msgs = summarizingChatMemory.get(conversationId);
            String summary = "";
            com.alibaba.fastjson.JSONArray window = new com.alibaba.fastjson.JSONArray();
            for (org.springframework.ai.chat.messages.Message m : msgs) {
                String text = m.getText();
                String role = m.getMessageType() == null ? "?" : m.getMessageType().name();
                if (text != null && text.startsWith("【对话历史摘要】")) {
                    summary = text.substring("【对话历史摘要】".length());
                } else {
                    com.alibaba.fastjson.JSONObject mo = new com.alibaba.fastjson.JSONObject(true);
                    mo.put("role", role);
                    mo.put("content", text != null && text.length() > 1500 ? text.substring(0, 1500) + "…[截断]" : text);
                    window.add(mo);
                }
            }
            snap.put("available", true);
            snap.put("stmSummary", summary);
            snap.put("windowSize", window.size());
            snap.put("conversationWindow", window);
        } catch (Exception e) {
            snap.put("available", false);
            snap.put("reason", String.valueOf(e.getMessage()));
            log.warn("[Q?] 抓 STM 快照失败: {}", e.getMessage());
        }
        return snap;
    }

    private void writeIncrementalReport(String path, List<TestResult> results, int pass, int fail, long totalCost,
                                         Map<String, Integer> strategyCount, Map<String, Integer> agentCount) {
        StringBuilder md = new StringBuilder();
        md.append("# E2E 100 问全链路测试报告\n\n");
        md.append("> 测试时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        md.append("> 用户身份：userId=").append(USER_ID).append(", tenantId=").append(TENANT_ID).append(", sessionId=").append(SESSION_ID).append("\n");
        md.append("> 测试目的：验证全链路效果（路由+记忆+RAG+MCP）\n\n");

        md.append("## 测试概览\n\n");
        md.append("| 指标 | 值 |\n|---|---|\n");
        md.append("| 已完成 | ").append(results.size()).append("/100 |\n");
        md.append("| 通过 | ").append(pass).append(" |\n");
        md.append("| 失败/超时/错误 | ").append(fail).append(" |\n");
        // 重试统计
        long retryPass = results.stream().filter(r -> "RETRY_PASS".equals(r.status)).count();
        long retryFail = results.stream().filter(r -> "RETRY_FAIL".equals(r.status)).count();
        if (retryPass + retryFail > 0) {
            md.append("| 网络重试成功 | ").append(retryPass).append(" |\n");
            md.append("| 网络重试仍失败 | ").append(retryFail).append(" |\n");
        }
        md.append("| 平均耗时 | ").append(results.size() > 0 ? (totalCost / results.size()) : 0).append("ms |\n");
        md.append("| 总耗时 | ").append(totalCost / 1000).append("s |\n\n");

        appendAgentEvalOverview(md, results);

        md.append("### 路由策略分布\n\n");
        md.append("| 策略 | 次数 |\n|---|---|\n");
        strategyCount.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n");

        md.append("### Agent 分布\n\n");
        md.append("| Agent ID | 次数 |\n|---|---|\n");
        agentCount.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n---\n\n");

        md.append("## 详细结果\n\n");

        for (TestResult r : results) {
            String no = String.valueOf(r.testCase.no), category = r.testCase.category, question = r.testCase.question;
            String agentId = r.agentId, strategy = r.strategy, response = r.response, status = r.status, cost = r.costMs + "ms";

            md.append("### Q").append(no).append(". [").append(category).append("] ").append(question, 0, Math.min(question.length(), 60));
            if (question.length() > 60) md.append("...");
            md.append("\n\n");
            md.append("- **状态**：").append(status).append("\n");
            md.append("- **路由**：").append(agentId).append(" (").append(strategy).append(")\n");
            md.append("- **耗时**：").append(cost).append("\n");
            md.append("- **Eval Metadata**：").append(r.testCase.evalSpec.summary()).append("\n");
            md.append("- **Agent Eval**：").append(r.eval.overallScore).append(" / ").append(r.eval.grade())
                    .append("（route=").append(r.eval.routeScore)
                    .append(", answer=").append(r.eval.answerScore)
                    .append(", step=").append(r.eval.stepScore)
                    .append(", tool=").append(r.eval.toolScore)
                    .append(", grounding=").append(r.eval.groundingScore)
                    .append(", memory=").append(r.eval.memoryScore)
                    .append("）\n");
            md.append("- **评估信号**：steps=").append(r.eval.stepStartCount)
                    .append(", tools=").append(r.eval.toolCallCount)
                    .append(", toolErrors=").append(r.eval.toolErrorCount)
                    .append(", duplicateTools=").append(r.eval.duplicateToolCalls)
                    .append(", ragEvidence=").append(r.eval.ragEvidenceCount)
                    .append(", memoryEvents=").append(r.eval.memoryEvidenceEventCount)
                    .append(", memoryEvidence=").append(r.eval.memoryEvidenceCount)
                    .append(", earlyStepTools=").append(r.eval.earlyStepToolCalls).append("\n");
            md.append("- **扣分/观察**：").append(r.eval.warningText()).append("\n");
            md.append("- **回答**：").append(response).append("\n\n");
            md.append("---\n\n");
        }

        // 汇总表
        md.append("## 汇总表\n\n");
        md.append("| # | 分类 | Agent | 策略 | 状态 | 耗时 | Eval | 等级 |\n");
        md.append("|---|------|-------|------|------|------|---:|---|\n");
        for (TestResult r : results) {
            md.append("| ").append(r.testCase.no).append(" | ").append(r.testCase.category).append(" | ")
                    .append(r.agentId).append(" | ").append(r.strategy).append(" | ")
                    .append(r.status).append(" | ").append(r.costMs).append("ms | ")
                    .append(r.eval.overallScore).append(" | ").append(r.eval.grade()).append(" |\n");
        }

        md.append("\n## 清理 SQL\n\n");
        md.append("```sql\n");
        md.append("-- 测试前执行\n");
        md.append("DELETE FROM ai_chat_memory WHERE conversation_id LIKE '%10001%';\n");
        md.append("DELETE FROM ai_chat_memory_summary WHERE conversation_id LIKE '%10001%';\n");
        md.append("DELETE FROM ai_episodic_memory WHERE user_id = '10001';\n");
        md.append("DELETE FROM ai_long_term_memory WHERE user_id = '10001';\n");
        md.append("```\n");

        try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(Paths.get(path)), StandardCharsets.UTF_8)) {
            osw.write(md.toString());
        } catch (Exception e) {
            log.error("写入报告失败: {}", e.getMessage());
        }
    }

    private static Path resolveProjectRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return cwd.getFileName() != null && "ai-agent-station-study-app".equals(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
    }

    private void appendAgentEvalOverview(StringBuilder md, List<TestResult> results) {
        if (results == null || results.isEmpty()) return;
        double avg = results.stream().mapToDouble(r -> r.eval.overallScore).average().orElse(0);
        double route = results.stream().mapToDouble(r -> r.eval.routeScore).average().orElse(0);
        double answer = results.stream().mapToDouble(r -> r.eval.answerScore).average().orElse(0);
        double step = results.stream().mapToDouble(r -> r.eval.stepScore).average().orElse(0);
        double tool = results.stream().mapToDouble(r -> r.eval.toolScore).average().orElse(0);
        double grounding = results.stream().mapToDouble(r -> r.eval.groundingScore).average().orElse(0);
        double memory = results.stream().mapToDouble(r -> r.eval.memoryScore).average().orElse(0);
        long warnings = results.stream().filter(r -> !r.eval.warnings.isEmpty()).count();
        int duplicateTools = results.stream().mapToInt(r -> r.eval.duplicateToolCalls).sum();
        int earlyStepTools = results.stream().mapToInt(r -> r.eval.earlyStepToolCalls).sum();

        md.append("## Agent 评估概览\n\n");
        md.append("| 指标 | 值 |\n|---|---:|\n");
        md.append("| AgentEval 平均分 | ").append(round(avg)).append(" |\n");
        md.append("| 路由匹配均分 | ").append(round(route)).append(" |\n");
        md.append("| 最终答案均分 | ").append(round(answer)).append(" |\n");
        md.append("| Step 职责均分 | ").append(round(step)).append(" |\n");
        md.append("| 工具选择均分 | ").append(round(tool)).append(" |\n");
        md.append("| 证据/工具 grounding 均分 | ").append(round(grounding)).append(" |\n");
        md.append("| 记忆治理均分 | ").append(round(memory)).append(" |\n");
        md.append("| 有扣分/观察的题数 | ").append(warnings).append(" |\n");
        md.append("| 重复工具调用总数 | ").append(duplicateTools).append(" |\n");
        md.append("| 非执行步(分析/规划/质检/汇总)工具越权总数 | ").append(earlyStepTools).append(" |\n\n");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ==================== 辅助方法 ====================

    /**
     * 带网络抖动重试的执行方法。
     * 网络瞬断（Connection reset 等）自动重试 1 次，其他错误直接跳过。
     *
     * @param retryCount 当前重试次数（首次调用传 0）
     * @return [strategy, response, status]
     *         status: PASS=一次过, RETRY_PASS=重试后过, RETRY_FAIL=重试仍失败,
     *                 ERROR=非网络错误, TIMEOUT=超时, FAIL=路由失败
     */
    private ExecutionResult executeWithRetry(TestCase tc, String agentId, String missingToolDesc, int retryCount) throws InterruptedException {
        String strategy = "(未知)";
        String response;
        String status;

        // 预取策略名称，用于超时时调用 cancelExecute()
        String stratName = getStrategy(agentId);
        AtomicReference<IExecuteStrategy> activeStrategyRef = new AtomicReference<>();

        CompletableFuture<ExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            MDC.put("userId", USER_ID);
            MDC.put("tenantId", TENANT_ID);
            MDC.put("sessionId", SESSION_ID);
            // 2026-05-20：异步线程也补一份 traceId/qid（supplyAsync 进新线程，MDC 是空的，必须重写）
            MDC.put("traceId", "Q" + tc.no);
            MDC.put("qid", "Q" + tc.no);
                try {
                    armoryService.ensureArmed(agentId);
                    String strat = stratName;
                    TestSseEmitter emitter = new TestSseEmitter();
                    // 退休 dynamicMissingToolDesc 字段后，need 按 sessionId 预置进 store（execute 时各 step 经 needsFor 读取）
                    if (mcpToolCatalogService != null && missingToolDesc != null && !missingToolDesc.isBlank()) {
                        mcpToolCatalogService.setNeeds(SESSION_ID, missingToolDesc);
                    }
                    ExecuteCommandEntity entity = ExecuteCommandEntity.builder()
                            .aiAgentId(agentId)
                            .message(tc.question)
                        .sessionId(SESSION_ID)
                        // 2026-06-23 修跨题串扰：每题唯一 runId，让 ReasoningContentFilter 注入缓存按 runId 隔离
                        // （否则同 sessionId 下连续题共用 reasoning_content 缓存，别题思考被注入本题 → 答错题）
                        .runId(SESSION_ID + "-Q" + tc.no)
                        .userId(USER_ID)
                        .tenantId(TENANT_ID)
                        .maxStep(3)
                        .build();
                IExecuteStrategy activeStrategy;
                switch (strat) {
                    case "fixedAgentExecuteStrategy" -> { activeStrategy = fixedStrategy; }
                    case "autoAgentExecuteStrategy" -> { activeStrategy = autoStrategy; }
                    case "flowAgentExecuteStrategy" -> { activeStrategy = flowStrategy; }
                    default -> { return new ExecutionResult(strat, "(未知策略: " + strat + ")", "FAIL", emitter); }
                }
                activeStrategyRef.set(activeStrategy);
                if (approvalChannelRegistry != null) {
                    approvalChannelRegistry.register(SESSION_ID, emitter);
                }
                try {
                    activeStrategy.execute(entity, emitter);
                } finally {
                    if (approvalChannelRegistry != null) {
                        approvalChannelRegistry.unregister(SESSION_ID, emitter);
                    }
                }
                return new ExecutionResult(strat, emitter.getSummaryContent(), "PASS", emitter);
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                MDC.clear();
            }
        }, questionExecutor);

        try {
            ExecutionResult result = future.get(QUESTION_TIMEOUT_SECONDS, SECONDS);
            strategy = result.strategy;
            response = result.response;
            status = result.status;
            // 重试成功
            if (retryCount > 0) {
                status = "RETRY_PASS";
                log.info("[Q{}] 网络重试成功", tc.no);
            }
            return new ExecutionResult(strategy, response, status, result.emitter);
        } catch (TimeoutException te) {
            // 先取消 agent 执行，再取消 future
            IExecuteStrategy strat = activeStrategyRef.get();
            if (strat != null) {
                strat.cancelExecute(SESSION_ID);
            }
            future.cancel(true);
            response = "(超时: 超过 " + QUESTION_TIMEOUT_SECONDS + " 秒)";
            status = "TIMEOUT";
            log.warn("[Q{}] 执行超时 ({}s)，已调用 cancelExecute 并跳过", tc.no, QUESTION_TIMEOUT_SECONDS);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String errMsg = cause != null ? cause.getMessage() : ee.getMessage();

            // 网络瞬断 + 首次 → 等 1 分钟后重试一次
            if (retryCount == 0 && isTransientNetworkError(cause)) {
                log.warn("[Q{}] 检测到网络抖动，等待 60s 后重试... 原因: {}", tc.no, errMsg);
                try { Thread.sleep(60_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return executeWithRetry(tc, agentId, missingToolDesc, 1);
            }

            if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
            response = "(执行异常: " + errMsg + ")";
            status = retryCount > 0 ? "RETRY_FAIL" : "ERROR";
            log.error("[Q{}] 执行异常 (retryCount={}): {}", tc.no, retryCount, errMsg);
        }

        return new ExecutionResult(strategy, response, status, new TestSseEmitter());
    }

    private boolean isPassStatus(String status) {
        return "PASS".equals(status) || "RETRY_PASS".equals(status);
    }

    /** 判断是否为可重试的瞬态网络错误 */
    private boolean isTransientNetworkError(Throwable cause) {
        if (cause == null) return false;
        // 遍历 cause chain 找网络相关异常
        Throwable t = cause;
        while (t != null) {
            String cls = t.getClass().getSimpleName();
            String msg = t.getMessage();
            // EOFException: HTTP/2 连接中途断开（SSE 流读到一半 EOF）
            if (cls.equals("EOFException")) {
                return true;
            }
            if (cls.contains("SocketException") || cls.contains("IOException")
                    || cls.contains("ResourceAccessException") || cls.contains("WebClientRequestException")) {
                if (msg != null && (msg.contains("Connection reset") || msg.contains("Connection refused")
                        || msg.contains("timed out") || msg.contains("broken pipe"))) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private String getStrategy(String agentId) {
        AiAgentVO agentVO = repository.queryAiAgentByAgentId(agentId);
        if (agentVO != null && agentVO.getStrategy() != null) {
            return agentVO.getStrategy();
        }
        return "unknown";
    }


    // ==================== 100 个问题 ====================

    private List<TestCase> build100Questions() {
        List<TestCase> cases = new ArrayList<>();

        // === 第一段：初次认识用户（Q1-10）— 测试 LTM 提取 + 路由多样性 ===
        cases.add(new TestCase(1, "初次认识", "你好，我叫张伟，今年28岁，在杭州工作，是一名Java后端开发工程师"));
        cases.add(new TestCase(2, "初次认识", "我最近想学Python，但不知道从哪里开始，能给我一些建议吗？"));
        cases.add(new TestCase(3, "初次认识", "我每天早上7点起床，晚上11点睡觉，帮我规划一下一天的时间安排"));
        cases.add(new TestCase(4, "初次认识", "我想减脂，身高175cm，体重80kg，给我制定一个健身计划"));
        cases.add(new TestCase(5, "初次认识", "今天中午想做一道简单的家常菜，有什么推荐？"));
        cases.add(new TestCase(6, "初次认识", "我最近在读《人类简史》，已经读了一半了，帮我做个读书笔记框架"));
        cases.add(new TestCase(7, "初次认识", "我每个月工资15000，房租3500，帮我做个预算规划"));
        cases.add(new TestCase(8, "初次认识", "帮我写一段自我介绍，用于技术社区的个人主页"));
        cases.add(new TestCase(9, "初次认识", "我计划五一去成都玩3天，帮我规划一下行程"));
        cases.add(new TestCase(10, "初次认识", "你好，还记得我叫什么名字吗？我在哪个城市工作？"));

        // === 第二段：深入各领域（Q11-20）— 测试 RAG 检索 + ChatMemory 连续性 ===
        cases.add(new TestCase(11, "深入领域", "你之前推荐的Python学习路线，我觉得不错，能详细说说第一阶段该学什么吗？"));
        cases.add(new TestCase(12, "深入领域", "我想了解一下深蹲的正确姿势，有什么要注意的？"));
        cases.add(new TestCase(13, "深入领域", "帮我查一下番茄炒蛋的详细做法，我要第一次尝试"));
        cases.add(new TestCase(14, "深入领域", "我想养成每天阅读的习惯，但总是坚持不下来，怎么办？"));
        cases.add(new TestCase(15, "深入领域", "我想开始理财，但完全没有基础，先从什么开始？"));
        cases.add(new TestCase(16, "深入领域", "帮我规划一下这周的学习计划，重点是Python基础语法"));
        cases.add(new TestCase(17, "深入领域", "我想写一篇关于Spring Boot的技术博客，帮我列个大纲"));
        cases.add(new TestCase(18, "深入领域", "我最近睡眠质量很差，有什么改善方法？"));
        cases.add(new TestCase(19, "深入领域", "你能帮我分析一下定期定额投资基金的优势吗？"));
        cases.add(new TestCase(20, "深入领域", "我想学做红烧肉，需要准备哪些食材和调料？"));

        // === 第三段：跨话题切换（Q21-30）— 测试记忆跨话题连续性 ===
        cases.add(new TestCase(21, "跨话题", "对了，我之前说的减脂计划，执行了一周感觉怎么样调整？"));
        cases.add(new TestCase(22, "跨话题", "帮我推荐几本适合程序员读的书"));
        cases.add(new TestCase(23, "跨话题", "我想给女朋友做一个生日蛋糕，新手能做吗？"));
        cases.add(new TestCase(24, "跨话题", "帮我写一首关于程序员生活的现代诗"));
        cases.add(new TestCase(25, "跨话题", "我最近肩膀疼，是不是和久坐有关系？怎么缓解？"));
        cases.add(new TestCase(26, "跨话题", "我想学英语口语，有什么好的方法推荐？"));
        cases.add(new TestCase(27, "跨话题", "帮我规划一个周末的杭州周边自驾游路线"));
        cases.add(new TestCase(28, "跨话题", "我想了解一下基金定投和一次性投入哪个更好？"));
        cases.add(new TestCase(29, "跨话题", "帮我写一篇关于微服务架构的技术博客"));
        cases.add(new TestCase(30, "跨话题", "你还记得我是做什么工作的吗？我在哪个城市？"));

        // === 第四段：复杂任务（Q31-40）— 测试 Auto/Flow 策略 ===
        cases.add(new TestCase(31, "复杂任务", "帮我分析一下Java并发编程中volatile关键字的作用，用通俗易懂的方式解释"));
        cases.add(new TestCase(32, "复杂任务", "我想做一个个人博客网站，帮我从技术选型到部署的完整方案"));
        cases.add(new TestCase(33, "复杂任务", "我最近在看《思考，快与慢》，帮我总结一下系统1和系统2的核心观点"));
        cases.add(new TestCase(34, "复杂任务", "帮我设计一个一周的减脂餐食谱，要简单易做"));
        cases.add(new TestCase(35, "复杂任务", "我想学做PPT，有什么快速入门的方法？"));
        cases.add(new TestCase(36, "复杂任务", "帮我写一个Python脚本，实现从Excel读取数据并生成图表"));
        cases.add(new TestCase(37, "复杂任务", "我想了解一下Docker和Kubernetes的区别和联系"));
        cases.add(new TestCase(38, "复杂任务", "帮我规划一下从初级Java工程师到高级工程师的学习路径"));
        cases.add(new TestCase(39, "复杂任务", "我想给家里的老人制定一个简单的健身计划，有什么建议？"));
        cases.add(new TestCase(40, "复杂任务", "帮我写一封请假邮件，因为家里有事需要请三天假"));

        // === 第五段：RAG 知识库深度测试（Q41-50）— 针对各 agent 知识领域 ===
        cases.add(new TestCase(41, "RAG知识库", "什么是SMART目标法则？能举几个例子吗？"));
        cases.add(new TestCase(42, "RAG知识库", "麻婆豆腐怎么做才正宗？"));
        cases.add(new TestCase(43, "RAG知识库", "什么是费曼学习法？怎么应用到实际学习中？"));
        cases.add(new TestCase(44, "RAG知识库", "游泳的四种泳姿各有什么特点？新手应该先学哪种？"));
        cases.add(new TestCase(45, "RAG知识库", "艾森豪威尔矩阵是什么？怎么用在日常工作中？"));
        cases.add(new TestCase(46, "RAG知识库", "什么是复利效应？能举个具体的数字例子吗？"));
        cases.add(new TestCase(47, "RAG知识库", "如何用Anki进行间隔重复学习？"));
        cases.add(new TestCase(48, "RAG知识库", "写故事的时候怎么塑造一个有魅力的反派角色？"));
        cases.add(new TestCase(49, "RAG知识库", "去日本旅行有哪些必吃的美食？"));
        cases.add(new TestCase(50, "RAG知识库", "非暴力沟通的四个步骤是什么？能举个例子吗？"));

        // === 第六段：记忆累积验证（Q51-60）— 测试 LTM 和 Episodic 是否正确累积 ===
        cases.add(new TestCase(51, "记忆累积", "你觉得我适合学Python还是Go？考虑到我是Java后端开发"));
        cases.add(new TestCase(52, "记忆累积", "我之前问的减脂计划，现在体重降到78kg了，下一步怎么调整？"));
        cases.add(new TestCase(53, "记忆累积", "帮我推荐几道适合减脂期吃的菜谱"));
        cases.add(new TestCase(54, "记忆累积", "我想在技术社区建立个人影响力，有什么建议？"));
        cases.add(new TestCase(55, "记忆累积", "我最近在考虑跳槽，作为Java开发，杭州有哪些好的公司？"));
        cases.add(new TestCase(56, "记忆累积", "帮我写一篇关于Redis缓存策略的技术博客"));
        cases.add(new TestCase(57, "记忆累积", "我想学吉他，零基础应该怎么开始？"));
        cases.add(new TestCase(58, "记忆累积", "帮我规划一下明年的职业发展目标"));
        cases.add(new TestCase(59, "记忆累积", "我女朋友生日快到了，有什么浪漫的求婚创意？"));
        cases.add(new TestCase(60, "记忆累积", "你还记得我之前说想学Python吗？现在我已经学了两周了，接下来学什么？"));

        // === 第七段：MCP 工具调用测试（Q61-70）— 测试工具链 ===
        cases.add(new TestCase(61, "MCP工具", "帮我在CSDN上搜索一下Spring Boot的最佳实践文章"));
        cases.add(new TestCase(62, "MCP工具", "帮我查一下今天杭州的天气怎么样"));
        cases.add(new TestCase(63, "MCP工具", "我想搜索一下2024年最流行的编程语言排名"));
        cases.add(new TestCase(64, "MCP工具", "帮我在网上找一些关于DDD领域驱动设计的入门教程"));
        cases.add(new TestCase(65, "MCP工具", "我想了解一下最近AI领域有什么新的突破"));
        cases.add(new TestCase(66, "MCP工具", "帮我查一下从杭州到成都的高铁时刻表"));
        cases.add(new TestCase(67, "MCP工具", "搜索一下有没有关于Java虚拟机调优的实战案例"));
        cases.add(new TestCase(68, "MCP工具", "帮我找一些关于远程工作最佳实践的文章"));
        cases.add(new TestCase(69, "MCP工具", "我想了解一下最新的前端框架对比（React vs Vue vs Svelte）"));
        cases.add(new TestCase(70, "MCP工具", "帮我搜索一些关于程序员职业发展的播客推荐"));

        // === 第八段：多轮对话深度测试（Q71-80）— 测试 ChatMemory Summary ===
        cases.add(new TestCase(71, "多轮深度", "综合考虑我的情况（Java后端、杭州、想学Python），给我一个完整的学习规划"));
        cases.add(new TestCase(72, "多轮深度", "帮我回顾一下我们之前的对话，我提到了哪些想学的技能？"));
        cases.add(new TestCase(73, "多轮深度", "基于我之前说的预算情况，你觉得我应该投入多少钱在学习上？"));
        cases.add(new TestCase(74, "多轮深度", "我想写一篇关于我们之前讨论的微服务架构的技术博客，帮我完善大纲"));
        cases.add(new TestCase(75, "多轮深度", "帮我总结一下我目前的所有健身目标和进展"));
        cases.add(new TestCase(76, "多轮深度", "我想做一个个人知识管理系统，你有什么建议？"));
        cases.add(new TestCase(77, "多轮深度", "帮我分析一下我目前的时间分配是否合理"));
        cases.add(new TestCase(78, "多轮深度", "我想给我的技术博客写一个系列文章，主题是从Java到云原生，帮我规划系列大纲"));
        cases.add(new TestCase(79, "多轮深度", "帮我制定一个下个月的OKR目标"));
        cases.add(new TestCase(80, "多轮深度", "你觉得我作为一个程序员，最需要提升的能力是什么？"));

        // === 第九段：边界和异常测试（Q81-90）===
        cases.add(new TestCase(81, "边界测试", "帮我翻译这段英文：The quick brown fox jumps over the lazy dog"));
        cases.add(new TestCase(82, "边界测试", "帮我检查这段SQL有没有性能问题：SELECT * FROM users WHERE name LIKE '%张%'"));
        cases.add(new TestCase(83, "边界测试", "我想了解一下量子计算的基本原理"));
        cases.add(new TestCase(84, "边界测试", "帮我算一下，如果我每月定投3000元到年化8%的基金，10年后有多少钱？"));
        cases.add(new TestCase(85, "边界测试", "帮我写一个简单的贪吃蛇游戏的Python代码"));
        cases.add(new TestCase(86, "边界测试", "我最近心情不太好，工作压力很大，有什么缓解方法？"));
        cases.add(new TestCase(87, "边界测试", "帮我推荐一些适合周末在家看的电影"));
        cases.add(new TestCase(88, "边界测试", "我想了解一下区块链技术的基本原理"));
        cases.add(new TestCase(89, "边界测试", "帮我设计一个简单的个人记账表格模板"));
        cases.add(new TestCase(90, "边界测试", "我想给我的代码加上日志监控，有什么好的方案？"));

        // === 第十段：总结和回顾（Q91-100）— 测试全部记忆模块的综合表现 ===
        cases.add(new TestCase(91, "总结回顾", "帮我回顾一下，从我们第一次对话到现在，我都有哪些变化和成长？"));
        cases.add(new TestCase(92, "总结回顾", "基于我所有的学习目标，你觉得我最应该优先完成的是什么？"));
        cases.add(new TestCase(93, "总结回顾", "帮我制定一个3个月的综合提升计划，涵盖技术、健身、阅读"));
        cases.add(new TestCase(94, "总结回顾", "我想把我们讨论过的所有菜谱整理成一个清单"));
        cases.add(new TestCase(95, "总结回顾", "帮我总结一下我目前的所有理财目标和进展"));
        cases.add(new TestCase(96, "总结回顾", "你觉得我适合什么样的副业？考虑到我的技能和兴趣"));
        cases.add(new TestCase(97, "总结回顾", "帮我写一篇个人年终总结的框架"));
        cases.add(new TestCase(98, "总结回顾", "我想了解一下如何建立一个有效的知识管理体系"));
        cases.add(new TestCase(99, "总结回顾", "帮我回顾一下我问过的所有关于技术的问题，做个分类总结"));
        cases.add(new TestCase(100, "总结回顾", "谢谢你这段时间的帮助，你觉得我还需要注意什么？"));

        return cases;
    }
}
