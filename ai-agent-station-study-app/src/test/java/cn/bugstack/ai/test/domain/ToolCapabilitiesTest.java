package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapability;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityPolicies;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ToolCapabilitiesTest {

    @Test
    public void fiveProfilesHaveFrozenCapabilitySets() {
        assertTrue(ToolCapabilityProfile.NONE.capabilities().isEmpty());
        assertEquals(java.util.Set.of(ToolCapability.REQUEST_TOOL), ToolCapabilityProfile.DISCOVERY_ONLY.capabilities());
        assertEquals(java.util.Set.of(ToolCapability.ASK_USER, ToolCapability.REQUEST_TOOL),
                ToolCapabilityProfile.INTERACTIVE_META.capabilities());
        assertEquals(java.util.Set.of(ToolCapability.BUSINESS_TOOL), ToolCapabilityProfile.BUSINESS_ONLY.capabilities());
        assertEquals(java.util.Set.of(ToolCapability.BUSINESS_TOOL, ToolCapability.ASK_USER, ToolCapability.REQUEST_TOOL),
                ToolCapabilityProfile.ALL.capabilities());
    }

    @Test
    public void earlyStageAnalysisPlanningCanAskUser_qualityStaysDiscoveryOnly() {
        // 2026-06-22 用户启用：分析/规划阶段允许 ask_user（EARLY_STAGE_DEFAULT 提升为 INTERACTIVE_META）
        assertSame(ToolCapabilityProfile.INTERACTIVE_META, ToolCapabilityProfile.EARLY_STAGE_DEFAULT);
        assertSame(ToolCapabilityProfile.INTERACTIVE_META, ToolCapabilityPolicies.AUTO_STEP1_ANALYSIS);
        assertSame(ToolCapabilityProfile.INTERACTIVE_META, ToolCapabilityPolicies.FLOW_STEP1_TOOL_ANALYSIS);
        assertSame(ToolCapabilityProfile.INTERACTIVE_META, ToolCapabilityPolicies.FLOW_STEP2_PLANNING);
        // 质检步显式 DISCOVERY_ONLY —— 自评不阻塞问用户（不引用 EARLY_STAGE_DEFAULT）
        assertSame(ToolCapabilityProfile.DISCOVERY_ONLY, ToolCapabilityPolicies.AUTO_STEP3_QUALITY);
    }

    @Test
    public void callPrototypeMatrixIsFrozenInOneAuthority() {
        assertSame(ToolCapabilityProfile.ALL, ToolCapabilityPolicies.FIXED_NORMAL);
        assertSame(ToolCapabilityProfile.ALL, ToolCapabilityPolicies.FIXED_STEER);
        assertSame(ToolCapabilityProfile.ALL, ToolCapabilityPolicies.AUTO_STEP2_EXECUTION);
        assertSame(ToolCapabilityProfile.NONE, ToolCapabilityPolicies.AUTO_STEP4_SUMMARY);
        assertSame(ToolCapabilityProfile.ALL, ToolCapabilityPolicies.FLOW_DAG_EXECUTION);
        assertSame(ToolCapabilityProfile.NONE, ToolCapabilityPolicies.FLOW_FINAL_SYNTHESIS);
        assertSame(ToolCapabilityProfile.NONE, ToolCapabilityPolicies.answerNow(false));
        assertSame(ToolCapabilityProfile.BUSINESS_ONLY, ToolCapabilityPolicies.answerNow(true));
    }

    @Test
    public void toolContextParserDistinguishesExplicitMissingAndInvalid() {
        assertEquals(ToolCapabilities.State.MISSING, ToolCapabilities.parse(Map.of()).state());
        assertEquals(ToolCapabilityProfile.BUSINESS_ONLY,
                ToolCapabilities.parse(Map.of(ToolCapabilities.TOOL_CONTEXT_KEY, "business_only")).profile());
        assertEquals(ToolCapabilityProfile.ALL,
                ToolCapabilities.parse(Map.of(ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.ALL)).profile());
        ToolCapabilities.Parsed invalid = ToolCapabilities.parse(Map.of(ToolCapabilities.TOOL_CONTEXT_KEY, "surprise"));
        assertEquals(ToolCapabilities.State.INVALID, invalid.state());
        assertEquals(ToolCapabilityProfile.NONE, invalid.profile());

        Map<String, Object> context = new HashMap<>();
        ToolCapabilities.put(context, ToolCapabilityProfile.DISCOVERY_ONLY);
        assertEquals("DISCOVERY_ONLY", context.get(ToolCapabilities.TOOL_CONTEXT_KEY));
    }
}
