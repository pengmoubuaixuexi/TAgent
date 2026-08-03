package cn.bugstack.ai.test.domain;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Frontend contract: human interaction events must never manufacture tool cards. */
public class InteractionTimelineContractTest {

    private String frontendHtml() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull("static/index.html must be on the test classpath", stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void timelineInteractionKeepsRecoveryStateWithoutCreatingCards() throws Exception {
        String html = frontendHtml();
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

    @Test
    public void restoredAskUserMustNotFlashAfterItExpiredOrLeakAcrossSessions() throws Exception {
        String html = frontendHtml();

        int showStart = html.indexOf("function showUserInput");
        int showEnd = html.indexOf("function renderUserInputOptions", showStart);
        assertTrue("showUserInput must exist", showStart >= 0 && showEnd > showStart);
        String showFunction = html.substring(showStart, showEnd);
        int staleGuard = showFunction.indexOf("explicitDeadline <= Date.now()");
        int revealModal = showFunction.indexOf("userInputModal').classList.remove('hidden')");
        assertTrue("expired ask_user must be rejected", staleGuard >= 0);
        assertTrue("expired guard must run before revealing the modal",
                revealModal >= 0 && staleGuard < revealModal);

        int detachStart = html.indexOf("function detachObservedRunUi");
        int detachEnd = html.indexOf("function activeConversationStorageKey", detachStart);
        assertTrue("detachObservedRunUi must exist", detachStart >= 0 && detachEnd > detachStart);
        String detachFunction = html.substring(detachStart, detachEnd);
        assertTrue("session switch must clear ask_user state",
                detachFunction.contains("state.pendingUserInput = null"));
        assertTrue("session switch must hide ask_user modal",
                detachFunction.contains("hideUserInputModal()"));
    }

    @Test
    public void askUserDialogMustKeepSubmitVisibleAndSupportRealMultiSelect() throws Exception {
        String html = frontendHtml();

        int dialog = html.indexOf("id=\"userInputDialog\"");
        int scrollable = html.indexOf("id=\"userInputScrollableContent\"", dialog);
        int submit = html.indexOf("id=\"userInputSubmitBtn\"", scrollable);
        assertTrue("ask_user dialog must be capped to the viewport", dialog >= 0
                && html.substring(dialog, scrollable).contains("max-h-[calc(100vh-2rem)]"));
        assertTrue("long ask_user content must scroll inside the dialog", scrollable > dialog
                && html.substring(scrollable, submit).contains("overflow-y-auto"));
        assertTrue("submit footer must remain after the scrollable content", submit > scrollable);

        assertTrue("frontend must detect structured and legacy multi-select questions",
                html.contains("function isUserInputMultiSelect"));
        assertTrue("multi-select state must retain several answers",
                html.contains("answers.push(label)") && html.contains("answers.splice(selectedIndex, 1)"));
        assertTrue("multi-select answers must still be serialized as text",
                html.contains("value.answers.join('、')"));
    }
}
