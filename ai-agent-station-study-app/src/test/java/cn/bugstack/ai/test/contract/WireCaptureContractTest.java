package cn.bugstack.ai.test.contract;

import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Structural capture ledger for all twelve P0-A main-chain stable IDs. */
public class WireCaptureContractTest {

    @Test
    public void capturesEveryMainChainPrototypeAndKeepsCurrentSeparateFromTarget() {
        List<AsIsClientFixtureFactory.Prototype> prototypes = AsIsClientFixtureFactory.prototypes();
        assertEquals(12, prototypes.size());
        assertEquals(12, new HashSet<>(prototypes.stream().map(AsIsClientFixtureFactory.Prototype::stableId).toList()).size());

        AssemblyTraceRecorder traces = new AssemblyTraceRecorder();
        for (AsIsClientFixtureFactory.Prototype prototype : prototypes) {
            String runId = "run/" + prototype.stableId();
            CapturingChatModel model = new CapturingChatModel().forStableId(prototype.stableId());
            ChatClient client = AsIsClientFixtureFactory.client(prototype, model);
            ChatClient.ChatClientRequestSpec request = client.prompt().user("synthetic task for " + prototype.stableId())
                    .options(AsIsClientFixtureFactory.requestOptions(prototype));
            if (prototype.requestSystemOverride() != null) {
                request = request.system(prototype.requestSystemOverride());
            }
            request.call().content();

            WireSnapshot wire = model.lastSnapshot();
            traces.record(AsIsClientFixtureFactory.trace(prototype, runId));
            traces.assertMatches(wire);
            assertEquals(prototype.stableId(), wire.stableId());
            assertEquals(Integer.valueOf(321), wire.maxTokens());
            assertEquals(prototype.resolvedTools(), wire.toolNames());
            assertTrue(wire.roles().contains("SYSTEM"));
            assertTrue(wire.roles().contains("USER"));

            AssemblyTrace trace = traces.require(prototype.stableId());
            assertNotEquals("current mechanism must not be replaced by the P1 target",
                    trace.currentPolicyMechanism(), trace.targetPolicyReference());
        }
    }

    @Test
    public void flowDagRequestSystemOverridesDefaultSystem() {
        var prototype = AsIsClientFixtureFactory.prototypes().stream()
                .filter(p -> p.stableId().equals("Flow-DAG")).findFirst().orElseThrow();
        CapturingChatModel model = new CapturingChatModel().forStableId(prototype.stableId());
        AsIsClientFixtureFactory.client(prototype, model).prompt()
                .system(prototype.requestSystemOverride()).user("execute one step").call().content();
        String system = model.lastSnapshot().messages().stream()
                .filter(m -> m.role().equals("SYSTEM")).findFirst().orElseThrow().text();
        assertEquals("SUB_STEP_EXECUTOR_SYSTEM", system);
    }
}
