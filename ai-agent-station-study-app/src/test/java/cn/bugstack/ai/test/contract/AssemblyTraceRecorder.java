package cn.bugstack.ai.test.contract;

import java.util.ArrayList;
import java.util.List;

/** Test-only recorder tying assembly provenance to a captured wire request. */
public final class AssemblyTraceRecorder {

    private final List<AssemblyTrace> traces = new ArrayList<>();

    public void record(AssemblyTrace trace) {
        traces.add(trace);
    }

    public List<AssemblyTrace> traces() {
        return List.copyOf(traces);
    }

    public AssemblyTrace require(String stableId) {
        return traces.stream().filter(t -> t.stableId().equals(stableId)).findFirst()
                .orElseThrow(() -> new AssertionError("missing AssemblyTrace for " + stableId));
    }

    public void assertMatches(WireSnapshot snapshot) {
        AssemblyTrace trace = require(snapshot.stableId());
        java.util.Set<String> expected = new java.util.TreeSet<>(trace.resolvedToolNames());
        java.util.Set<String> actual = new java.util.TreeSet<>(snapshot.toolNames());
        if (!expected.equals(actual)) {
            throw new AssertionError("tool provenance/wire mismatch for " + snapshot.stableId()
                    + ": trace=" + expected + ", wire=" + actual);
        }
    }
}
