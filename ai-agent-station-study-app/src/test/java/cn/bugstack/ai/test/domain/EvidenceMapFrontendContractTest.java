package cn.bugstack.ai.test.domain;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Frontend contract for inspecting Evidence Map sources when mapping is empty. */
public class EvidenceMapFrontendContractTest {

    @Test
    public void emptyMappingStillExposesCollectedSourcesAndDiagnostics() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull("static/index.html must be on the test classpath", stream);
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("id=\"evidenceMapSourcesBtn\""));
            assertTrue(html.contains("function renderAllEvidenceSources"));
            assertTrue(html.contains("user_request:'用户原始请求'"));
            assertTrue(html.contains("partial_support:'部分/推导依据'"));
            assertTrue(html.contains("function normalizeGenericEvidenceValue"));
            assertTrue(html.contains("核验点 ${coveredChecks}/${checkCount}"));
            assertTrue(html.contains("const checkLinks = Array.isArray(check && check.links) ? check.links : []"));
            assertTrue(html.contains("该核验点尚未找到可验证来源。"));
            assertTrue(html.contains("data-check-index=\"${checkIndex}\""));
            assertTrue(html.contains("data-link-index=\"${linkIndex}\""));
            assertTrue(html.contains(": `${title}|${String(text).replace(/\\s+/g, ' ').trim()}`"));
            assertTrue(html.contains("可追溯率"));
            assertTrue(html.contains("已经采集 ${evidences.length} 条来源"));
            assertTrue(html.contains("模型返回 ${modelClaims} 条，本次接受 0 条"));
            assertTrue("duplicate clicks must reuse the same in-flight request",
                    html.contains("state.evidenceMapRequests.get(runId)"));
            assertTrue("a failed regeneration must keep the previous generated map",
                    html.contains("已保留原证据地图"));
            assertTrue("expired snapshots must block regeneration while keeping sources readable",
                    html.contains("data.regenerateAvailable === false"));
            assertTrue(html.contains("当前已生成的证据地图会继续保留"));
        }
    }
}
