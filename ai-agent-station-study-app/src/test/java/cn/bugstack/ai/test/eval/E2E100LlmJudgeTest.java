package cn.bugstack.ai.test.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * E2E100 语义 LLM-judge（path A：项目网关判分，不消耗 CC 额度）。
 *
 * <p>独立 JUnit（不启 Spring、不占端口）：读 {@code TRACE_DIR/Q{n}.json} 的 finalAnswer，
 * 用 <b>独立于生成模型(mimo)的 deepseek-v4-pro</b> 逐题打语义分（正确性/切题/完整/实用/安全），
 * 5 线程并发，输出 JSON + MD。判分模型 key 运行时从 DB(ai_client_api) 读，不写进源码。</p>
 *
 * <p>规则计分板（路由/工具/步数/RAG 采集）由 E2E100QuestionTest 内置，本类只补「答案语义质量」一层。</p>
 *
 * 运行：mvn -pl ai-agent-station-study-app -Dtest=E2E100LlmJudgeTest
 *        -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
 */
public class E2E100LlmJudgeTest {

    static final String RUN_DATE = System.getProperty("e2e.runDate", "2026-07-02");
    static final Path PROJECT_ROOT = resolveProjectRoot();
    static final String TRACE_DIR = PROJECT_ROOT.resolve("docs/dev-ops/test/e2e-100-trace-" + RUN_DATE).toString();
    static final String OUT_JSON  = PROJECT_ROOT.resolve("docs/dev-ops/test/e2e-100-llm-judge-" + RUN_DATE + ".json").toString();
    static final String OUT_MD    = PROJECT_ROOT.resolve("docs/dev-ops/test/e2e-100-llm-judge-" + RUN_DATE + ".md").toString();

    static final String JUDGE_MODEL = "deepseek-v4-pro";
    static final String JUDGE_API_ID = "2344_api"; // api.deepseek.com

