package com.learning.onboarding.agents;

import com.learning.onboarding.domain.Evidence;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Severity;
import com.learning.onboarding.domain.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tries to prove a finding wrong.
 *
 * <p>A separate agent with its own context and its own prompt. That separation
 * is the point: a model asked to check its own work agrees with itself, because
 * it is being asked to evaluate reasoning it just produced. One that did not
 * write the finding has no such attachment.
 *
 * <p>This is the biggest single lever on false positives, and false positives
 * are what decide whether a review tool gets used or muted.
 *
 * <h2>The default when unsure is that the finding survives</h2>
 * Worth stating plainly, because it is the opposite of the usual advice. Most
 * adversarial-verification guidance says to default to refuted when uncertain,
 * on the basis that a false positive is the expensive failure.
 *
 * <p>Here it is inverted. A dropped finding means a non-compliant vendor is
 * approved and ships product to stores. A surviving false positive means a human
 * spends five minutes disagreeing. The asymmetry is enormous and it runs the
 * other way, so an uncertain challenge leaves the finding standing.
 *
 * <h2>Only serious findings are challenged</h2>
 * Every verification is a model call. Challenging an INFO finding costs the same
 * as challenging a BLOCKING one and changes nothing - nobody is going to reject
 * a vendor over it. {@link #WORTH_CHALLENGING} is where that line sits, and it
 * is a cost decision as much as a design one.
 */
public class VerifierAgent {

    private static final Logger log = LoggerFactory.getLogger(VerifierAgent.class);

    /**
     * Findings at or above this severity get challenged.
     *
     * <p>MAJOR, so BLOCKING and MAJOR are verified and MINOR and INFO are not.
     * Raising it to BLOCKING would halve the verifier's cost and lose the
     * false-positive reduction on MAJOR findings, which are the ones most likely
     * to be argued about.
     */
    public static final Severity WORTH_CHALLENGING = Severity.MAJOR;

    private final String promptVersion;
    private final PromptLibrary prompts;
    private final VerifierModel model;

    public VerifierAgent(String promptVersion, PromptLibrary prompts, VerifierModel model) {
        this.promptVersion = promptVersion;
        this.prompts = prompts;
        this.model = model;
        prompts.get(promptVersion);   // fail at construction, not at first use
    }

    public String promptVersion() {
        return promptVersion;
    }

    public static boolean worthChallenging(ReviewFinding finding) {
        return finding.severity().atLeast(WORTH_CHALLENGING);
    }

    /**
     * @return the verdict, or a surviving verdict if the challenge could not run
     */
    public Verdict challenge(ReviewFinding finding, ReviewContext context) {
        String system = prompts.get(promptVersion);
        String user = """
                THE FINDING YOU ARE CHALLENGING

                  raised by: %s reviewer
                  severity:  %s
                  claim:     %s
                  %s
                  evidence:
                %s

                THE APPLICATION AND ITS DOCUMENTS

                %s
                """.formatted(
                finding.area(),
                finding.severity(),
                finding.problem(),
                finding.details().isSkuSpecific()
                        ? "SKU:       " + finding.details().skuRef() : "",
                renderEvidence(finding.evidence()),
                context.render());

        try {
            return model.challenge(system, user);

        } catch (RuntimeException e) {
            // A verifier that could not run must not silently drop the finding.
            // The finding survives, and the reason says why - so a human reading
            // the review can see the challenge did not happen rather than
            // assuming it passed.
            log.warn("verification failed for {}: {}", finding.findingId(), e.getMessage());
            return Verdict.survives(
                    "not challenged - the verifier could not run (" + e.getMessage() + ")");
        }
    }

    private static String renderEvidence(List<Evidence> evidence) {
        StringBuilder sb = new StringBuilder();
        evidence.forEach(e -> sb
                .append("    ").append(e.describe())
                .append(" : \"").append(e.quote().replaceAll("\\s+", " ")).append("\"\n"));
        return sb.toString();
    }

    /** The one model call a verifier makes. Separate so it can be stubbed. */
    public interface VerifierModel {
        Verdict challenge(String systemPrompt, String userPrompt);

        String modelName();
    }
}
