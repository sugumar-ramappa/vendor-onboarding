package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AuditEntry;
import com.learning.onboarding.domain.ReviewFinding;

import java.io.Serializable;
import java.util.List;

/**
 * What one reviewer produced: its findings, and the record of the call that
 * produced them.
 *
 * <p>The two travel together on purpose. If the audit entry were emitted
 * separately - written to a log, or pushed to a listener - it would be possible
 * to have findings with no audit record, or an audit record whose findings were
 * dropped somewhere downstream. Returning them as one value makes that
 * impossible to get wrong.
 *
 * <p>An outcome with an empty finding list and an OK audit entry means the
 * reviewer looked and found nothing. An outcome with an empty list and a failed
 * audit entry means nobody looked. Those must never be confused, which is why
 * the audit entry is not optional.
 */
public record ReviewOutcome(List<ReviewFinding> findings, AuditEntry audit)
        implements Serializable {

    public ReviewOutcome {
        findings = findings == null ? List.of() : List.copyOf(findings);
        if (audit == null) {
            throw new IllegalArgumentException(
                    "every review must record the call that produced it - findings "
                            + "with no audit entry cannot be explained or reproduced");
        }
    }

    public boolean succeeded() {
        return audit.outcome() == AuditEntry.Outcome.OK;
    }
}
