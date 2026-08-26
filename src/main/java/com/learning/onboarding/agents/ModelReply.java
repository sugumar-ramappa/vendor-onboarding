package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AgentFinding;
import java.util.List;

/**
 * What a model call produced, and what it cost.
 *
 * <h2>Why the cost travels with the findings</h2>
 *
 * {@code review()} used to return {@code List<AgentFinding>}, so the usage
 * reported alongside the response had nowhere to go and was discarded at the
 * call site. The obvious alternative - a {@code lastUsage()} accessor on the
 * model - is wrong here for a specific reason: <b>the reviewers run
 * concurrently and share one model instance</b>, so a mutable "last call" field
 * would be read by whichever reviewer asked next rather than by the one that
 * made the call.
 *
 * Returning both together makes that race impossible to write. The cost belongs
 * to the call, so it is returned by the call.
 */
public record ModelReply(List<AgentFinding> findings, TokenUsage usage) {

    public ModelReply {
        findings = findings == null ? List.of() : List.copyOf(findings);
        usage = usage == null ? TokenUsage.unknown() : usage;
    }

    public static ModelReply of(List<AgentFinding> findings) {
        return new ModelReply(findings, TokenUsage.unknown());
    }
}
