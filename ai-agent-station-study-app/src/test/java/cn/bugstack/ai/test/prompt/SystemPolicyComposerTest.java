package cn.bugstack.ai.test.prompt;

import cn.bugstack.ai.domain.agent.service.prompt.SystemPolicyComposer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P2-B-1 SystemPolicyComposer 纯函数测试（v3.md §73）：
 * 公共信任边界声明 + prepend 不替换/不改写领域 prompt。A1/A2 真实接入由全量/E2E 验证（交别的模型）。
 */
public class SystemPolicyComposerTest {

    @Test
    public void policy_declaresTrustBoundaryAndNonOverrideSemantics() {
        String p = SystemPolicyComposer.policy();
        assertNotNull(p);
        assertFalse(p.isBlank());
        // 信任边界核心语义：背景数据 + 不得覆盖角色/阶段/契约/工具权限
        assertTrue("声明背景数据", p.contains("背景数据"));
        assertTrue("不得覆盖角色", p.contains("角色"));
        assertTrue("不得覆盖阶段职责", p.contains("阶段"));
        assertTrue("不得覆盖输出契约", p.contains("契约"));
        assertTrue("不得覆盖工具权限", p.contains("工具"));
    }

    @Test
    public void prepend_putsPolicyBeforeDomainPromptAndKeepsItIntact() {
        String domain = "你是攻略编写者，输出完整攻略。";
        String out = SystemPolicyComposer.prepend(domain);
        assertTrue("信任边界在最前", out.startsWith(SystemPolicyComposer.policy()));
        assertTrue("领域 prompt 原样保留在后", out.endsWith(domain));
        assertTrue("确实 prepend 了内容（不只是返回领域 prompt）", out.length() > domain.length());
    }

    @Test
    public void prepend_nullOrBlank_returnsPolicyOnly() {
        assertEquals(SystemPolicyComposer.policy(), SystemPolicyComposer.prepend(null));
        assertEquals(SystemPolicyComposer.policy(), SystemPolicyComposer.prepend(""));
        assertEquals(SystemPolicyComposer.policy(), SystemPolicyComposer.prepend("   "));
    }

    @Test
    public void prepend_doesNotReplaceOrMutateDomainPrompt() {
        // 治理目标：SystemPolicy 不替换 DB system prompt（DB prompt 重写归 P4）
        String domain = "【本阶段硬性约束】本步只做质量审查。";
        String out = SystemPolicyComposer.prepend(domain);
        assertTrue("领域 prompt 全文仍在", out.contains(domain));
    }
}
