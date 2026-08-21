package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.Conflict;
import com.learning.onboarding.domain.ReviewFinding;
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
 * <p>This class puts a typed face on that map, so nodes read through accessors
 * instead of scattering string keys around.
 *
 * <h2>Channels</h2>
 * By default a returned key REPLACES the existing value. That is wrong for
 * findings: five reviewers run in parallel and all return findings, so the last
 * one to finish would win and the other four would vanish. An appender channel
 * accumulates instead.
 *
 * <p><b>appenderWithDuplicate, not appender.</b> The plain variant drops values
 * equal to one already present. Two reviewers legitimately raising the same
 * problem, or a node running twice because the verifier sent work back round the
 * cycle, would then be silently lost - and a trace that omits the second pass
 * through a node is a trace that lies about what happened.
 *
 * <h2>Why failures are a separate channel</h2>
 * A reviewer that could not run must not look like a reviewer that found
 * nothing. {@link #failures()} carries the difference, and the decision gate
 * refuses to auto-approve while it is non-empty - if nobody looked at
 * compliance, the application goes to a human whatever the other four concluded.
 */
public class ReviewState extends AgentState {

    public static final String CONTEXT = "context";
    public static final String FINDINGS = "findings";
    public static final String FAILURES = "failures";
    public static final String CONFLICTS = "conflicts";
    public static final String TRACE = "trace";

    /** Declares how each key merges. Keys absent from this map replace on write. */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            FINDINGS, Channels.<ReviewFinding>appenderWithDuplicate(List::of),
            FAILURES, Channels.<String>appenderWithDuplicate(List::of),
            CONFLICTS, Channels.<Conflict>appenderWithDuplicate(List::of),
            TRACE, Channels.<String>appenderWithDuplicate(List::of)
    );

    public ReviewState(Map<String, Object> initData) {
        super(initData);
    }

    /** The application and its documents. Set once, at the start. */
    public ReviewContext context() {
        return this.<ReviewContext>value(CONTEXT)
                .orElseThrow(() -> new IllegalStateException("no review context in state"));
    }

    public String applicationId() {
        return context().applicationId();
    }

    /** Accumulated across every reviewer, thanks to the appender channel. */
    public List<ReviewFinding> findings() {
        return this.<List<ReviewFinding>>value(FINDINGS).orElseGet(List::of);
    }

    /**
     * Where two reviewers disagreed about the same subject.
     *
     * <p>Never resolved automatically - both positions go to a human, because at
     * least one of them is wrong and deciding which is a business judgement.
     */
    public List<Conflict> conflicts() {
        return this.<List<Conflict>>value(CONFLICTS).orElseGet(List::of);
    }

    /** Reviewers that could not run. Not the same as reviewers that found nothing. */
    public List<String> failures() {
        return this.<List<String>>value(FAILURES).orElseGet(List::of);
    }

    /** Which nodes ran, in order. Cheap observability while building. */
    public List<String> trace() {
        return this.<List<String>>value(TRACE).orElseGet(List::of);
    }

    /** True when every reviewer ran. Nothing may be auto-decided otherwise. */
    public boolean allReviewersRan() {
        return failures().isEmpty();
    }
}
