package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.Evidence;
import com.learning.onboarding.domain.EvidenceNeed;
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
                if (namesReferenceData(e.documentId())) {
                    // Not fabrication - the reviewer is citing the retailer's own
                    // rulebook, which its prompt calls authoritative and supplies
                    // to it directly. It is simply not a document the VENDOR
                    // submitted, and this check only knows about those.
                    //
                    // Every "vendor value X breaks policy limit Y" finding has
                    // this shape: X comes from the pack and Y comes from the
                    // rulebook. Discarding them cost a real defect on F20 - a
                    // 28.4 kg case against a 25 kg manual handling limit, found
                    // correctly and deleted for citing the limit.
                    log.info("{} cites retailer reference data ({}), which is not part of "
                             + "the vendor pack - sending to a human rather than discarding",
                            finding.findingId(), e.documentId());
                    anyUnverifiable = true;
                    continue;
                }
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
                // Not every failed match is a fabrication. A quote whose
                // distinctive tokens - SKU codes, GTINs, figures - all appear in
                // the document in order is anchored to real content that was
                // re-rendered; a quote whose tokens are not there was invented.
                // Those two deserve opposite treatment, and until 29 Aug they
                // got the same one.
                if (document.mentionsInOrder(e.quote())) {
                    log.warn("{} cites {} with text that does not match verbatim, "
                             + "but every distinctive term appears in order - "
                             + "sending to a human rather than discarding: \"{}\"",
                            finding.findingId(), e.documentId(), truncate(e.quote()));
                    anyUnverifiable = true;
                    continue;
                }
                log.warn("{} quotes text absent from {}: \"{}\"",
                        finding.findingId(), e.documentId(), truncate(e.quote()));
                return Result.UNGROUNDED;
            }
        }
        return anyUnverifiable ? Result.UNVERIFIABLE : Result.GROUNDED;
    }

    /**
     * Does this citation name the retailer's reference data rather than a
     * vendor document?
     *
     * <p>The reviewer prompts call the rulebook authoritative and hand it over
     * as a REFERENCE DATA block, so citing it is the correct behaviour, not a
     * fabrication. It is matched by name because there is nothing else to match
     * against: at the first verify pass {@code ReviewState.evidence()} is empty
     * - only the {@code gatherMore} cycle fills it - so there is no rulebook
     * text here to check a quote against.
     *
     * <p><b>The weakness, stated rather than hidden.</b> A reviewer could evade
     * grounding entirely by citing "RULEBOOK" for an invented claim. That is a
     * real hole and it is accepted deliberately, because the finding is not
     * cleared - it is marked unverifiable and goes to a person, who sees both
     * the claim and the note that its source could not be checked. The
     * alternative was deleting correct findings outright, which is what this
     * replaced.
     *
     * <p>Closing the hole properly means grounding against the reference text
     * itself, which requires the rulebook to be in state before the first verify
     * pass rather than after it.
     */
    private static boolean namesReferenceData(String documentId) {
        if (documentId == null) {
            return false;
        }
        String id = documentId.toUpperCase().replaceAll("[^A-Z]", "");
        if (id.contains("RULEBOOK") || id.contains("REFERENCEDATA")) {
            return true;
        }
        for (EvidenceNeed need : EvidenceNeed.values()) {
            if (id.contains(need.name().replaceAll("[^A-Z]", ""))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String quote) {
        return quote.length() > 60 ? quote.substring(0, 60) + "..." : quote;
    }
}
