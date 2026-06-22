package cn.bugstack.ai.test.contract;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P3-1 <b>prompt 迁移门</b>（Codex §89.4 要求；v3.md §89.7）。
 *
 * <p>区别于 {@link PromptContractBaselineTest}（那是 parser baseline，喂合成样本给 parser、不读 DB/方案文本）：
 * <b>本测试读 P3-2 候选 prompt 文本</b>（staging SQL `V050__…` / `V051__…`），直接断言
 * <b>"重写后的 DB prompt 没删契约、确实去了硬绑"</b>。改候选 prompt 删了契约 token → 本测试红。
 *
 * <p>fixture = 正式迁移 {@code docs/dev-ops/sql-migrations/V050__…/V051__…}（2026-06-22 已 APPLIED；
 * test 与真实 SQL 同源、不分叉 —— Codex §91.3）。断言只针对 UPDATE 语句体
 * （**先剥掉 {@code --} 注释行**，避免注释里出现的工具名/说明干扰判定）。
 *
 * <p>纯文件读取，零 Spring/DB/LLM。
 */
public class PromptTextContractBaselineTest {

    /** 从 user.dir 向上找仓库根，定位正式迁移 SQL；剥掉 -- 注释行后返回正文。 */
    private static String readCandidateBody(String fileName) {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/dev-ops/sql-migrations").resolve(fileName);
            if (Files.exists(candidate)) {
                try {
                    String raw = Files.readString(candidate, StandardCharsets.UTF_8);
                    return Arrays.stream(raw.split("\n", -1))
                            .filter(line -> !line.stripLeading().startsWith("--"))
                            .collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    throw new IllegalStateException("读取候选 SQL 失败: " + candidate, e);
                }
            }
        }
        fail("找不到迁移 SQL: " + fileName + "（从 user.dir=" + System.getProperty("user.dir") + " 向上未找到 docs/dev-ops/sql-migrations）");
        return "";
    }

    private static void assertAbsent(String body, String label, String... needles) {
        for (String n : needles) {
            assertFalse(label + " 残留硬绑工具名: " + n, body.contains(n));
        }
    }

    // ============================ V050：Fixed（去硬绑，无契约 token）============================

    @Test
    public void v050_fixed_allSixPromptsPresent() {
        String body = readCandidateBody("V050__rewrite_fixed_system_prompts.sql");
        for (String id : new String[]{"8001_p1", "8002_p1", "8003_p1", "8004_p1", "8005_p1", "8014_p1"}) {
            assertTrue("V050 缺 prompt_id: " + id, body.contains("'" + id + "'"));
        }
    }

    @Test
    public void v050_fixed_allToolNamesDeHardcoded() {
        String body = readCandidateBody("V050__rewrite_fixed_system_prompts.sql");
        assertAbsent(body, "Fixed",
                "weather-mcp", "calculator-mcp", "g-search", "recipe-mcp", "nutrition-mcp",
                "book-mcp", "arxiv-mcp", "fitness-mcp", "translator-mcp", "writing-mcp",
                "CSDN 发帖工具", "微信公众号通知工具");
    }

    @Test
    public void v050_fixed_noContractTokenAccidentallyIntroduced() {
        // Fixed 本就无契约 token；确保重写没误引入机器字段/裁决枚举
        String body = readCandidateBody("V050__rewrite_fixed_system_prompts.sql");
        assertAbsent(body, "Fixed 误引入契约", "AUTO_QUALITY_VERDICT", "AUTO_COMPLETION", "DEPENDS_ON");
    }

    // ============================ V051：Auto/Flow（保契约 + 去硬绑执行步）============================

    @Test
    public void v051_step3VerdictEnumPreserved() {
        // 8012_p3 必须保留 PASS/FAIL/OPTIMIZE（Step3 legacy 白名单/裸枚举行解析；enforce 机器字段另由代码注入）
        String body = readCandidateBody("V051__rewrite_auto_flow_exec_prompts.sql");
        assertTrue("8012_p3 丢了 PASS", body.contains("PASS"));
        assertTrue("8012_p3 丢了 FAIL", body.contains("FAIL"));
        assertTrue("8012_p3 丢了 OPTIMIZE", body.contains("OPTIMIZE"));
    }

    @Test
    public void v051_execStepsDeHardcoded() {
        String body = readCandidateBody("V051__rewrite_auto_flow_exec_prompts.sql");
        assertAbsent(body, "Auto/Flow 执行步",
                "calendar-mcp", "todo-mcp", "finance-mcp", "calculator-mcp",
                "elasticsearch", "grafana:", "image-gen-mcp", "writing-mcp",
                "recipe-mcp", "nutrition-mcp");
    }

    @Test
    public void v051_imageHardRulesPreserved() {
        // 8009_p3 的「Image MCP hard rules」是工具调用顺序/失败重试的流程性约束，非工具清单 → 保留
        String body = readCandidateBody("V051__rewrite_auto_flow_exec_prompts.sql");
        assertTrue("8009_p3 丢了 Image MCP hard rules", body.contains("Image MCP hard rules"));
        assertTrue("hard rules 的 list_image_models 流程没保留", body.contains("list_image_models"));
        assertTrue("hard rules 的 generate_image 流程没保留", body.contains("generate_image"));
    }

    @Test
    public void v051_p4OverbanUnchanged_conservative() {
        // 保守批：p4「禁止调用任何工具」overban 维持现状（§5 候选项，未擅改）
        String body = readCandidateBody("V051__rewrite_auto_flow_exec_prompts.sql");
        assertTrue("8012_p4 overban 被擅自改了（保守批应维持现状）", body.contains("禁止调用任何工具"));
    }

    @Test
    public void v051_onlyExpectedPromptsTouched() {
        String body = readCandidateBody("V051__rewrite_auto_flow_exec_prompts.sql");
        for (String id : new String[]{"8006_p2", "8007_p2", "8009_p3", "8015_p2", "8016_p2",
                "8012_p1", "8012_p3", "8012_p4"}) {
            assertTrue("V051 缺应改 prompt_id: " + id, body.contains("'" + id + "'"));
        }
        // Flow 规划 prompt（第N步/DEPENDS_ON 引用，spec code-owned）本批不动 → 不应出现在 V051
        for (String untouched : new String[]{"'8013_p2'", "'8009_p2'", "'8010_p2'"}) {
            assertFalse("Flow 规划 prompt 不该在 V051（保留 code-owned 引用、本批不改）: " + untouched,
                    body.contains(untouched));
        }
    }
}
