package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.LlmPricing;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LlmPricingTest {

    @Test
    public void testGpt4oMiniVersionMatchesMiniNotBase() {
        // gpt-4o-mini prefix is longer than gpt-4o prefix
        double cost = LlmPricing.estimateCostUsd("gpt-4o-mini-2024-07-18", 1000, 100);
        // Expected: gpt-4o-mini price (0.00015 input, 0.00060 output)
        double expected = 1000 / 1000.0 * 0.00015 + 100 / 1000.0 * 0.00060;
        assertEquals(expected, cost, 0.000001);
    }

    @Test
    public void testGpt4oBaseModelMatch() {
        double cost = LlmPricing.estimateCostUsd("gpt-4o-2024-08-06", 1000, 100);
        double expected = 1000 / 1000.0 * 0.0025 + 100 / 1000.0 * 0.0100;
        assertEquals(expected, cost, 0.000001);
    }

    @Test
    public void testGpt4oMiniIsCheaperThanGpt4o() {
        double costMini = LlmPricing.estimateCostUsd("gpt-4o-mini-2024-07-18", 1000, 100);
        double costBase = LlmPricing.estimateCostUsd("gpt-4o-2024-08-06", 1000, 100);
        assertTrue("gpt-4o-mini variant should be cheaper than gpt-4o variant",
                costMini < costBase);
    }

    @Test
    public void testExactMatch() {
        double cost = LlmPricing.estimateCostUsd("claude-sonnet-4-6", 500, 200);
        double expected = 500 / 1000.0 * 0.0030 + 200 / 1000.0 * 0.0150;
        assertEquals(expected, cost, 0.000001);
    }

    @Test
    public void testUnknownModelReturnsZero() {
        double cost = LlmPricing.estimateCostUsd("unknown-model-v1", 1000, 100);
        assertEquals(0.0, cost, 0.0);
    }

    @Test
    public void testNullModelReturnsZero() {
        double cost = LlmPricing.estimateCostUsd(null, 1000, 100);
        assertEquals(0.0, cost, 0.0);
    }

    @Test
    public void testDeterministicForMultiplePrefixMatch() {
        // Run 100x to catch any non-determinism
        for (int i = 0; i < 100; i++) {
            double cost = LlmPricing.estimateCostUsd("gpt-4o-mini-2024-07-18", 1000, 0);
            double expected = 1000 / 1000.0 * 0.00015;
            assertEquals("Iteration " + i + ": must always match gpt-4o-mini, not gpt-4o",
                    expected, cost, 0.000001);
        }
    }

    @Test
    public void testMimoV26PricedSameAsV25() {
        assertEquals(LlmPricing.estimateCostUsd("mimo-v2.5-pro", 1000, 1000),
                LlmPricing.estimateCostUsd("mimo-v2.6-pro", 1000, 1000), 0.000001);
        assertEquals(LlmPricing.estimateCostUsd("mimo-v2.5", 1000, 1000),
                LlmPricing.estimateCostUsd("mimo-v2.6-flash", 1000, 1000), 0.000001);
        assertTrue(LlmPricing.estimateCostUsd("mimo-v2.6-flash", 1000, 1000) > 0);
    }
}
