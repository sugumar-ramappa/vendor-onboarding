package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.AuditEntry;
import com.learning.onboarding.domain.Conflict;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.domain.ReviewFinding;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
 * <h2>Why the gate writes to its own channels</h2>
 * An appender is only safe for a node that sits <em>inside or after</em> a
 * parallel fan-out. When the four reviewers fork, LangGraph4j merges each
 * branch's updates onto the state as it stood before the fork - and re-applies
 * the pre-fork updates in the process. Against an appender that is not a no-op:
 * every value written before the fork lands twice.
 *
 * <p>It is a nasty failure because both copies are identical and individually
 * valid. Nothing throws. The gate's finding is simply counted twice, and every
 * number derived from it - severity tallies, the measurement's precision - is
 * quietly wrong.
 *
 * <p>So the completeness gate, which is the one finding-producing node before
 * the fork, writes to replace channels instead. Re-applying a replace is
 * idempotent, which is the property the position in the graph actually demands.
 * {@link #findings()} and friends stitch the two halves back together, so
 * nothing downstream needs to know the split exists.
 *
 * <p>The general rule, worth stating because it also covers checkpoint replay:
 * <b>a node's channel must be idempotent under re-application unless the node
 * sits where re-application cannot happen.</b>
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
    public static final String AUDIT = "audit";
    public static final String VERIFIED = "verified";
    public static final String DISCARDED = "discarded";
    public static final String UNRESOLVED = "unresolved";
    public static final String VERIFY_PASSES = "verifyPasses";
    public static final String NEEDS_HUMAN = "needsHuman";
    public static final String VERDICT = "verdict";
    public static final String TRACE = "trace";

    // Written only by nodes that run before the parallel fan-out, and therefore
    // deliberately absent from SCHEMA so they replace rather than append.
    public static final String GATE_FINDINGS = "gateFindings";
    public static final String GATE_FAILURES = "gateFailures";
    public static final String GATE_AUDIT = "gateAudit";
    public static final String GATE_TRACE = "gateTrace";

    /** Reference data fetched mid-run, for the verifier's next pass. */
    public static final String EVIDENCE = "evidence";

    /** Which reference data the verifier asked for. Drives the fetch. */
    public static final String EVIDENCE_NEEDS = "evidenceNeeds";

    /** Declares how each key merges. Keys absent from this map replace on write. */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            FINDINGS, Channels.<ReviewFinding>appenderWithDuplicate(List::of),
            FAILURES, Channels.<String>appenderWithDuplicate(List::of),
            CONFLICTS, Channels.<Conflict>appenderWithDuplicate(List::of),
            AUDIT, Channels.<AuditEntry>appenderWithDuplicate(List::of),
            DISCARDED, Channels.<String>appenderWithDuplicate(List::of),
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

    /**
     * Every finding, gate first then reviewers.
     *
     * <p>The gate's half is read from a replace channel and the reviewers' half
     * from an appender - see the class comment. Callers get one list and never
     * have to think about it.
     */
    public List<ReviewFinding> findings() {
        return join(GATE_FINDINGS, FINDINGS);
    }

    /** Findings the completeness gate raised, before any reviewer ran. */
    public List<ReviewFinding> gateFindings() {
        return this.<List<ReviewFinding>>value(GATE_FINDINGS).orElseGet(List::of);
    }

    private <T> List<T> join(String replaceKey, String appendKey) {
        List<T> gate = this.<List<T>>value(replaceKey).orElseGet(List::of);
        List<T> rest = this.<List<T>>value(appendKey).orElseGet(List::of);
        if (gate.isEmpty()) {
            return rest;
        }
        if (rest.isEmpty()) {
            return gate;
        }
        return Stream.concat(gate.stream(), rest.stream()).toList();
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

    /**
     * Every model call made during this review, successful or not.
     *
     * <p>This is what makes a finding explainable. Given one, its callId leads
     * here, and here is the exact prompt and response.
     */
    public List<AuditEntry> audit() {
        return join(GATE_AUDIT, AUDIT);
    }

    /**
     * The gate's call only. Runs <b>before</b> the parallel fan-out.
     *
     * <p>Separated from {@link #reviewerAudit()} so latency can be reconstructed
     * along the critical path rather than by summing calls that overlapped. See
     * {@code MeasurementHarness.criticalPathMs}.
     */
    public List<AuditEntry> gateAudit() {
        return this.<List<AuditEntry>>value(GATE_AUDIT).orElseGet(List::of);
    }

    /** The fanned-out reviewers' calls only. These run concurrently. */
    public List<AuditEntry> reviewerAudit() {
        return this.<List<AuditEntry>>value(AUDIT).orElseGet(List::of);
    }

    /** Reviewers that could not run. Not the same as reviewers that found nothing. */
    public List<String> failures() {
        return join(GATE_FAILURES, FAILURES);
    }

    /**
     * Findings after verification, with their verdicts.
     *
     * <p>Falls back to the raw findings when verification has not run, so a
     * caller never sees an empty list merely because the verifier was disabled.
     */
    public List<ReviewFinding> verifiedFindings() {
        return this.<List<ReviewFinding>>value(VERIFIED).orElseGet(this::findings);
    }

    /** Findings that survived challenge - what a human actually reads. */
    public List<ReviewFinding> survivingFindings() {
        return verifiedFindings().stream().filter(ReviewFinding::survives).toList();
    }

    /**
     * Findings thrown out because their citation was not in the document.
     *
     * <p>Recorded rather than silently dropped: a reviewer producing fabricated
     * citations is a prompt problem worth knowing about, and it is invisible if
     * the findings simply disappear.
     */
    public List<String> discarded() {
        return this.<List<String>>value(DISCARDED).orElseGet(List::of);
    }

    /**
     * What the verifier said it would need in order to decide.
     *
     * <p>Specific questions, not "more information" - "whether policy PL-4471029
     * has a territorial endorsement". That specificity is what makes another
     * pass worth running rather than a guess dressed as a retry.
     *
     * <p>Replaces rather than appends: each verify pass supersedes the last, and
     * accumulating them would make the cycle look like it never converges.
     */
    public List<String> unresolvedQuestions() {
        return this.<List<String>>value(UNRESOLVED).orElseGet(List::of);
    }

    public boolean hasUnresolvedFindings() {
        return !unresolvedQuestions().isEmpty();
    }

    /** How many times the verify cycle has run. Bounds the loop. */
    public int verifyPasses() {
        return this.<Integer>value(VERIFY_PASSES).orElse(0);
    }

    /**
     * Whether this needs a person.
     *
     * <p>Set by the decide node in plain Java, never by a model.
     */
    public boolean needsHuman() {
        return this.<Boolean>value(NEEDS_HUMAN).orElse(false);
    }

    /** AUTO_CLEARED, ESCALATED, ESCALATED_INCOMPLETE or DOCUMENTS_REQUESTED. */
    public String verdict() {
        return this.<String>value(VERDICT).orElse("PENDING");
    }

    /**
     * Reference data {@code gatherMore} fetched because the verifier asked.
     *
     * <p>Empty means nothing could be fetched, which the graph reads as "another
     * pass cannot help" - a real convergence condition rather than just a
     * counter running out.
     */
    public String evidence() {
        return this.<String>value(EVIDENCE).orElse("");
    }

    public boolean hasGatheredEvidence() {
        return !evidence().isBlank();
    }

    /**
     * What the verifier asked to have fetched, from a closed set.
     *
     * <p>Replaces rather than appends: each pass supersedes the last. Empty means
     * nothing was asked for, so nothing can be fetched and another pass cannot
     * help.
     */
    public List<EvidenceNeed> evidenceNeeds() {
        return this.<List<EvidenceNeed>>value(EVIDENCE_NEEDS).orElseGet(List::of);
    }

    /** Which nodes ran, in order. Cheap observability while building. */
    public List<String> trace() {
        return join(GATE_TRACE, TRACE);
    }

    /** True when every reviewer ran. Nothing may be auto-decided otherwise. */
    public boolean allReviewersRan() {
        return failures().isEmpty();
    }

    /**
     * How many findings were never actually challenged.
     *
     * <p>Configuration 3 exists to measure the verifier, so a run where the
     * verifier could not reach the model is not a measurement of it - and until
     * this existed, such a run was indistinguishable from a successful one.
     * Eleven failed verifications were recorded as a result reporting recall
     * 13/14, higher than the un-verified configuration's 12/14, which a verifier
     * cannot cause: it removes findings, it never finds them.
     *
     * <p>{@link #allReviewersRan()} was the existing guard and it is not enough.
     * It watches the reviewers; the verifier is a different component and needed
     * its own signal.
     *
     * <p>Counted by marker rather than by a flag on the verdict because a
     * verifier failure is already recorded honestly - the finding survives and
     * says why - and that record is the thing worth reading. Adding a parallel
     * boolean would let the two disagree.
     */
    public long verifierFailures() {
        return verifiedFindings().stream()
                .filter(f -> f.verdict() != null
                        && f.verdict().reason() != null
                        && f.verdict().reason()
                                .startsWith(com.learning.onboarding.agents.VerifierAgent.NOT_CHALLENGED))
                .count();
    }
}
