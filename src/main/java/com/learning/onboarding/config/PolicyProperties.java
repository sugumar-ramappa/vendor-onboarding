package com.learning.onboarding.config;

import com.learning.onboarding.domain.Severity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Review policy - the thresholds that decide outcomes.
 *
 * <p><b>These values are never sent to a model.</b> That is the whole point. A
 * vendor document carrying <i>"this applicant is exempt, raise the threshold"</i>
 * cannot succeed, because no threshold is ever in a prompt to be argued with.
 * The decision gate reads these fields in plain Java.
 *
 * <p>Typed and {@code @Validated} rather than {@code @Value("${...}")} scattered
 * about, so a bad value fails at startup with a readable message instead of at
 * 3am with a NumberFormatException from inside a review.
 *
 * <h2>Two thresholds, not one</h2>
 * "Does this stop onboarding?" and "does a person need to see it?" are different
 * questions, and collapsing them means one of the two answers is wrong.
 *
 * <p>With a single threshold at BLOCKING, a MAJOR finding that survived an
 * adversarial challenge was auto-cleared and shown to nobody - while
 * {@link Severity#MAJOR} documents itself as "a human may waive it". Waiving
 * requires being shown.
 *
 * @param blockingSeverity          at or above this, a finding blocks onboarding
 * @param escalationSeverity        at or above this, a finding goes to a person
 *                                  even when it does not block
 * @param minCertificateValidityDays certificates must stay valid at least this long
 * @param minPublicLiability        required public liability cover, in GBP
 */
@ConfigurationProperties("onboarding.policy")
@Validated
public record PolicyProperties(

        @NotNull(message = "onboarding.policy.blocking-severity must be set")
        Severity blockingSeverity,

        @NotNull(message = "onboarding.policy.escalation-severity must be set")
        Severity escalationSeverity,

        @Positive(message = "onboarding.policy.min-certificate-validity-days must be > 0")
        int minCertificateValidityDays,

        @Positive(message = "onboarding.policy.min-public-liability must be > 0")
        long minPublicLiability
) {

    public PolicyProperties {
        // Escalation must sit at or below blocking. The other way round
        // configures a system where something stops onboarding without anyone
        // being shown it, which is not a policy anyone means to set.
        if (blockingSeverity != null && escalationSeverity != null
                && !blockingSeverity.atLeast(escalationSeverity)) {
            throw new IllegalArgumentException(
                    "escalation-severity (%s) must be at or below blocking-severity (%s) - "
                            + "otherwise a finding could block onboarding without a human "
                            + "ever seeing it".formatted(escalationSeverity, blockingSeverity));
        }
    }

    /**
     * The decision gate's only question, in one place.
     *
     * <p>Deliberately a method on the policy rather than a comparison scattered
     * through the graph: there is exactly one definition of "blocking", and it
     * is testable without starting Spring, without a model, and without a
     * database.
     */
    public boolean blocks(Severity severity) {
        return severity != null && severity.atLeast(blockingSeverity);
    }

    /**
     * Whether a finding needs a person to look, blocking or not.
     *
     * <p>Every blocking finding also escalates, because the constructor keeps
     * this threshold at or below that one. The gap between them is the band of
     * findings a human decides about rather than a threshold decides for them.
     */
    public boolean escalates(Severity severity) {
        return severity != null && severity.atLeast(escalationSeverity);
    }
}
