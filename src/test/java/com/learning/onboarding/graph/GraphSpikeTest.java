package com.learning.onboarding.graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 0 - the LangGraph4j spike.
 *
 * <p>No model calls, no Spring context, no domain. This exists to answer four
 * questions before anything is built on top of them:
 *
 * <ol>
 *   <li>does a graph compile and run?</li>
 *   <li>does state flow from node to node?</li>
 *   <li>do appender channels accumulate rather than overwrite?</li>
 *   <li>do conditional edges actually branch - and can they cycle?</li>
 * </ol>
 *
 * <p>Point 3 is the one that bites. Without a channel, five reviewer nodes each
 * returning findings would leave you with only the last one's, silently. Point 4
 * is why a graph library is being used at all - a fan-out that never loops could
 * have been written with CompletableFuture.
 *
 * <p>If this test fails, stop. Do not build the domain on top of a graph library
 * that is not behaving as expected.
 */
class GraphSpikeTest {

    @Test
    @DisplayName("state flows through nodes and appender channels accumulate")
    void stateFlowsAndAccumulates() throws Exception {

        CompiledGraph<ReviewState> graph = new StateGraph<>(ReviewState.SCHEMA, ReviewState::new)
                .addNode("compliance", node_async(state -> Map.of(
                        ReviewState.FAILURES, "compliance: certificate expired",
                        ReviewState.TRACE, "compliance")))
                .addNode("quality", node_async(state -> Map.of(
                        ReviewState.FAILURES, "quality: two open non-conformances",
                        ReviewState.TRACE, "quality")))
                .addEdge(START, "compliance")
                .addEdge("compliance", "quality")
                .addEdge("quality", END)
                .compile();

        Optional<ReviewState> result = graph.invoke(Map.of("marker", "APP-001"));

        assertTrue(result.isPresent(), "graph produced no final state");
        ReviewState finalState = result.get();

        assertEquals("APP-001", finalState.<String>value("marker").orElseThrow(),
                "input state did not survive to the end of the graph");

        // The assertion that matters. Without the AppenderChannel this is 1.
        assertEquals(2, finalState.failures().size(),
                "values were overwritten instead of accumulated - check ReviewState.SCHEMA");

        assertEquals(List.of("compliance", "quality"), finalState.trace(),
                "nodes did not run in the expected order");
    }

    @Test
    @DisplayName("conditional edges branch on state")
    void conditionalEdgesBranch() throws Exception {

        CompiledGraph<ReviewState> graph = buildBranchingGraph();

        // Path A: nothing blocking, so verification is skipped.
        ReviewState clean = graph.invoke(Map.of("blocking", false)).orElseThrow();

        assertEquals(List.of("triage"), clean.trace(),
                "clean application should not reach the verifier");

        // Path B: something blocking, so the verifier runs.
        ReviewState flagged = graph.invoke(Map.of("blocking", true)).orElseThrow();

        assertEquals(List.of("triage", "verify"), flagged.trace(),
                "blocking finding should route through the verifier");
    }

    /**
     * triage --(blocking?)--> verify --> END
     *        \--(clean)-----------------> END
     */
    private CompiledGraph<ReviewState> buildBranchingGraph() throws Exception {
        return new StateGraph<>(ReviewState.SCHEMA, ReviewState::new)
                .addNode("triage", node_async(state -> Map.of(ReviewState.TRACE, "triage")))
                .addNode("verify", node_async(state -> Map.of(ReviewState.TRACE, "verify")))
                .addEdge(START, "triage")
                .addConditionalEdges("triage",
                        edge_async(state -> state.<Boolean>value("blocking").orElse(false)
                                ? "needsVerification"
                                : "clean"),
                        // Maps the edge action's return value to a node name.
                        Map.of("needsVerification", "verify", "clean", END))
                .addEdge("verify", END)
                .compile();
    }
}
