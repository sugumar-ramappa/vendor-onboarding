package com.learning.onboarding.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

/**
 * Shared state passed between graph nodes.
 *
 * <p>LangGraph4j state is a {@code Map<String, Object>} underneath. A node does
 * not mutate it - it returns a map of updates, and the framework merges them.
 * That immutability is what makes checkpointing and replay work, so it is worth
 * accepting rather than working around.
 *
 * <p>This class exists to put a typed face on that map. Nodes read through the
 * accessors below instead of scattering string keys through the codebase, which
 * is the same reason you would not pass a {@code Map} around a Spring service
 * layer.
 *
 * <h2>Channels</h2>
 * By default a returned key REPLACES the existing value. That is wrong for
 * findings: five reviewers each return findings, and the last would win. An
 * appender channel makes {@code findings} accumulate instead.
 *
 * <p>Getting this wrong is silent - four reviewers' work simply disappears - so
 * the spike test asserts on it explicitly.
 *
 * <p><b>appenderWithDuplicate, not appender.</b> The plain
 * {@link Channels#appender} variant drops values equal to one already present.
 * Two reviewers legitimately raising the same problem, or a node running twice
 * because the verifier sent work back round the cycle, would then be silently
 * lost - and a trace that omits the second pass through a node is a trace that
 * lies about what happened.
 */
public class ReviewState extends AgentState {

    public static final String APPLICATION_ID = "applicationId";
    public static final String FINDINGS = "findings";
    public static final String TRACE = "trace";

    /**
     * Declares how each key merges. Keys absent from this map replace on write.
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            FINDINGS, Channels.<String>appenderWithDuplicate(List::of),
            TRACE, Channels.<String>appenderWithDuplicate(List::of)
    );

    public ReviewState(Map<String, Object> initData) {
        super(initData);
    }

    public String applicationId() {
        return this.<String>value(APPLICATION_ID)
                .orElseThrow(() -> new IllegalStateException("applicationId missing from state"));
    }

    /** Accumulated across every reviewer node, thanks to the appender channel. */
    public List<String> findings() {
        return this.<List<String>>value(FINDINGS).orElseGet(List::of);
    }

    /** Which nodes ran, in order. Cheap observability while building. */
    public List<String> trace() {
        return this.<List<String>>value(TRACE).orElseGet(List::of);
    }
}
