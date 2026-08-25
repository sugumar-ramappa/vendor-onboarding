package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.agents.ReviewOutcome;
import com.learning.onboarding.agents.ReviewerAgent;
import com.learning.onboarding.agents.VerifierAgent;
import com.learning.onboarding.config.PolicyProperties;
import com.learning.onboarding.domain.Conflict;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Verdict;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The review pipeline.
 *
 * <pre>
 *                          START
 *                            │
 *                       completeness              is the pack even usable?
 *                            │
 *              ┌─ incomplete ─┴─ complete ─┐
 *              ▼                           ▼
 *      requestDocuments                 fanOut
 *              │               ┌──────────┴──────────┐
 *              ▼               ▼    4 reviewers      ▼
 *             END          compliance quality logistics finance
 *                              └──────────┬──────────┘
 *                                         ▼
 *                                  gather + conflicts
 *                                         ▼
 *                                ┌───► verify ───┐
 *                     unresolved │               │ decided
 *                       (max 2)  └── gatherMore ─┘
 *                                         ▼
 *                                      decide
 *                                         │
 *                          ┌─ clean ──────┴──── needs a human ─┐
 *                          ▼                                   ▼
 *                         END                       PAUSE, checkpointed
 * </pre>
 *
 * <h2>Each graph feature earns its place from the business, not the framework</h2>
 *
 * <b>1. The completeness gate exists because chasing documents costs weeks.</b>
 * A vendor whose pack is missing three mandatory documents does not need four
 * reviewers arguing about the contents - it needs the documents. Discovering
 * gaps one review at a time turns a two-week delay into an eight-week one, so
 * completeness runs first, alone, and asks for everything missing at once.
 *
 * <p>The conditional edge that skips the other four also saves four model calls
 * and about two minutes per incomplete submission - and incomplete submissions
 * are the common case, not the exception.
 *
 * <p><b>2. The verify cycle exists because escalating costs a person's time.</b>
 * When the verifier cannot decide, it names the specific thing that would settle
 * the question - not "more information" but "whether policy PL-4471029 has a
 * territorial endorsement". {@code gatherMore} reads that from the reference
 * database and {@code verify} looks again, so a finding that would have cost a
 * person ten minutes is settled by a SQL query.
 *
 * <p>Three ways out: nothing unresolved, nothing left to fetch, or the bound.
 * The middle one is the real convergence condition - without it the loop runs to
 * the bound every time, asking the same question with the same information. The
 * bound is a backstop, and it is in Java rather than in a prompt because a loop
 * whose continuation a model controls is exactly the shape a crafted document
 * would exploit.
 *
 * <p>A later pass re-challenges only findings still marked UNRESOLVED. Re-running
 * the whole set would be 2N model calls to learn nothing new about N-1 of them.
 *
 * <p><b>3. The pause exists because a human decision takes days.</b> Anything
 * blocking checkpoints the whole graph to Postgres and stops. A person decides
 * on Thursday; the run resumes from exactly where it stopped rather than
 * re-reviewing everything. Without it, either the request blocks for two days or
 * the review is thrown away and repeated.
 *
 * <p>The third is the one that would be genuinely unpleasant to hand-roll - it
 * means serialising in-flight state - and it is why every domain record here
 * implements {@code Serializable}.
 */
public class ReviewGraph {

    private static final Logger log = LoggerFactory.getLogger(ReviewGraph.class);

    /**
     * How many times a finding may go round the verify cycle.
     *
     * <p>Two. Beyond that the verifier is not converging, and each pass is a
     * model call per unresolved finding.
     */
    public static final int MAX_VERIFY_PASSES = 2;

    public static final int DEFAULT_CONCURRENCY = 2;

    /** The node the graph pauses before when a human is needed. */
    public static final String HUMAN_REVIEW_NODE = "humanReview";

    private final CompiledGraph<ReviewState> graph;
    private final ExecutorService pool;
    private final Semaphore limit;
    private final GroundingCheck groundingCheck;
    private final VerifierAgent verifier;
    private final EvidenceGatherer gatherer;
    private final PolicyProperties policy;

