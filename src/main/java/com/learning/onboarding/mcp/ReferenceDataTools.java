package com.learning.onboarding.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The agents' only route to the rules.
 *
 * <p>Every method here is a lookup against reference data over the read-only
 * connection. There is deliberately no tool that reads an application, a
 * finding, or the audit trail - an agent can find out <em>what is required</em>
 * and nothing about what anyone else concluded.
 *
 * <h2>Why tools rather than putting the rules in the prompt</h2>
 * Three reasons, in order of how much they matter:
 *
 * <ol>
 *   <li><b>A model must never recall a rule from training data.</b> Ask one
 *       what certification power tools need and you get a plausible, undated,
 *       unverifiable answer that may be wrong. A row from a table is checkable -
 *       you can point at it.</li>
 *   <li><b>Rules change without touching prompts.</b> A compliance manager adds
 *       an accepted standard by inserting a row. No prompt edit, no redeploy.</li>
 *   <li><b>Prompts have a budget.</b> Pasting every rule for every category into
 *       every reviewer's context wastes tokens on rules that do not apply and
 *       buries the ones that do.</li>
 * </ol>
 *
 * <h2>Descriptions are part of the contract</h2>
 * The {@code @Tool} description is what the model sees when deciding whether to
 * call something. A vague description produces a tool that is called at the
 * wrong times or not at all, so these say what the tool returns and when it is
 * the right one - they are prompt text, not documentation.
 */
@Service
public class ReferenceDataTools {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataTools.class);

    private final JdbcTemplate jdbc;

    public ReferenceDataTools(@Qualifier(AgentDataSourceConfig.AGENT_JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- results --

    public record RequiredDocument(String documentType, boolean mandatory, String note) {}

    public record ComplianceRule(String requirementCode, List<String> acceptedStandards,
                                 String appliesWhen, String description) {}

    public record LogisticsRequirement(boolean asnRequired, Integer asnLeadHours,
                                       boolean gs1RegistrationRequired, boolean palletLabelling,
                                       boolean cartonLabelling, int maxLeadTimeDays, String note) {}

    public record FinanceThreshold(String code, String value, String unit, String description) {}

    // --------------------------------------------------------------- tools --

    @Tool(description = """
            Which documents a vendor must submit for a given product category and
            delivery model. Call this before judging whether a submission pack is
            complete. Returns mandatory and optional document types; a document
            being present says nothing about whether its contents are valid.
            """)
    public List<RequiredDocument> requiredDocuments(
            @ToolParam(description = "product category, e.g. POWER_TOOLS") String productCategory,
            @ToolParam(description = "delivery model, e.g. DISTRIBUTION_CENTRE") String deliveryModel) {

        log.debug("tool requiredDocuments({}, {})", productCategory, deliveryModel);
        // '*' rows apply to every category; NULL delivery_model to every model.
        return jdbc.query("""
                SELECT document_type, mandatory, note
                FROM required_document
                WHERE (product_category = ? OR product_category = '*')
                  AND (delivery_model IS NULL OR delivery_model = ?)
                ORDER BY mandatory DESC, document_type
                """,
                (rs, n) -> new RequiredDocument(
                        rs.getString("document_type"),
                        rs.getBoolean("mandatory"),
                        rs.getString("note")),
                productCategory, deliveryModel);
    }

    @Tool(description = """
            The compliance requirements for a product category, each with the list
            of standards that satisfy it. Several standards are usually acceptable
            for one requirement, so check membership of the list rather than
            looking for a single expected value. 'appliesWhen' names a condition
            that must hold for the rule to apply, such as HAZARDOUS_SKU_PRESENT.
            """)
    public List<ComplianceRule> complianceRules(
            @ToolParam(description = "product category, e.g. POWER_TOOLS") String productCategory) {

        log.debug("tool complianceRules({})", productCategory);
        return jdbc.query("""
                SELECT requirement_code, accepted_standards, applies_when, description
                FROM compliance_rule
                WHERE product_category = ? OR product_category = '*'
                ORDER BY requirement_code
                """,
                (rs, n) -> new ComplianceRule(
                        rs.getString("requirement_code"),
                        List.of((String[]) rs.getArray("accepted_standards").getArray()),
                        rs.getString("applies_when"),
                        rs.getString("description")),
                productCategory);
    }

    @Tool(description = """
            What a vendor must be able to do operationally for a given delivery
            model: whether an ASN is required and how far ahead of arrival, GS1
            registration, pallet and carton labelling, and the longest acceptable
            lead time. Requirements differ sharply between models - drop ship
            needs no ASN because nothing is received.
            """)
    public LogisticsRequirement logisticsRequirements(
            @ToolParam(description = "DISTRIBUTION_CENTRE, DIRECT_TO_STORE or DROP_SHIP")
            String deliveryModel) {

        log.debug("tool logisticsRequirements({})", deliveryModel);
        List<LogisticsRequirement> rows = jdbc.query("""
                SELECT asn_required, asn_lead_hours, gs1_registration_required,
                       pallet_labelling, carton_labelling, max_lead_time_days, note
                FROM logistics_requirement
                WHERE delivery_model = ?
                """,
                (rs, n) -> new LogisticsRequirement(
                        rs.getBoolean("asn_required"),
                        rs.getObject("asn_lead_hours", Integer.class),
                        rs.getBoolean("gs1_registration_required"),
                        rs.getBoolean("pallet_labelling"),
                        rs.getBoolean("carton_labelling"),
                        rs.getInt("max_lead_time_days"),
                        rs.getString("note")),
                deliveryModel);

        // Returning null rather than inventing a default: an unknown delivery
        // model means the rules are unknown, and an agent told "no requirements"
        // would conclude the vendor passes.
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Tool(description = """
            The financial and handling thresholds a vendor is measured against -
            minimum insurance cover, minimum trading history, longest payment
            terms, and the manual handling weight limit for a case. Use these
            values rather than assuming typical ones.
            """)
    public List<FinanceThreshold> financeThresholds() {
        log.debug("tool financeThresholds()");
        return jdbc.query("""
                SELECT code, threshold_value, unit, description
                FROM finance_threshold
                ORDER BY code
                """,
                (rs, n) -> new FinanceThreshold(
                        rs.getString("code"),
                        rs.getBigDecimal("threshold_value").stripTrailingZeros().toPlainString(),
                        rs.getString("unit"),
                        rs.getString("description")));
    }

}
