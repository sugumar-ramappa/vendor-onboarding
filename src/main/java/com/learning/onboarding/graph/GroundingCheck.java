package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.Evidence;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.SubmittedDocument;
import com.learning.onboarding.intake.ExtractionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checks that every quoted piece of evidence actually appears in the document
 * it claims to come from.
 *
 * <p><b>No model involved.</b> This is a substring search, and doing it in Java
 * makes it exact and free - which matters, because it runs on every finding and
 * it is the check that catches the worst failure mode in the system.
 *
 * <h2>Why this is the most important guardrail</h2>
 * The worst thing this system can produce is not a missed problem. It is a
 * confident, well-written finding citing a certificate clause that does not
 * exist. A reviewer who checks two citations and finds them invented stops
 * trusting every finding the system will ever produce, including the correct
 * ones - and recovering that trust is far harder than never losing it.
 *
 * <h2>Whitespace is normalised; wording is not</h2>
 * PDF extraction inserts line breaks and double spaces that a model will not
 * reproduce character for character, and failing a genuine citation over a stray
 * newline would make the check useless. Wording is another matter: a
 * paraphrased quote is not a quote, and is treated as ungrounded.
 *
 * <h2>Transcribed documents are exempt, and flagged instead</h2>
 * A scan has no source text to match against - the "document" is a model's
 * reading of an image. Requiring a substring match there would fail every
 * finding from every scanned certificate. Those are marked as unverifiable
 * rather than passed or failed, and anything blocking built on one goes to a
 * human regardless.
 */
@Component
public class GroundingCheck {

    private static final Logger log = LoggerFactory.getLogger(GroundingCheck.class);

    /** What happened when a finding's citations were checked. */
    public enum Result {
        /** Every quote was found in the document it named. */
        GROUNDED,
        /** At least one quote could not be found. The finding is discarded. */
        UNGROUNDED,
        /**
         * The cited document was transcribed from an image, so there is no
         * source text to match against. Neither confirmed nor refuted.
         */
        UNVERIFIABLE
    }

    public Result check(ReviewFinding finding, ReviewContext context) {
        List<Evidence> evidence = finding.evidence();
        boolean anyUnverifiable = false;

        for (Evidence e : evidence) {
            SubmittedDocument document = context.application().document(e.documentId());

            if (document == null) {
                // A citation to a document that was never submitted. The most
                // clear-cut form of fabrication.
                log.warn("{} cites unknown document {}", finding.findingId(), e.documentId());
                return Result.UNGROUNDED;
            }

            if (context.sources().get(e.documentId()) == ExtractionSource.MODEL_VISION) {
                anyUnverifiable = true;
                continue;
            }

            if (!document.contains(e.quote())) {
                log.warn("{} quotes text absent from {}: \"{}\"",
                        finding.findingId(), e.documentId(),
                        e.quote().length() > 60 ? e.quote().substring(0, 60) + "..." : e.quote());
                return Result.UNGROUNDED;
            }
        }
        return anyUnverifiable ? Result.UNVERIFIABLE : Result.GROUNDED;
    }
}