    public ReviewGraph(ReviewerAgent completeness, List<ReviewerAgent> reviewers,
                       int concurrency, ConflictDetector conflictDetector,
                       GroundingCheck groundingCheck, VerifierAgent verifier,
                       EvidenceGatherer gatherer, PolicyProperties policy,
                       BaseCheckpointSaver checkpointSaver)
            throws GraphStateException {

        if (reviewers.isEmpty()) {
            throw new IllegalArgumentException("a review graph with no reviewers reviews nothing");
        }
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.limit = new Semaphore(concurrency);
        this.groundingCheck = groundingCheck;
        this.verifier = verifier;
        this.gatherer = gatherer == null ? EvidenceGatherer.NONE : gatherer;
        this.policy = policy;

        var builder = new StateGraph<>(ReviewState.SCHEMA, ReviewState::new);

        // ------------------------------------------------- gate: completeness --
        builder.addNode("completeness", async(state -> preFork(runReviewer(completeness, state))));

        builder.addNode("requestDocuments", node_async(state -> {
            log.info("{}: pack incomplete ({} finding(s)) - skipping the substantive "
                            + "reviews and asking for documents",
                    state.applicationId(), state.findings().size());
            return Map.of(ReviewState.TRACE, "requestDocuments",
                    ReviewState.VERDICT, "DOCUMENTS_REQUESTED");
        }));

        builder.addConditionalEdges("completeness",
                edge_async(state -> blocksOn(state.findings()) ? "incomplete" : "complete"),
                Map.of("incomplete", "requestDocuments", "complete", "fanOut"));

        builder.addEdge("requestDocuments", END);

        // ------------------------------------------------------------ fan-out --
        builder.addNode("fanOut", node_async(state -> {
            log.info("{}: pack complete, running {} reviewer(s)",
                    state.applicationId(), reviewers.size());
            // Read-modify-write on a replace channel. Still idempotent: a
            // re-merge re-applies this recorded value, it does not re-run the
            // node, so the list cannot grow a second "fanOut".
            return Map.of(ReviewState.GATE_TRACE,
                    Stream.concat(state.trace().stream(), Stream.of("fanOut")).toList());
        }));

        for (ReviewerAgent reviewer : reviewers) {
            String node = reviewer.area().name().toLowerCase();
            builder.addNode(node, async(state -> runReviewer(reviewer, state)));
            builder.addEdge("fanOut", node);     // several edges, one source = parallel
            builder.addEdge(node, "gather");
        }

        // The first point where every reviewer's output exists together, and
        // therefore the only place a conflict can be seen at all.
        builder.addNode("gather", node_async(state -> {
            List<Conflict> conflicts = conflictDetector.detect(state.findings());
            log.info("{}: {} finding(s), {} conflict(s), {} reviewer failure(s)",
                    state.applicationId(), state.findings().size(),
                    conflicts.size(), state.failures().size());

            Map<String, Object> updates = new HashMap<>();
            updates.put(ReviewState.TRACE, "gather");
            if (!conflicts.isEmpty()) {
                updates.put(ReviewState.CONFLICTS, conflicts);
            }
            return updates;
        }));

        // -------------------------------------------------------- verify cycle --
        builder.addNode("verify", async(this::verifyFindings));

        builder.addNode("gatherMore", node_async(state -> {
            List<String> questions = state.unresolvedQuestions();
            List<EvidenceNeed> needs = state.evidenceNeeds();
            log.info("{}: verifier asked for {} on {} finding(s), pass {}",
                    state.applicationId(), needs, questions.size(), state.verifyPasses() + 1);

            String gathered = gatherer.gather(needs, questions, state.context());

            Map<String, Object> updates = new HashMap<>();
            updates.put(ReviewState.VERIFY_PASSES, state.verifyPasses() + 1);
            updates.put(ReviewState.EVIDENCE, gathered);
            updates.put(ReviewState.TRACE,
                    gathered.isBlank() ? "gatherMore (nothing to fetch)" : "gatherMore");
            return updates;
        }));

        // Three ways out, and only one of them is the counter.
        //
        //   nothing unresolved      the cycle did its job
        //   nothing left to fetch   another pass would ask the same question
        //                           with the same information - the condition
        //                           that made the loop pointless before the
        //                           gatherer existed
        //   the bound               a backstop, not the schedule
        builder.addConditionalEdges("verify",
                edge_async(state -> {
                    if (!state.hasUnresolvedFindings()) {
                        return "done";
                    }
                    if (state.verifyPasses() >= MAX_VERIFY_PASSES) {
                        log.info("{}: verify bound reached with {} still unresolved - "
                                        + "they survive and go to a human",
                                state.applicationId(), state.unresolvedQuestions().size());
                        return "done";
                    }
                    if (state.verifyPasses() > 0 && !state.hasGatheredEvidence()) {
                        log.info("{}: nothing left to fetch - another pass cannot help",
                                state.applicationId());
                        return "done";
                    }
                    return "again";
                }),
                Map.of("again", "gatherMore", "done", "decide"));

        builder.addEdge("gatherMore", "verify");   // ← the cycle

        // ---------------------------------------------------- gate: authority --
        builder.addNode("decide", node_async(this::decide));

        builder.addNode(HUMAN_REVIEW_NODE, node_async(state -> {
            log.info("{}: human decision applied", state.applicationId());
            return Map.of(ReviewState.TRACE, HUMAN_REVIEW_NODE);
        }));

        builder.addConditionalEdges("decide",
                edge_async(state -> state.needsHuman() ? "human" : "clean"),
                Map.of("human", HUMAN_REVIEW_NODE, "clean", END));

        builder.addEdge(HUMAN_REVIEW_NODE, END);
        builder.addEdge(START, "completeness");
        builder.addEdge("gather", "verify");

        var config = CompileConfig.builder()
                // Pause BEFORE the human node, so the checkpoint holds exactly
                // what a person needs to see and the run stops rather than
                // finishing and pretending a decision was made.
                .interruptBefore(HUMAN_REVIEW_NODE)
                // Belt and braces on the cycle: even with a broken pass counter
                // the graph cannot spin forever.
                .recursionLimit(40);

        if (checkpointSaver != null) {
            config.checkpointSaver(checkpointSaver);
        }
        this.graph = builder.compile(config.build());
    }

