package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.EvidenceNeed;

import java.util.List;

/**
 * Fetches the reference data a verifier said it was missing.
 *
 * <p>This is what makes the verify cycle worth having. Without it the second
 * pass asks the same question with the same information and gets the same
 * answer, and the loop is decoration.
 *
 * <h2>Why this is not in every prompt to begin with</h2>
 * It could be. The rulebook for one product category is a few hundred tokens,
 * and pasting it into all five reviewer prompts would remove the need for the
 * cycle entirely.
 *
 * <p>It is not, because that cost is paid on every review of every application,
 * and it is only needed on findings that are actually contested - a small
 * minority. Fetching it lazily, once, on the findings that need it is the same
 * trade as any other cache: pay for what you use.
 *
 * <p>The other reason is prompt budget. Five reviewers times the full rulebook
 * is real money and real latency, and a longer prompt measurably dilutes
 * attention on the documents the reviewer is meant to be reading.
 *
 * <h2>No model call, and no parsing</h2>
 * A gatherer runs SQL and nothing else. <em>What</em> to read comes from the
 * verifier as an {@link EvidenceNeed} - a value from a closed set, which either
 * binds or fails - and <em>which rows</em> comes from the application's own
 * category and delivery model. Neither is derived from free text.
 *
 * <p>The alternatives are worse in the same way: keyword-matching the verifier's
 * sentence silently fetches the wrong table on any new phrasing, and a model call
 * to route it adds a call, a failure mode, and a path where wording that
 * originated in a vendor's PDF influences which query runs.
 */
public interface EvidenceGatherer {

    /**
     * @param needs     which reference data to read, chosen by the verifier from
     *                  a closed set. Empty means fetch nothing
     * @param questions what the verifier said it needed, in prose, verbatim.
     *                  Shown to it again alongside the data; never parsed
     * @param context   the application under review
     * @return reference data as text for the verifier's prompt, or empty if
     *         there is nothing to add - which the graph reads as "another pass
     *         cannot help" and stops looping
     */
    String gather(List<EvidenceNeed> needs, List<String> questions, ReviewContext context);

    /**
     * Gathers nothing.
     *
     * <p>Used when the graph runs without a database - the measurement harness
     * and most tests. The cycle then exits on its first unresolved pass rather
     * than spinning, which is the correct behaviour: no evidence can be
     * fetched, so another pass genuinely cannot help.
     */
    EvidenceGatherer NONE = (needs, questions, context) -> "";
}
