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
 * @param blockingSeverity          at or above this, a finding blocks onboarding
 * @param minCertificateValidityDays certificates must stay valid at least this long
 * @param minPublicLiability        required public liability cover, in GBP
 */
@ConfigurationProperties("onboarding.policy")
@Validated
public record PolicyProperties(

        @NotNull(message = "onboarding.policy.blocking-severity must be set")
        Severity blockingSeverity,

        @Positive(message = "onboarding.policy.min-certificate-validity-days must be > 0")
        int minCertificateValidityDays,

        @Positive(message = "onboarding.policy.min-public-liability must be > 0")
        long minPublicLiability
) {

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
}