    /**
     * NOT {@code node_async}: that wraps a synchronous function and computes it
     * eagerly on the calling thread, so branches run one after another however
     * the graph is drawn.
     */
    private AsyncNodeAction<ReviewState> async(Function<ReviewState, Map<String, Object>> work) {
        return state -> CompletableFuture.supplyAsync(() -> work.apply(state), pool);
    }

    /**
     * Rewrites a reviewer's updates onto the gate's replace channels.
     *
     * <p>Needed because the completeness gate runs before the parallel fan-out,
     * and LangGraph4j re-applies pre-fork updates when it merges the branches
     * back. Against an appender that doubles every value; against a replace it
     * is a no-op. See {@link ReviewState} for the full account.
     *
     * <p>Written as a translation rather than a second copy of
     * {@link #runReviewer} so the gate and the reviewers stay literally the same
     * code - the gate is an ordinary reviewer that happens to sit earlier.
     */
    private static Map<String, Object> preFork(Map<String, Object> updates) {
        return updates.entrySet().stream().collect(Collectors.toMap(
                e -> switch (e.getKey()) {
                    case ReviewState.FINDINGS -> ReviewState.GATE_FINDINGS;
                    case ReviewState.FAILURES -> ReviewState.GATE_FAILURES;
                    case ReviewState.AUDIT -> ReviewState.GATE_AUDIT;
                    case ReviewState.TRACE -> ReviewState.GATE_TRACE;
                    default -> e.getKey();
                },
                // An appender accepts a bare value or a collection and flattens
                // either. A replace channel does not, so anything written as a
                // scalar has to be wrapped here or the reader gets an
                // AuditEntry where it expects a List of them.
                e -> e.getValue() instanceof java.util.Collection<?>
                        ? e.getValue()
                        : List.of(e.getValue())));
    }

