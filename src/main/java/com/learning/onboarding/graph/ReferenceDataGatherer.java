package com.learning.onboarding.graph;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.mcp.ReferenceDataTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The real gatherer: the rulebook slice for one application, read over the
 * agents' read-only connection.
 *
 * <p>One query per {@link EvidenceNeed} the verifier named, and no model call.
 * <em>Which</em> data is chosen by the verifier from a closed set; <em>which
 * rows</em> follow from the application's own category and delivery model - facts
 * we hold, not facts a document asserts. So a vendor cannot steer the lookup by
 * writing something suggestive in a PDF.
 *
 * <p>The verifier's prose questions are passed through verbatim and never
 * parsed. They are shown to it again alongside the data so it can see what it
 * asked for, but nothing here branches on their content: free text from a model
 * deciding which SQL runs is a control path worth not building.
 */
@Component
public class ReferenceDataGatherer implements EvidenceGatherer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataGatherer.class);

    private final ReferenceDataTools reference;

    public ReferenceDataGatherer(ReferenceDataTools reference) {
        this.reference = reference;
    }

    @Override
    public String gather(List<EvidenceNeed> needs, List<String> questions, ReviewContext context) {
        // Nothing named, nothing to read. Returning early rather than defaulting
        // to "fetch everything" is the point of the enum: an unasked-for query
        // is a query whose result nobody can explain.
        if (needs == null || needs.isEmpty()) {
            return "";
        }
        var application = context.application();
        String category = application.category().name();
        String delivery = application.deliveryModel().name();

        var sb = new StringBuilder();
        sb.append("""
                REFERENCE DATA, FETCHED BECAUSE YOU ASKED FOR IT

                  This is the retailer's own rulebook, read from the reference
                  database. It is authoritative. It did not come from the vendor.

                """);

        sb.append("  You said you needed:\n");
        questions.forEach(q -> sb.append("    - ").append(q).append('\n'));
        sb.append('\n');

        try {
            // Only what was asked for. A verifier that wanted the accepted
            // standards does not get the finance thresholds as well - the extra
            // rows cost tokens and give it more to be distracted by.
            for (EvidenceNeed need : needs.stream().distinct().toList()) {
                switch (need) {
                    // The condition is rendered INSIDE the requirement, not after
                    // it. "mandatory - Timber and timber-derived only" reads as a
                    // rule plus a remark, and a reviewer acts on the rule; the
                    // completeness gate blocked a vendor selling steel screws for a
                    // missing timber certificate while quoting that very remark as
                    // its evidence. "mandatory when any SKU is a timber product"
                    // is one statement that cannot be half-applied.
                    //
                    // Appended only when a condition exists, so unconditional rows
                    // render exactly as before. That keeps the review cache valid
                    // for every category whose rulebook did not change - which on a
                    // free tier is the difference between re-measuring one fixture
                    // and re-measuring all of them.
                    case REQUIRED_DOCUMENTS -> appendSection(sb, "REQUIRED DOCUMENTS",
                            reference.requiredDocuments(category, delivery).stream()
                                    .map(d -> "%s (%s%s)%s".formatted(d.documentType(),
                                            d.mandatory() ? "mandatory" : "optional",
                                            d.appliesWhen() == null || d.appliesWhen().isBlank()
                                                    ? "" : " when " + d.appliesWhen(),
                                            d.note() == null || d.note().isBlank()
                                                    ? "" : " - " + d.note()))
                                    .toList());

                    case COMPLIANCE_RULES -> appendSection(sb, "COMPLIANCE RULES",
                            reference.complianceRules(category).stream()
                                    .map(r -> "%s: accepts %s%s".formatted(r.requirementCode(),
                                            String.join(" or ", r.acceptedStandards()),
                                            r.appliesWhen() == null || r.appliesWhen().isBlank()
                                                    ? "" : " when " + r.appliesWhen()))
                                    .toList());

                    case LOGISTICS_REQUIREMENTS -> {
                        var logistics = reference.logisticsRequirements(delivery);
                        appendSection(sb, "LOGISTICS REQUIREMENTS",
                                logistics == null ? List.of() : List.of(
                                        "ASN required: " + logistics.asnRequired(),
                                        "ASN lead hours: " + logistics.asnLeadHours(),
                                        "GS1 registration required: "
                                                + logistics.gs1RegistrationRequired(),
                                        "max lead time days: " + logistics.maxLeadTimeDays()));
                    }

                    case FINANCE_THRESHOLDS -> appendSection(sb, "FINANCE THRESHOLDS",
                            reference.financeThresholds().stream()
                                    .map(t -> "%s: %s %s - %s".formatted(t.code(), t.value(),
                                            t.unit(), t.description()))
                                    .toList());
                }
            }

        } catch (RuntimeException e) {
            // A gatherer that cannot read is not a reason to lose the findings.
            // Returning empty tells the graph another pass cannot help, so it
            // stops looping and sends the unresolved findings to a human - which
            // is what would have happened without the cycle at all.
            log.warn("could not gather reference data for {}: {}",
                    context.applicationId(), e.getMessage());
            return "";
        }

        log.info("{}: gathered {} for {} question(s), {} chars",
                context.applicationId(), needs, questions.size(), sb.length());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("  ").append(title).append('\n');
        lines.forEach(l -> sb.append("    - ").append(l).append('\n'));
        sb.append('\n');
    }
}
