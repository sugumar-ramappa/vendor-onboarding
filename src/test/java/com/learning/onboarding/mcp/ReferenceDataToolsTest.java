package com.learning.onboarding.mcp;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tools, and the permissions behind them.
 *
 * <p>The permission tests are the important half. Everything else in this
 * project that stops an agent doing damage is advisory - a scanner that can be
 * evaded, an instruction that can be overridden. These assert the one control
 * that cannot be argued with, so they are worth more than the query tests.
 *
 * <p>Runs Flyway by hand rather than through Spring so it can connect as two
 * different users and compare what each is allowed to do.
 */
@Testcontainers
class ReferenceDataToolsTest {

    private static final String AGENT_PASSWORD = "agent-test-password";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vendor_onboarding")
                    .withUsername("owner")
                    .withPassword("owner");

    static ReferenceDataTools tools;
    static JdbcTemplate agentJdbc;

    @BeforeAll
    static void migrateAndConnect() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("agentPassword", AGENT_PASSWORD))
                .load()
                .migrate();

        agentJdbc = new JdbcTemplate(agentDataSource());
        tools = new ReferenceDataTools(agentJdbc);
    }

    /** Connects as review_agent - the role V3 created, not the owner. */
    private static DataSource agentDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername("review_agent");
        ds.setPassword(AGENT_PASSWORD);
        ds.setMaximumPoolSize(2);
        return ds;
    }

    /**
     * Postgres says "permission denied for table X", but Spring wraps that in a
     * BadSqlGrammarException whose own message contains only the SQL. The useful
     * text is in the cause, so asserting on getMessage() alone silently passes
     * for the wrong reason.
     */
    private static void assertPermissionDenied(Executable operation, String what) {
        Exception e = assertThrows(Exception.class, operation, what);

        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().toLowerCase().contains("permission denied"),
                what + " - expected a permission error, got: " + chain);
    }

    // ------------------------------------------------------- the guardrail --

    @Test
    @DisplayName("the agent role cannot write to reference data")
    void agentCannotWriteReferenceData() {
        assertPermissionDenied(() -> agentJdbc.update(
                        "INSERT INTO compliance_rule (product_category, requirement_code, "
                                + "accepted_standards, description) VALUES ('POWER_TOOLS', 'FAKE', "
                                + "ARRAY['NONE'], 'injected')"),
                "a compromised agent must not be able to rewrite the rules it is judged against");
    }

    @Test
    @DisplayName("the agent role cannot read applications or findings")
    void agentCannotReadOperationalTables() {
        // This is what enforces reviewer independence at the database level. A
        // compliance agent that could read the quality agent's findings would
        // anchor on them, and the multi-agent argument collapses.
        for (String table : List.of("application", "review_finding", "audit_entry",
                                    "finding_evidence", "review_conflict")) {
            assertPermissionDenied(
                    () -> agentJdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class),
                    "agent must not be able to read " + table);
        }
    }

    @Test
    @DisplayName("the agent role cannot delete its own audit trail")
    void agentCannotTamperWithAudit() {
        assertPermissionDenied(() -> agentJdbc.update("DELETE FROM audit_entry"),
                "an agent must not be able to erase its own audit trail");
    }

    // ------------------------------------------------------------- lookups --

    @Test
    @DisplayName("required documents combine category rules and delivery model rules")
    void requiredDocumentsCombineRules() {
        var docs = tools.requiredDocuments("POWER_TOOLS", "DISTRIBUTION_CENTRE");
        var types = docs.stream().map(ReferenceDataTools.RequiredDocument::documentType).toList();

        assertTrue(types.contains("COMPANY_PROFILE"), "'*' rules apply to every category");
        assertTrue(types.contains("ELECTRICAL_SAFETY_CERTIFICATE"), "powered goods rule");
        assertTrue(types.contains("EDI_CAPABILITY_FORM"), "DC delivery needs an ASN");
    }

    @Test
    @DisplayName("drop ship requires fewer documents than a DC delivery")
    void deliveryModelChangesRequirements() {
        long dc = tools.requiredDocuments("HAND_TOOLS", "DISTRIBUTION_CENTRE")
                .stream().filter(ReferenceDataTools.RequiredDocument::mandatory).count();
        long drop = tools.requiredDocuments("HAND_TOOLS", "DROP_SHIP")
                .stream().filter(ReferenceDataTools.RequiredDocument::mandatory).count();

        assertTrue(dc > drop, "nothing is received on drop ship, so it needs less paperwork");
    }

    @Test
    @DisplayName("a requirement offers several acceptable standards")
    void complianceRulesListAlternatives() {
        var rule = tools.complianceRules("POWER_TOOLS").stream()
                .filter(r -> r.requirementCode().equals("ELECTRICAL_SAFETY"))
                .findFirst().orElseThrow();

        assertTrue(rule.acceptedStandards().size() > 1,
                "hardcoding a single standard would reject perfectly good vendors");
        assertTrue(rule.acceptedStandards().contains("EN 62841"));
    }

    @Test
    @DisplayName("ASN rules differ by delivery model")
    void logisticsRequirementsDifferByModel() {
        var dc = tools.logisticsRequirements("DISTRIBUTION_CENTRE");
        var drop = tools.logisticsRequirements("DROP_SHIP");

        assertTrue(dc.asnRequired(), "a DC delivery without an ASN is received blind");
        assertEquals(4, dc.asnLeadHours());
        assertTrue(dc.palletLabelling());

        assertFalse(drop.asnRequired(), "nothing is received on drop ship");
        assertNull(drop.asnLeadHours());
    }

    @Test
    @DisplayName("direct to store needs more ASN notice than a DC")
    void directToStoreNeedsMoreNotice() {
        assertTrue(tools.logisticsRequirements("DIRECT_TO_STORE").asnLeadHours()
                        > tools.logisticsRequirements("DISTRIBUTION_CENTRE").asnLeadHours(),
                "400 stores need planning that one dock does not");
    }

    @Test
    @DisplayName("an unknown delivery model returns nothing, not a default")
    void unknownModelReturnsNull() {
        assertNull(tools.logisticsRequirements("TELEPORTATION"),
                "an agent told 'no requirements' would conclude the vendor passes");
    }

    @Test
    @DisplayName("finance thresholds are values, not assumptions")
    void financeThresholdsAreReadable() {
        var codes = tools.financeThresholds().stream()
                .map(ReferenceDataTools.FinanceThreshold::code).toList();

        assertTrue(codes.contains("MIN_PUBLIC_LIABILITY"));
        assertTrue(codes.contains("MANUAL_HANDLING_LIMIT_KG"));
    }
}