    private boolean blocksOn(List<ReviewFinding> findings) {
        return findings.stream().anyMatch(f -> policy.blocks(f.severity()));
    }

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
            if (outcome.succeeded()) {
                return Map.of(
                        ReviewState.FINDINGS, outcome.findings(),
                        ReviewState.AUDIT, outcome.audit(),
                        ReviewState.TRACE, area);
            }
            // Recorded, never swallowed. A reviewer that could not run is the
            // evidence that this application was not fully reviewed.
            log.warn("{} failed for {}: {}", area, state.applicationId(),
                    outcome.audit().failureDetail());
            return Map.of(
                    ReviewState.FAILURES,
                    area + ": " + outcome.audit().outcome() + " - " + outcome.audit().failureDetail(),
                    ReviewState.AUDIT, outcome.audit(),
                    ReviewState.TRACE, area + " (failed)");
        } finally {
            limit.release();
        }
    }

    /**
     * Grounding first, because it is free and catches the worst failure. Only
     * grounded, serious findings cost a model call.
     */
    private Map<String, Object> verifyFindings(ReviewState state) {
        if (verifier == null) {
            return Map.of(ReviewState.TRACE, "verify (disabled)");
        }
        // A later pass exists to settle what was left open. Re-running the whole
        // set would re-pay for every finding the first pass already decided -
        // with two passes and N serious findings that is 2N model calls to learn
        // nothing new about N-of-them.
        if (state.verifyPasses() > 0) {
            return reverifyUnresolved(state);
        }

        List<ReviewFinding> verified = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        var needs = new java.util.LinkedHashSet<EvidenceNeed>();

        for (ReviewFinding finding : state.findings()) {
            GroundingCheck.Result grounding = groundingCheck.check(finding, state.context());

            if (grounding == GroundingCheck.Result.UNGROUNDED) {
                discarded.add("%s: quote not found in the cited document"
                        .formatted(finding.findingId()));
                continue;
            }
            if (grounding == GroundingCheck.Result.UNVERIFIABLE) {
                verified.add(finding.withVerdict(Verdict.survives(
                        "citation could not be verified - transcribed from a scan")));
                continue;
            }
            if (!VerifierAgent.worthChallenging(finding)) {
                verified.add(finding);
                continue;
            }

            Verdict verdict = verifier.challenge(finding, state.context(), state.evidence());
            if (verdict.needsAnotherPass() && state.verifyPasses() < MAX_VERIFY_PASSES) {
                unresolved.add(finding.findingId() + ": " + verdict.needsEvidenceFor());
                needs.addAll(verdict.needs());
            }
            verified.add(finding.withVerdict(verdict));
        }

        long refuted = verified.stream().filter(f -> !f.survives()).count();
        log.info("{}: verify pass {} - {} verified, {} refuted, {} discarded, {} unresolved",
                state.applicationId(), state.verifyPasses() + 1, verified.size(),
                refuted, discarded.size(), unresolved.size());

        Map<String, Object> updates = new HashMap<>();
        updates.put(ReviewState.VERIFIED, verified);
        updates.put(ReviewState.UNRESOLVED, unresolved);
        updates.put(ReviewState.EVIDENCE_NEEDS, List.copyOf(needs));
        updates.put(ReviewState.TRACE, "verify");
        if (!discarded.isEmpty()) {
            updates.put(ReviewState.DISCARDED, discarded);
        }
        return updates;
    }

    /**
     * A later cycle pass: only the findings still open, with what was fetched.
     *
     * <p>Everything the first pass settled is carried through untouched. Its
     * verdict did not become less true because a different finding needed a
     * lookup.
     */
    private Map<String, Object> reverifyUnresolved(ReviewState state) {
        String evidence = state.evidence();
        List<ReviewFinding> verified = new ArrayList<>();
        List<String> stillUnresolved = new ArrayList<>();
        var needs = new java.util.LinkedHashSet<EvidenceNeed>();
        int rechallenged = 0;

        for (ReviewFinding finding : state.verifiedFindings()) {
            Verdict previous = finding.verdict();
            boolean open = previous != null
                    && previous.outcome() == Verdict.Outcome.UNRESOLVED;

            if (!open) {
                verified.add(finding);
                continue;
            }

            rechallenged++;
            Verdict verdict = verifier.challenge(finding, state.context(), evidence);
            if (verdict.needsAnotherPass() && state.verifyPasses() < MAX_VERIFY_PASSES) {
                stillUnresolved.add(finding.findingId() + ": " + verdict.needsEvidenceFor());
                needs.addAll(verdict.needs());
            }
            verified.add(finding.withVerdict(verdict));
        }

        log.info("{}: verify pass {} - re-challenged {} of {}, {} still unresolved",
                state.applicationId(), state.verifyPasses() + 1, rechallenged,
                verified.size(), stillUnresolved.size());

        return Map.of(
                ReviewState.VERIFIED, verified,
                ReviewState.UNRESOLVED, stillUnresolved,
                ReviewState.EVIDENCE_NEEDS, List.copyOf(needs),
                ReviewState.TRACE, "verify (pass " + (state.verifyPasses() + 1) + ")");
    }

    /**
     * The authority gate. Plain Java, thresholds from configuration.
     *
     * <p>No model has any part in this. A vendor document cannot widen a
     * threshold, because no threshold was ever in a prompt to argue with.
     */
    private Map<String, Object> decide(ReviewState state) {
        List<ReviewFinding> surviving = state.survivingFindings();

        boolean blocking = surviving.stream().anyMatch(f -> policy.blocks(f.severity()));
        // Separate from blocking on purpose. A finding that survived an agent
        // built to destroy it is a stronger signal than its severity label
        // alone, and auto-clearing one means nobody ever sees what the review
        // actually found.
        boolean needsReview = surviving.stream().anyMatch(f -> policy.escalates(f.severity()));
        boolean conflicted = !state.conflicts().isEmpty();
        boolean incomplete = !state.allReviewersRan();
        boolean unresolved = surviving.stream().anyMatch(f -> f.verdict() != null
                && f.verdict().outcome() == Verdict.Outcome.UNRESOLVED);

        boolean human = blocking || needsReview || conflicted || incomplete || unresolved;
        String verdict = human
                ? (incomplete ? "ESCALATED_INCOMPLETE" : "ESCALATED")
                : "AUTO_CLEARED";

        log.info("{}: {} (blocking={} needsReview={} conflicted={} incomplete={} unresolved={})",
                state.applicationId(), verdict, blocking, needsReview, conflicted,
                incomplete, unresolved);

        return Map.of(
                ReviewState.NEEDS_HUMAN, human,
                ReviewState.VERDICT, verdict,
                ReviewState.TRACE, "decide");
    }

    /** Runs the pipeline. Stops at the human-review node when one is needed. */
    public ReviewState review(ReviewContext context) {
        return review(context, RunnableConfig.builder()
                .threadId(context.applicationId()).build());
    }

    /**
     * @param config carries the thread id, which is what a later resume uses to
     *               find the checkpoint
     */
    public ReviewState review(ReviewContext context, RunnableConfig config) {
        return graph.invoke(GraphInput.args(Map.of(ReviewState.CONTEXT, context)), config)
                .orElseThrow(() -> new IllegalStateException(
                        "graph produced no final state for " + context.applicationId()));
    }

    /**
     * Continues a paused review after a human has decided.
     *
     * <p>Only possible because the graph was compiled with a checkpoint saver:
     * the state was serialised when it stopped, so this picks it up rather than
     * re-running four reviewers on a decision that was made days ago.
     *
     * <p>{@code GraphInput.resume()}, not an empty argument map. They are
     * different instructions: the map is treated as the input to a <em>new</em>
     * run, which starts again from the completeness gate and pays for every
     * reviewer a second time. The only visible symptom is a slow resume and a
     * bill, which is why it is worth naming.
     */
    public Optional<ReviewState> resume(RunnableConfig config) {
        return graph.invoke(GraphInput.resume(), config);
    }

    public CompiledGraph<ReviewState> compiled() {
        return graph;
    }
}
