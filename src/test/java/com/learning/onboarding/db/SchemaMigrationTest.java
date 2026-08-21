package com.learning.onboarding.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The migrations run, and the seeded rules are the ones the reviewers need.
 *
 * <p><b>Testcontainers, not H2.</b> H2 accepts syntax Postgres rejects -
 * {@code TEXT[]} array columns in {@code compliance_rule} being the obvious
 * example here. Testing against a substitute would prove the schema works on a
 * database nobody runs.
 *
 * <p>Runs against a throwaway container, so it cannot touch the local
 * {@code vendor_onboarding} database, let alone the RAG project's.
 */
@SpringBootTest(properties = {
        // A schema test has no business loading a model client. Excluding the
        // AI autoconfiguration keeps this test about the database, and stops it
        // failing for reasons that have nothing to do with the schema.
        "spring.autoconfigure.exclude="
                + "org.springframework.ai.model.google.genai.autoconfigure.chat"
                + ".GoogleGenAiChatAutoConfiguration,"
                + "org.springframework.ai.model.chat.client.autoconfigure"
                + ".ChatClientAutoConfiguration",
        "onboarding.intake.vision.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class SchemaMigrationTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vendor_onboarding_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // The agent role is created by V3 with this password, so the agent
        // DataSource must be given the same one.
        registry.add("onboarding.agent-datasource.password", () -> "agent-local-dev-only");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("every table the reviewers need exists")
    void schemaApplies() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertTrue(tables.containsAll(List.of(
                        "required_document", "compliance_rule", "logistics_requirement",
                        "finance_threshold", "application", "application_sku",
                        "application_document", "review_finding", "finding_evidence",
                        "review_conflict", "audit_entry")),
                "missing tables, got: " + tables);
    }

    @Test
    @DisplayName("delivery model decides whether an ASN is required")
    void asnRulesDifferByDeliveryModel() {
        Boolean dc = jdbc.queryForObject(
                "SELECT asn_required FROM logistics_requirement WHERE delivery_model = 'DISTRIBUTION_CENTRE'",
                Boolean.class);
        Boolean drop = jdbc.queryForObject(
                "SELECT asn_required FROM logistics_requirement WHERE delivery_model = 'DROP_SHIP'",
                Boolean.class);

        assertEquals(Boolean.TRUE, dc, "a DC delivery without an ASN is received blind");
        assertEquals(Boolean.FALSE, drop, "nothing is received on drop ship, so no ASN applies");
    }

    @Test
    @DisplayName("accepted standards are a list, not a single value")
    void complianceRulesAllowAlternatives() {
        // The reason the column is TEXT[]: several schemes are usually
        // acceptable, and hardcoding one would reject good vendors.
        String[] standards = jdbc.queryForObject(
                """
                SELECT accepted_standards FROM compliance_rule
                WHERE product_category = 'POWER_TOOLS' AND requirement_code = 'ELECTRICAL_SAFETY'
                """,
                (rs, n) -> (String[]) rs.getArray("accepted_standards").getArray());

        assertNotNull(standards);
        assertTrue(standards.length > 1, "expected several acceptable standards");
        assertTrue(List.of(standards).contains("EN 62841"));
    }

    @Test
    @DisplayName("drop ship requires fewer documents than a DC delivery")
    void requiredDocumentsDifferByDeliveryModel() {
        Integer dc = jdbc.queryForObject(
                "SELECT count(*) FROM required_document WHERE delivery_model = 'DISTRIBUTION_CENTRE' AND mandatory",
                Integer.class);
        Integer drop = jdbc.queryForObject(
                "SELECT count(*) FROM required_document WHERE delivery_model = 'DROP_SHIP' AND mandatory",
                Integer.class);

        assertNotNull(dc);
        assertNotNull(drop);
        assertTrue(dc > drop,
                "drop ship never enters our supply chain, so it needs less paperwork");
    }
}
