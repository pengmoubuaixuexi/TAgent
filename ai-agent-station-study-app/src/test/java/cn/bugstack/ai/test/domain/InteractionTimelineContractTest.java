package cn.bugstack.ai.test.domain;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Frontend contract: human interaction events must never manufacture tool cards. */
public class InteractionTimelineContractTest {

    @Test
    public void timelineInteractionKeepsRecoveryStateWithoutCreatingCards() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull("static/index.html must be on the test classpath", stream);
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = html.indexOf("function timelineInteraction");
            int end = html.indexOf("function applyRunTimelineEvent", start);
            assertTrue("timelineInteraction must exist", start >= 0 && end > start);

            String function = html.substring(start, end);
            assertTrue("pending interaction state must remain recoverable",
                    function.contains("view.interactionById[id] = {card: null"));
            assertFalse("human interactions must not create DOM cards",
                    function.contains("document.createElement"));
            assertFalse("human interactions must not use tool-card styling",
                    function.contains("tool-card"));
        }
    }
}
