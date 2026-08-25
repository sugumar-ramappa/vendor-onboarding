package com.learning.onboarding.measure;

import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Severity;

import java.util.List;

/**
 * One test application, with the defects deliberately planted in it.
 *
 * <p>Hand-written, like the golden set on the retrieval project in this
 * workspace. The value is entirely in the labelling: an automated generator can
 * produce a hundred applications, and none of them would tell you whether a
 * finding was correct.
 *
 * <h2>Clean fixtures are as important as defective ones</h2>
 * A system that reports everything as BLOCKING scores perfectly on recall and is
 * useless. Without applications where the right answer is "no findings", the
 * false-positive rate cannot be measured at all, and recall on its own is a
 * number that can be gamed by flagging more.
 *
 * @param id           e.g. "F03"
 * @param description  what this fixture is testing, in one line
 * @param clean        true when the correct answer is no findings at all
 * @param applicationJson the application and documents, as submitted
 * @param expected     the defects planted here
 */
public record Fixture(
        String id,
        String description,
        boolean clean,
        FixtureApplication applicationJson,
        List<ExpectedDefect> expected
) {

    /**
     * A defect planted in a fixture, and how to recognise a finding as having
     * caught it.
     *
     * <h2>Why matching is by keyword rather than by text</h2>
     * A model will phrase the same finding differently on different runs and
     * across prompt versions. Comparing exact strings would measure phrasing
     * stability, not detection - and would make every prompt edit look like a
     * regression.
     *
     * <p>So a finding counts as catching this defect when it comes from the
     * right reviewer, is at least as serious as expected, concerns the right SKU
     * if one is named, and mentions every required keyword.
     *
     * @param area        which reviewer should catch it
     * @param minSeverity the least serious it may be reported as and still count
     * @param sku         the SKU it concerns, or null for a vendor-level defect
     * @param mustMention words the finding must contain to count as this defect
     * @param note        why this defect is here, for whoever reads the fixture
     */
    public record ExpectedDefect(
            ReviewArea area,
            Severity minSeverity,
            String sku,
            List<String> mustMention,
            String note
    ) {

        /**
         * Was this defect DETECTED - by anyone.
         *
         * <p>Deliberately does not check the area. That check used to live here,
         * and it silently capped the single-agent baseline: its findings carry
         * whichever {@link ReviewArea} slot it was constructed with, so six of
         * the eleven planted defects could never match however well it did. A
         * baseline that cannot score above 0.45 by construction makes the
         * comparison an artefact of the harness.
         *
         * <p>Whether the RIGHT reviewer found it is a real and separate
         * question - see {@link #routedCorrectly}.
         */
        public boolean matchedBy(ReviewFinding finding) {
            if (!finding.severity().atLeast(minSeverity)) {
                return false;
            }
            if (sku != null && !sku.equals(finding.details().skuRef())) {
                return false;
            }
            String text = finding.problem().toLowerCase();
            return mustMention.stream().allMatch(word -> text.contains(word.toLowerCase()));
        }

        /**
         * Was it found by the reviewer that should have found it.
         *
         * <p>Only meaningful where the areas are real. A single agent covering
         * all five has no routing to be right or wrong about, so this is
         * reported for the multi-agent configurations and marked N/A for the
         * baseline rather than scored as a failure.
         */
        public boolean routedCorrectly(ReviewFinding finding) {
            return matchedBy(finding) && finding.area() == area;
        }
    }

    /** The submitted pack, in a shape that is pleasant to write by hand. */
    public record FixtureApplication(
            String applicationId,
            String vendorName,
            String category,
            String deliveryModel,
            String requestedGoLive,
            List<FixtureSku> skus,
            List<FixtureDocument> documents
    ) {}

    public record FixtureSku(String vendorSku, String description, String gtin,
                             int casePack, String caseWeightKg, boolean hazardous) {}

    public record FixtureDocument(String documentId, String type, String text) {}
}