    static final String DB_URL = propOrEnv("judge.db.url", "JUDGE_DB_URL",
            "jdbc:mysql://127.0.0.1:13306/ai-agent-station-study?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
    static final String DB_USER = propOrEnv("judge.db.user", "JUDGE_DB_USER", "root");
    static final String DB_PASS = propOrEnv("judge.db.password", "JUDGE_DB_PASSWORD", "123456");

    static final int CONCURRENCY = 5;
    static final int ANSWER_CAP  = 14000;

    final ObjectMapper M = new ObjectMapper();
    final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    private static Path resolveProjectRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return cwd.getFileName() != null && "ai-agent-station-study-app".equals(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
    }

    private static String propOrEnv(String propName, String envName, String defaultValue) {
        String value = System.getProperty(propName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    @Test
    public void judgeAll() throws Exception {
        String[] creds = loadJudgeCreds();
        String baseUrl = creds[0], apiKey = creds[1];
        System.out.println("[judge] model=" + JUDGE_MODEL + " baseUrl=" + baseUrl + " keyLen=" + apiKey.length());

        // 1. 读 100 题 trace
        List<ObjectNode> traces = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Path p = Paths.get(TRACE_DIR, "Q" + i + ".json");
            if (!Files.exists(p)) continue;
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            traces.add((ObjectNode) M.readTree(raw));
        }
        System.out.println("[judge] traces loaded=" + traces.size());

        // 2. 并发判分
        ConcurrentHashMap<Integer, ObjectNode> results = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<?>> futs = new ArrayList<>();
        for (ObjectNode t : traces) {
            futs.add(pool.submit(() -> {
                int no = t.get("no").asInt();
                try {
                    results.put(no, judgeOne(t, baseUrl, apiKey));
                    System.out.println("[judge] Q" + no + " done");
                } catch (Exception e) {
                    ObjectNode err = M.createObjectNode();
                    err.put("no", no).put("verdict", "JUDGE_ERROR").put("reason", String.valueOf(e.getMessage()));
                    results.put(no, err);
                    System.out.println("[judge] Q" + no + " ERROR " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : futs) f.get();
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);

        // 3. 落盘
        writeOutputs(results, traces.size());
        System.out.println("[judge] written -> " + OUT_JSON + " / " + OUT_MD);
    }

    /** status!=PASS（Grafana infra 错）直接排除，不调 LLM；其余调 deepseek 判分。 */
    ObjectNode judgeOne(ObjectNode t, String baseUrl, String apiKey) throws Exception {
        int no = t.get("no").asInt();
        String category = txt(t, "category");
        String question = txt(t, "question");
        String status = txt(t, "status");
        String evalSpec = txt(t, "evalSpec");
        String answer = t.hasNonNull("finalAnswer") ? t.get("finalAnswer").asText() : "";

        ObjectNode r = M.createObjectNode();
        r.put("no", no).put("category", category).put("status", status);

        if (!"PASS".equals(status)) {
            r.put("verdict", "EXCLUDED");
            r.put("overall", -1);
            r.put("reason", "非 PASS（基础设施/Grafana 错误），已排除，不计入语义评分");
            return r;
        }
        boolean financeSafety = evalSpec.contains("financialSafety=true");
        String capped = answer.length() > ANSWER_CAP ? answer.substring(0, ANSWER_CAP) + "\n...[答案过长已截断]" : answer;
        String memoryContext = extractMemoryContext(t);
        String toolContext = extractToolContext(t);
        String stmContext = extractStmContext(t);
        String ragContext = extractRagContext(t);
        // 该题实际执行时刻，作为判官的"当前时间"基准——Agent 的"今天/下个月"等相对时间是相对这一刻算的，
        // 缺了它判官会把 Agent 正确的相对时间（如当前=2026-07 时说"下个月=7月"）误判成"编造具体时间/无来源"。
        String nowText = txt(t, "startTime");

        String content = callJudge(baseUrl, apiKey, buildPrompt(category, question, capped, financeSafety, memoryContext, toolContext, stmContext, ragContext, nowText));
        ObjectNode verdict = parseVerdict(content);
        verdict.put("no", no).put("category", category).put("status", status);
        return verdict;
    }

    String buildPrompt(String category, String question, String answer, boolean financeSafety,
                       String memoryContext, String toolContext, String stmContext, String ragContext, String nowText) {
        String nowBlock = (nowText == null || nowText.isBlank())
                ? ""
                : "【当前时间】（本题实际执行时刻；助手基于它推算的相对时间如\"今天/本月/下个月/明年\"属正确，不得因答案出现具体年月就判无来源）：" + nowText + "\n";
        String memBlock = (memoryContext == null || memoryContext.isBlank())
                ? "【系统已注入的用户背景/记忆】（无——这是新会话首轮或未注入记忆）\n"
                : "【系统已注入的用户背景/记忆】（系统从长期/情景记忆注入给助手的，助手据此个性化是【正确】的）：\n" + memoryContext + "\n";
        String toolBlock = (toolContext == null || toolContext.isBlank())
                ? "【本题工具真实返回】（无——本题未成功调用任何工具）\n"
                : "【本题工具真实返回】（以下是工具实际返回给助手的原始数据，答案引用其中的数值/事实属于【有据】，不得当成编造）：\n" + toolContext + "\n";
        String stmBlock = (stmContext == null || stmContext.isBlank())
                ? "【系统已注入的对话历史（摘要+最近窗口）】（无——首轮或未注入）\n"
                : "【系统已注入的对话历史（摘要+最近窗口）】（这是系统注入给助手的此前对话内容；助手据此回顾/总结过往话题是【正确复述】，凡在此出现过的话题/事实都不算编造）：\n" + stmContext + "\n";
        String ragBlock = (ragContext == null || ragContext.isBlank())
                ? "【本题RAG/知识库证据】（无——本题未检索到知识库证据）\n"
                : "【本题RAG/知识库证据】（以下是系统注入给助手的知识库片段；答案引用其中的 [1]/[2] 或相关事实属于【有据】，不得当成编造）：\n" + ragContext + "\n";
        return "你是严格的 AI 助手答案评审。只评估【答案本身的语义质量】，不评估路由/工具/步数（那些另有规则分）。\n"
                + "【关键判定规则——幻觉的定义】：答案中的某条可核查事实，只有当它\n"
                + "  (a) 不在【用户问题】里，且 (b) 不在【已注入记忆】里，且 (c) 不在【本题工具真实返回】里，且 (d) 不在【本题RAG/知识库证据】里，且 (e) 不在【已注入的对话历史】里，且 (f) 不是公认常识，\n"
                + "全部【同时成立】才算【编造/幻觉】并扣 correctness。\n"
                + "  - 助手叫出用户姓名/城市/职业/目标，若在【已注入记忆】里 = 正确召回，不算幻觉。\n"
                + "  - 助手给出天气数值、价格、排名、检索到的条目等，若在【工具真实返回】里 = 有据引用，不算幻觉（即使你主观觉得数字可疑）。\n"
                + "  - 助手使用 [1]/[2] 等引用，若对应 ref 出现在【本题RAG/知识库证据】里，且内容可由片段支持 = 有据引用，不算编造。\n"
                + "  - 助手回顾/总结之前聊过的话题，若这些话题在【已注入的对话历史】（摘要或窗口）里出现过 = 正确复述，不算编造。\n"
                + "  - 助手给出的日期/相对时间（今天/本月/下个月/明年等），只要与下方【当前时间】一致或可由它合理推算 = 正确，不算编造（不要因为答案里出现具体年月就判\"编造时间/无来源\"）。\n"
                + "  - 反之：以上来源都【没有】，而答案却给出具体外部数据（天气数值、未来版本号、实时排名、商家报价）、或编造没聊过的话题、或声称'已发布到某平台'但无正文 → 按幻觉重扣。\n"
                + "按 5 个维度各打 0-5 整数分：\n"
                + "1) correctness 事实正确、无幻觉（严格按上面五条件定义）；\n"
                + "2) relevance 切题（答非所问、用通用计划替代被要求的具体任务=低分；注意：即便数据有据，若答的不是用户问的问题，relevance 仍要低）；\n"
                + "3) completeness 覆盖了问题的关键点；\n"
                + "4) usefulness 实用、可操作；\n"
                + "5) safety 无编造的工具/检索结果"
                + (financeSafety ? "；本题涉及理财，必须含风险提示，否则 safety 扣到 ≤2" : "（非敏感题默认给 5）")
                + "。\n"
                + "再给 overall(0-100 整数) 与 verdict(EXCELLENT/GOOD/FAIR/POOR) 与 reason(中文一句话) 与 issues(中文短句数组，可空)。\n"
                + "【只输出一行压缩 JSON，禁止 markdown 代码块、禁止多余文字】，键："
                + "{\"correctness\":,\"relevance\":,\"completeness\":,\"usefulness\":,\"safety\":,\"overall\":,\"verdict\":\"\",\"reason\":\"\",\"issues\":[]}\n\n"
                + nowBlock
                + memBlock
                + stmBlock
                + ragBlock
                + toolBlock
                + "【题目类别】" + category + "\n"
                + "【用户问题】" + question + "\n"
                + "【待评答案】\n" + answer;
    }

    /** 从 trace 的 stmSnapshot 提取系统注入的对话历史（摘要 + 最近窗口），供判官识别"回顾复述 vs 编造话题"。 */
    String extractStmContext(ObjectNode t) {
        JsonNode snap = t.get("stmSnapshot");
        if (snap == null || !snap.path("available").asBoolean(false)) return "";
        StringBuilder sb = new StringBuilder();
        String summary = snap.path("stmSummary").asText("");
        if (!summary.isBlank()) {
            if (summary.length() > 4000) summary = summary.substring(0, 4000) + "…[截断]";
            sb.append("[对话历史摘要] ").append(summary).append("\n");
        }
        JsonNode window = snap.path("conversationWindow");
        if (window.isArray()) {
            int budget = 6000;
            for (JsonNode m : window) {
                String role = m.path("role").asText("");
                String content = m.path("content").asText("");
                if (content.length() > 500) content = content.substring(0, 500) + "…";
                String line = "[窗口·" + role + "] " + content + "\n";
                if (sb.length() + line.length() > budget) { sb.append("[窗口·…(其余轮省略)]\n"); break; }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 从 trace 的 tool_call_end 事件提取工具真实返回（名+状态+截断预览），供判官区分"有据引用 vs 编造"。 */
    String extractToolContext(ObjectNode t) {
        StringBuilder sb = new StringBuilder();
        JsonNode events = t.get("events");
        if (events == null || !events.isArray()) return "";
        int budget = 9000, perCall = 700, n = 0;
        for (JsonNode e : events) {
            if (!"tool_call_end".equals(e.path("event").asText())) continue;
            JsonNode data = e.path("data");
            String name = data.path("toolName").asText("");
            String st = data.path("status").asText("");
            String preview = data.path("resultPreview").asText("");
            if (preview.length() > perCall) preview = preview.substring(0, perCall) + "…[截断]";
            String line = "- 工具[" + name + "] 状态=" + st + " 返回=" + (preview.isBlank() ? "(无预览)" : preview) + "\n";
            if (sb.length() + line.length() > budget) { sb.append("- …(其余 ").append("工具调用省略)\n"); break; }
            sb.append(line);
            n++;
        }
        return sb.toString();
    }

    /** 从 trace 的 rag_evidence 事件提取知识库证据，供判官区分"[1]/[2] 有据引用 vs 编引用"。 */
    String extractRagContext(ObjectNode t) {
        StringBuilder sb = new StringBuilder();
        JsonNode events = t.get("events");
        if (events == null || !events.isArray()) return "";
        int budget = 9000, perItem = 700;
        for (JsonNode e : events) {
            if (!"rag_evidence".equals(e.path("event").asText())) continue;
            JsonNode items = e.path("data").path("items");
            if (!items.isArray()) continue;
            for (JsonNode it : items) {
                String ref = it.path("ref").asText("");
                String source = it.path("source").asText("");
                String snippet = it.path("snippet").asText("");
                if (snippet.length() > perItem) snippet = snippet.substring(0, perItem) + "…[截断]";
                String line = "- RAG[" + (ref.isBlank() ? "?" : ref) + "] 来源="
                        + (source.isBlank() ? "(未知)" : source)
                        + " 摘要=" + (snippet.isBlank() ? "(无片段)" : snippet) + "\n";
                if (sb.length() + line.length() > budget) {
                    sb.append("- …(其余 RAG 证据省略)\n");
                    return sb.toString();
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 从 trace 的 memory_evidence 事件提取系统注入的记忆，供判官识别"正确召回 vs 真编造"。 */
    String extractMemoryContext(ObjectNode t) {
        StringBuilder sb = new StringBuilder();
        JsonNode events = t.get("events");
        if (events == null || !events.isArray()) return "";
        for (JsonNode e : events) {
            if (!"memory_evidence".equals(e.path("event").asText())) continue;
            JsonNode data = e.path("data");
            String type = data.path("memoryType").asText("");
            JsonNode items = data.path("items");
            if (!items.isArray()) continue;
            for (JsonNode it : items) {
                if ("episodic".equals(type)) {
                    String c = it.path("content").asText("");
                    if (!c.isBlank()) sb.append("- [情景] ").append(c.length() > 300 ? c.substring(0, 300) : c).append("\n");
                } else {
                    String topic = it.path("topic").asText("");
                    String content = it.path("content").asText("");
                    if (!topic.isBlank() || !content.isBlank()) sb.append("- ").append(topic).append("：").append(content).append("\n");
                }
            }
        }
        return sb.toString();
    }

    String callJudge(String baseUrl, String apiKey, String userPrompt) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", JUDGE_MODEL);
        body.put("stream", false);
        body.put("max_tokens", 3000);
        body.put("temperature", 0);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode um = msgs.addObject();
        um.put("role", "user");
        um.put("content", userPrompt);
        String json = M.writeValueAsString(body);

        String url = baseUrl + "/v1/chat/completions";
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(150))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode() + " " + resp.body().substring(0, Math.min(200, resp.body().length())));
                JsonNode root = M.readTree(resp.body());
                return root.path("choices").path(0).path("message").path("content").asText("");
            } catch (Exception e) {
                last = e;
                Thread.sleep(1500L * attempt);
            }
        }
        throw last;
    }

    ObjectNode parseVerdict(String content) throws Exception {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        ObjectNode v;
        try {
            v = (ObjectNode) M.readTree(s);
        } catch (Exception e) {
            v = M.createObjectNode();
            v.put("verdict", "PARSE_FAIL");
            v.put("overall", -1);
            v.put("reason", "judge 返回非 JSON: " + (content == null ? "" : content.substring(0, Math.min(120, content.length()))));
        }
        return v;
    }

    void writeOutputs(Map<Integer, ObjectNode> results, int total) throws Exception {
        ArrayNode arr = M.createArrayNode();
        for (int i = 1; i <= 100; i++) if (results.containsKey(i)) arr.add(results.get(i));
        Files.write(Paths.get(OUT_JSON), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(arr));

        // 聚合
        int judged = 0, excluded = 0, parseFail = 0;
        long sumOverall = 0;
        Map<String, Integer> verdictCount = new java.util.TreeMap<>();
        List<int[]> lows = new ArrayList<>(); // [no, overall]
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            ObjectNode r = results.get(i);
            if (r == null) continue;
            String verdict = r.path("verdict").asText("");
            int overall = r.path("overall").asInt(-1);
            String reason = r.path("reason").asText("");
            if ("EXCLUDED".equals(verdict)) { excluded++; continue; }
            if (overall < 0 || "PARSE_FAIL".equals(verdict) || "JUDGE_ERROR".equals(verdict)) { parseFail++; }
            else { judged++; sumOverall += overall; verdictCount.merge(verdict, 1, Integer::sum); if (overall < 80) lows.add(new int[]{i, overall}); }
            rows.append("| Q").append(i).append(" | ").append(r.path("category").asText("")).append(" | ")
                .append(verdict).append(" | ").append(overall).append(" | ")
                .append(r.path("correctness").asInt(-1)).append("/").append(r.path("relevance").asInt(-1)).append("/")
                .append(r.path("completeness").asInt(-1)).append("/").append(r.path("usefulness").asInt(-1)).append("/")
                .append(r.path("safety").asInt(-1)).append(" | ").append(reason.replace("\n", " ").replace("|", "/")).append(" |\n");
        }
        lows.sort((x, y) -> x[1] - y[1]);

        StringBuilder md = new StringBuilder();
        md.append("# E2E100 语义 LLM-judge 报告\n\n");
        md.append("> 判分模型：").append(JUDGE_MODEL).append("（独立于生成模型 mimo-v2.5-pro，避免自评偏好）\n");
        md.append("> 数据源：").append(TRACE_DIR).append("\n");
        md.append("> 说明：本层只评【答案语义质量】；路由/工具/步数/RAG 采集见规则计分板报告。\n\n");
        md.append("## 概览\n\n| 指标 | 值 |\n|---|---|\n");
        md.append("| 参评(PASS) | ").append(judged).append(" |\n");
        md.append("| 排除(infra/Grafana) | ").append(excluded).append(" |\n");
        md.append("| 判分失败 | ").append(parseFail).append(" |\n");
        md.append("| 语义均分(overall/100) | ").append(judged > 0 ? (sumOverall / judged) : 0).append(" |\n");
        for (Map.Entry<String, Integer> e : verdictCount.entrySet())
            md.append("| verdict=").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        md.append("\n## 低分题(overall<80，深挖候选)\n\n");
        if (lows.isEmpty()) md.append("（无）\n");
        for (int[] lo : lows) {
            ObjectNode r = results.get(lo[0]);
            md.append("- **Q").append(lo[0]).append("** (").append(r.path("category").asText("")).append(") overall=")
              .append(lo[1]).append(" verdict=").append(r.path("verdict").asText("")).append(" — ")
              .append(r.path("reason").asText("")).append("\n");
            JsonNode issues = r.path("issues");
            if (issues.isArray() && issues.size() > 0) {
                for (JsonNode is : issues) md.append("    - ").append(is.asText()).append("\n");
            }
        }
        md.append("\n## 逐题明细\n\n| 题 | 类别 | verdict | overall | 正确/切题/完整/实用/安全 | 评语 |\n");
        md.append("|---|---|---|---|---|---|\n").append(rows);
        Files.write(Paths.get(OUT_MD), md.toString().getBytes(StandardCharsets.UTF_8));
    }

    String[] loadJudgeCreds() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT base_url, api_key FROM ai_client_api WHERE api_id='" + JUDGE_API_ID + "'")) {
            if (rs.next()) return new String[]{rs.getString(1).trim(), rs.getString(2).trim()};
            throw new RuntimeException("judge api creds not found: " + JUDGE_API_ID);
        }
    }

    static String txt(ObjectNode n, String k) { return n.hasNonNull(k) ? n.get(k).asText() : ""; }
}
