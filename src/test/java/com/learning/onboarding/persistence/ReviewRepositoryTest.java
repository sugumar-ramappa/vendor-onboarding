package com.learning.onboarding.persistence;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.*;
import com.learning.onboarding.graph.ReviewState;
import com.learning.onboarding.intake.ExtractionSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persisting a completed review, against real Postgres.
 *
 * <p>Testcontainers rather than H2, because the things worth checking here are
 * SQL-level: batch inserts, a unique constraint enforcing idempotency, and a
 * nullable boolean carrying three states rather than two.
 */
@Testcontainers
class ReviewRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vendor_onboarding")
                    .withUsername("owner")
                    .withPassword("owner");

    static JdbcTemplate jdbc;
    static ReviewRepository repository;

    @BeforeAll
    static void migrate() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("agentPassword", "agent-test"))
                .load()
                .migrate();

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        repository = new ReviewRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        // CASCADE handles findings, evidence, conflicts and audit rows.
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM audit_entry");
    }

    // ------------------------------------------------------------- fixtures --

    private static final String APP_ID = "APP-2026-0113";

    private static ReviewFinding finding(ReviewArea area, Severity severity,
                                         String sku, UUID callId) {
        var details = new AgentFinding(severity, severity + " problem in " + area,
                List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                CheckType.SEMANTIC, 0.85, sku);

        return ReviewFinding.unverified(
                area.name().toLowerCase() + "-" + callId,
                area, details,
                new FindingSource(callId, area.name().toLowerCase() + "-v1",
                        "gemini-3.6-flash", Instant.now()));
    }

    private static AuditEntry audit(UUID callId, ReviewArea area, AuditEntry.Outcome outcome) {
        return new AuditEntry(callId, APP_ID, area.name().toLowerCase(),
                area.name().toLowerCase() + "-v1", "gemini-3.6-flash",
                "SYSTEM PROMPT\n\n<untrusted>vendor text</untrusted>",
                outcome == AuditEntry.Outcome.OK ? "[MAJOR] something" : null,
                1234, outcome,
                outcome == AuditEntry.Outcome.OK ? null : "rate limited",
                Instant.now());
    }

    private static ReviewState state(List<ReviewFinding> findings, List<Conflict> conflicts,
                                     List<AuditEntry> auditEntries, List<String> failures) {
        var application = new VendorApplication(
                APP_ID, "Acme Tools Ltd",
                ProductCategory.POWER_TOOLS, DeliveryModel.DISTRIBUTION_CENTRE,
                Instant.now().plus(60, ChronoUnit.DAYS),
                List.of(new Sku("ACM-DRL-18V", "18V cordless drill", "5012345678900",
                        6, new BigDecimal("12.4"), false)),
                List.of(new SubmittedDocument("cert.pdf",
                        DocumentType.ELECTRICAL_SAFETY_CERTIFICATE,
                        "Scope: hand tools\nValid until: 12 April 2027", 1)));

        var context = new ReviewContext(application, application.documents(), Map.of(),
                Map.of("cert.pdf", ExtractionSource.NATIVE_TEXT));

        return new ReviewState(Map.of(
                ReviewState.CONTEXT, context,
                ReviewState.FINDINGS, findings,
                ReviewState.CONFLICTS, conflicts,
                ReviewState.AUDIT, auditEntries,
                ReviewState.FAILURES, failures));
    }

    // ---------------------------------------------------------------- tests --

    @Test
    @DisplayName("a review is stored with its findings, evidence and audit trail")
    void storesEverything() {
        UUID callId = UUID.randomUUID();
        var findings = List.of(finding(ReviewArea.COMPLIANCE, Severity.BLOCKING,
                "ACM-DRL-18V", callId));

        assertTrue(repository.save(
                state(findings, List.of(), List.of(audit(callId, ReviewArea.COMPLIANCE,
                        AuditEntry.Outcome.OK)), List.of()),
                "key-1"));

        assertEquals(1, count("application"));
        assertEquals(1, count("application_sku"));
        assertEquals(1, count("application_document"));
        assertEquals(1, count("review_finding"));
        assertEquals(1, count("finding_evidence"));
        assertEquals(1, count("audit_entry"));
    }

    @Test
    @DisplayName("a finding can be traced back to the exact prompt that produced it")
    void findingLeadsToItsPrompt() {
        UUID callId = UUID.randomUUID();
        repository.save(state(
                List.of(finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", callId)),
                List.of(), List.of(audit(callId, ReviewArea.COMPLIANCE, AuditEntry.Outcome.OK)),
                List.of()), "key-1");

        // This is the whole point of the audit trail: given a finding, retrieve
        // the prompt and response that produced it.
        String prompt = jdbc.queryForObject("""
                SELECT a.prompt_text
                FROM review_finding f
                JOIN audit_entry a ON a.call_id = f.call_id
                WHERE f.review_area = 'COMPLIANCE'
                """, String.class);

        assertNotNull(prompt);
        assertTrue(prompt.contains("<untrusted>"),
                "the audit must show the documents as the model actually saw them");
    }

    @Test
    @DisplayName("an unverified finding stores NULL, not false")
    void unverifiedIsNullNotFalse() {
        UUID callId = UUID.randomUUID();
        repository.save(state(
                List.of(finding(ReviewArea.COMPLIANCE, Severity.MAJOR, null, callId)),
                List.of(), List.of(audit(callId, ReviewArea.COMPLIANCE, AuditEntry.Outcome.OK)),
                List.of()), "key-1");

        Boolean disproved = jdbc.queryForObject(
                "SELECT disproved FROM review_finding", Boolean.class);

        assertNull(disproved,
                "not-yet-verified is a third state. Storing it as false would lose "
                        + "the difference between a finding that survived challenge and "
                        + "one that was never challenged");
    }

    @Test
    @DisplayName("a review with a failed reviewer is stored as INCOMPLETE")
    void failedReviewerMakesTheReviewIncomplete() {
        UUID callId = UUID.randomUUID();
        repository.save(state(
                List.of(),                                  // no findings
                List.of(),
                List.of(audit(callId, ReviewArea.COMPLIANCE, AuditEntry.Outcome.RATE_LIMITED)),
                List.of("COMPLIANCE: RATE_LIMITED")),        // but a reviewer failed
                "key-1");

        assertEquals("INCOMPLETE",
                jdbc.queryForObject("SELECT status FROM application", String.class),
                "zero findings because nobody looked is not a reviewed application");
    }

    @Test
    @DisplayName("a clean review is stored as REVIEWED")
    void cleanReviewIsComplete() {
        UUID callId = UUID.randomUUID();
        repository.save(state(List.of(), List.of(),
                List.of(audit(callId, ReviewArea.COMPLIANCE, AuditEntry.Outcome.OK)),
                List.of()), "key-1");

        assertEquals("REVIEWED",
                jdbc.queryForObject("SELECT status FROM application", String.class),
                "five reviewers finding nothing is a real result");
    }

    @Test
    @DisplayName("a repeat submission is not re-run or re-stored")
    void idempotencyKeyPreventsDuplication() {
        UUID callId = UUID.randomUUID();
        var s = state(List.of(finding(ReviewArea.COMPLIANCE, Severity.MAJOR, null, callId)),
                List.of(), List.of(audit(callId, ReviewArea.COMPLIANCE, AuditEntry.Outcome.OK)),
                List.of());

        assertTrue(repository.save(s, "same-key"));
        assertFalse(repository.save(s, "same-key"),
                "a retried HTTP submission must not re-run five reviewers and "
                        + "re-spend quota");

        assertEquals(1, count("application"));
        assertEquals(1, count("review_finding"));
    }

    @Test
    @DisplayName("conflicts are stored with both findings")
    void conflictsAreStored() {
        UUID callA = UUID.randomUUID();
        UUID callB = UUID.randomUUID();
        var a = finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", callA);
        var b = finding(ReviewArea.LOGISTICS, Severity.INFO, "ACM-DRL-18V", callB);

        repository.save(state(List.of(a, b),
                List.of(new Conflict.SkuDispute(a, b, "ACM-DRL-18V")),
                List.of(audit(callA, ReviewArea.COMPLIANCE, AuditEntry.Outcome.OK),
                        audit(callB, ReviewArea.LOGISTICS, AuditEntry.Outcome.OK)),
                List.of()), "key-1");

        var stored = jdbc.queryForMap("SELECT * FROM review_conflict");

        assertEquals("SkuDispute", stored.get("conflict_type"));
        assertEquals(a.findingId(), stored.get("finding_a"));
        assertEquals(b.findingId(), stored.get("finding_b"));
        assertTrue(stored.get("description").toString().contains("BLOCKING"));
        assertTrue(stored.get("description").toString().contains("INFO"),
                "both positions must be stored - a conflict is never resolved");
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
