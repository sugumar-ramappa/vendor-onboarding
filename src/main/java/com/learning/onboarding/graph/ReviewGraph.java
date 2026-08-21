package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.agents.ReviewOutcome;
import com.learning.onboarding.agents.ReviewerAgent;
import com.learning.onboarding.domain.Conflict;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The review pipeline as a graph.
 *
 * <pre>
 *                        START
 *                          │
 *                        intake
 *                          │
 *      ┌────────┬──────────┼──────────┬─────────┐
 *      ▼        ▼          ▼          ▼         ▼
 *  complete- compliance quality  logistics  finance     five parallel nodes
 *   ness        │          │          │         │
 *      └────────┴──────────┼──────────┴─────────┘
 *                          ▼
 *                        gather
 *                          │
 *                         END
 * </pre>
 *
 * <h2>Why the fan-out is the honest shape</h2>
 * The five reviewers share nothing. No reviewer sees another's findings - that
 * isolation exists so one cannot anchor on another, and it is enforced by
 * {@link ReviewContext} having nowhere to put such data and by the agent
 * database role having no SELECT on {@code review_finding}.
 *
 * <p>Independent work has no ordering, so drawing it as a queue would be a
 * drawing of something that is not true. It is also nine minutes instead of two:
 * the correctness requirement and the performance fix are the same decision.
 *
 * <h2>Why a graph library and not CompletableFuture</h2>
 * This fan-out alone would not justify one - {@code allOf} does it in ten lines.
 * What justifies it arrives in step 7: the verifier can send a finding back for
 * more evidence, up to twice, which is a bounded cycle with state. Plus
 * checkpointing, so the graph can pause for a human decision and resume.
 *
 * <p>The graph is built now so those arrive as edges rather than a rewrite.
 */
public class ReviewGraph {

    private static final Logger log = LoggerFactory.getLogger(ReviewGraph.class);

    private final CompiledGraph<ReviewState> graph;
    private final ExecutorService pool;
    private final java.util.concurrent.Semaphore limit;

    public ReviewGraph(List<ReviewerAgent> reviewers) throws GraphStateException {
        this(reviewers, DEFAULT_CONCURRENCY);
    }

    /**
     * Default concurrency.
     *
     * <p>Bounded, not unbounded. Five simultaneous calls into a free-tier model
     * will rate-limit, and a rate-limited reviewer is a reviewer that did not
     * run - which forces the whole application to a human. Slightly slower and
     * complete beats faster and half-reviewed.
     */
    public static final int DEFAULT_CONCURRENCY = 3;

    public ReviewGraph(List<ReviewerAgent> reviewers, int concurrency)
            throws GraphStateException {
        this(reviewers, concurrency, new ConflictDetector());
    }

    public ReviewGraph(List<ReviewerAgent> reviewers, int concurrency,
                       ConflictDetector conflictDetector)
            throws GraphStateException {
        if (reviewers.isEmpty()) {
            throw new IllegalArgumentException("a review graph with no reviewers reviews nothing");
        }

        // Virtual threads: a reviewer spends essentially all its time waiting on
        // a network call, so platform threads would be idle memory. The pool is
        // still bounded by a semaphore in runReviewer - see DEFAULT_CONCURRENCY.
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.limit = new java.util.concurrent.Semaphore(concurrency);

        StateGraph<ReviewState> builder = new StateGraph<>(ReviewState.SCHEMA, ReviewState::new);

        // A single entry node, then fan out from it. Every reviewer gets the
        // same starting state, and none can see what another produced.
        builder.addNode("intake", node_async(state -> {
            log.info("reviewing {} with {} reviewer(s)", state.applicationId(), reviewers.size());
            return Map.of(ReviewState.TRACE, "intake");
        }));

        for (ReviewerAgent reviewer : reviewers) {
            String node = reviewer.area().name().toLowerCase();
            // NOT node_async(): that wraps a synchronous function and computes
            // it eagerly on the calling thread, so the branches run one after
            // another however the graph is drawn. supplyAsync is what actually
            // overlaps them.
            // Typed explicitly: a bare lambda matches both addNode overloads
            // and the compiler cannot choose.
            AsyncNodeAction<ReviewState> action = state ->
                    CompletableFuture.supplyAsync(() -> runReviewer(reviewer, state), pool);
            builder.addNode(node, action);
            builder.addEdge("intake", node);   // parallel: several edges, one source
            builder.addEdge(node, "gather");
        }

        // Joins the parallel branches. The framework has already merged each
        // branch's findings through the appender channel by the time this runs,
        // which is what lets conflict detection happen here: it is the first
        // point at which every reviewer's output exists in one place.
        //
        // Note that this is the ONLY place findings from different reviewers are
        // ever seen together. No reviewer has been shown another's work.
        builder.addNode("gather", node_async(state -> {
            List<Conflict> conflicts = conflictDetector.detect(state.findings());

            log.info("{}: {} finding(s), {} conflict(s), {} reviewer failure(s)",
                    state.applicationId(), state.findings().size(),
                    conflicts.size(), state.failures().size());

            return conflicts.isEmpty()
                    ? Map.of(ReviewState.TRACE, "gather")
                    : Map.of(ReviewState.CONFLICTS, conflicts, ReviewState.TRACE, "gather");
        }));

        builder.addEdge(START, "intake");
        builder.addEdge("gather", END);

        this.graph = builder.compile();
    }

    /**
     * Runs one reviewer, converting a failure into recorded state rather than
     * letting it kill the run.
     *
     * <p><b>The failure is recorded, never swallowed.</b> Returning an empty
     * finding list here would make a rate-limited reviewer indistinguishable
     * from one that looked and found nothing - and the application would then
     * appear to have passed a review nobody performed. The failure goes into its
     * own channel, and the decision gate refuses to auto-approve while it is
     * non-empty.
     *
     * <p>One reviewer failing does not abort the other four. Four opinions plus
     * a known gap is more useful to a human than nothing at all.
     */
    private Map<String, Object> runReviewer(ReviewerAgent reviewer, ReviewState state) {
        String area = reviewer.area().name();
        try {
            limit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of(ReviewState.FAILURES, area + ": interrupted",
                    ReviewState.TRACE, area + " (interrupted)");
        }
        try {
            ReviewOutcome outcome = reviewer.review(state.context());

            // The audit entry is recorded whether the call succeeded or not.
            // A failed call is a fact worth keeping - it is the evidence that
            // this application was not fully reviewed.
            if (outcome.succeeded()) {
                return Map.of(
                        ReviewState.FINDINGS, outcome.findings(),
                        ReviewState.AUDIT, outcome.audit(),
                        ReviewState.TRACE, area);
            }
            log.warn("{} reviewer failed for {}: {}",
                    area, state.applicationId(), outcome.audit().failureDetail());
            return Map.of(
                    ReviewState.FAILURES,
                    area + ": " + outcome.audit().outcome() + " - "
                            + outcome.audit().failureDetail(),
                    ReviewState.AUDIT, outcome.audit(),
                    ReviewState.TRACE, area + " (failed)");
        } finally {
            limit.release();
        }
    }

    /** Runs the pipeline. */
    public ReviewState review(ReviewContext context) {
        return graph.invoke(Map.of(ReviewState.CONTEXT, context))
                .orElseThrow(() -> new IllegalStateException(
                        "graph produced no final state for " + context.applicationId()));
    }

    /** The compiled graph, for the studio visualiser and for tests. */
    public CompiledGraph<ReviewState> compiled() {
        return graph;
    }
}
