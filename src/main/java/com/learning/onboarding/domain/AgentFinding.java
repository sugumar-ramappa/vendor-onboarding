package com.learning.onboarding.domain;

import java.util.List;

/**
 * What a reviewer agent is allowed to return. <b>This record is the schema sent
 * to the model.</b>
 *
 * <p>Spring AI derives a JSON schema from these components and instructs the
 * model to fill them in, so the shape of this record is a specification. Adding
 * a component changes what the model is asked for; removing one removes it from
 * the model's vocabulary entirely.
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li><b>No {@code approved} or {@code decision}.</b> The model cannot express
 *       approval, so no prompt can talk it into approving anything. Whether a
 *       finding blocks onboarding is decided in Java, from thresholds in
 *       configuration.</li>
 *   <li><b>No {@code area}.</b> Which reviewer produced this is determined
 *       by which graph node ran. A compliance reviewer that could label its
 *       output FINANCE would corrupt both the per-area measurement and the
 *       conflict detector.</li>
 *   <li><b>No source.</b> Correlation ID, prompt version and model name are
 *       facts about the call, not opinions of the model. See
 *       {@link FindingSource}.</li>
 * </ul>
 *
 * <p>Those absences are the cheapest guardrail available: capability the type
 * cannot express is capability no amount of injected text can acquire. It costs
 * nothing at runtime and cannot be bypassed.
 *
 * @param severity   how serious, from a fixed enum the model must choose within
 * @param problem      one sentence stating what is wrong
 * @param evidence   citations into the submitted pack; must not be empty
 * @param checkType  arithmetic or judgement - see {@link CheckType}
 * @param confidence 0.0 to 1.0, the model's own certainty
 */
public record AgentFinding(
        Severity severity,
        String problem,
        List<Evidence> evidence,
        CheckType checkType,
        double confidence
) {

    public AgentFinding {
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        if (problem == null || problem.isBlank()) {
            throw new IllegalArgumentException("problem is required");
        }
        // The grounding rule, enforced at construction rather than left to a
        // validator someone might forget to wire in. An uncitable finding
        // cannot exist as an object.
        if (evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "a finding must cite at least one piece of evidence: " + problem);
        }
        if (checkType == null) {
            throw new IllegalArgumentException("checkType is required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got " + confidence);
        }
        evidence = List.copyOf(evidence);
    }
}
