package com.learning.onboarding.persistence;

import com.learning.onboarding.domain.*;
import com.learning.onboarding.graph.ReviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Writes a completed review to the database.
 *
 * <p>Uses the application's own connection, not the agents'. The agent role has
 * no INSERT anywhere, which is what makes "an agent cannot alter its own audit
 * trail" a fact rather than a policy.
 *
 * <h2>One transaction</h2>
 * Application, SKUs, documents, findings, evidence, conflicts and audit entries
 * all commit together. A partial write would produce findings citing evidence
 * rows that do not exist, or an audit trail missing the call that produced a
 * finding - and a broken audit trail is worse than none, because it is trusted.
 */
@Repository
public class ReviewRepository {

    private static final Logger log = LoggerFactory.getLogger(ReviewRepository.class);

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a finished review.
     *
     * @param idempotencyKey supplied by the caller. A repeat submission of the
     *                       same pack must not re-run five reviewers and
     *                       re-spend quota, so this is the key that lets the
     *                       previous result be returned instead.
     * @return false if this key was already stored, and nothing was written
     */
    @Transactional
    public boolean save(ReviewState state, String idempotencyKey) {
        VendorApplication application = state.context().application();

        if (findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.info("{} already stored under idempotency key {} - not rewriting",
                    application.applicationId(), idempotencyKey);
            return false;
        }

        insertApplication(application, idempotencyKey, statusFor(state));
        insertSkus(application);
        insertDocuments(application, state);
        insertFindings(application.applicationId(), state.findings());
        insertConflicts(application.applicationId(), state.conflicts());
        insertAudit(state.audit());

        log.info("stored {}: {} finding(s), {} conflict(s), {} call(s)",
                application.applicationId(), state.findings().size(),
                state.conflicts().size(), state.audit().size());
        return true;
    }

    public Optional<String> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT application_id FROM application WHERE idempotency_key = ?",
                        (rs, n) -> rs.getString(1), key)
                .stream().findFirst();
    }

    /**
     * A review where some reviewer failed is never RECORDED as complete.
     *
     * <p>This is the fail-closed rule reaching the database. Five reviewers with
     * nothing to report and four reviewers plus a rate-limited one both produce
     * zero findings; only the first is a reviewed application.
     */
    private String statusFor(ReviewState state) {
        return state.allReviewersRan() ? "REVIEWED" : "INCOMPLETE";
    }

    private void insertApplication(VendorApplication a, String idempotencyKey, String status) {
        jdbc.update("""
                INSERT INTO application (application_id, vendor_name, product_category,
                                         delivery_model, requested_go_live,
                                         idempotency_key, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                a.applicationId(), a.vendorName(), a.category().name(),
                a.deliveryModel().name(), Timestamp.from(a.requestedGoLive()),
                idempotencyKey, status);
    }

    private void insertSkus(VendorApplication a) {
        jdbc.batchUpdate("""
                INSERT INTO application_sku (application_id, vendor_sku, description,
                                             gtin, case_pack, case_weight_kg, hazardous)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                a.skus().stream().map(sku -> new Object[]{
                        a.applicationId(), sku.vendorSku(), sku.description(),
                        sku.gtin(), sku.casePack(), sku.caseWeightKg(), sku.hazardous()
                }).toList());
    }

    private void insertDocuments(VendorApplication a, ReviewState state) {
        jdbc.batchUpdate("""
                INSERT INTO application_document (application_id, document_id,
                                                  document_type, extracted_text, page_count)
                VALUES (?, ?, ?, ?, ?)
                """,
                state.context().documents().stream().map(doc -> new Object[]{
                        a.applicationId(), doc.documentId(), doc.type().name(),
                        doc.text(), doc.pageCount()
                }).toList());
    }

    private void insertFindings(String applicationId, List<ReviewFinding> findings) {
        jdbc.batchUpdate("""
                INSERT INTO review_finding (finding_id, application_id, review_area,
                                            severity, problem, check_type, confidence,
                                            sku_ref, call_id, prompt_version, model_name,
                                            called_at, disproved, verdict_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                findings.stream().map(f -> new Object[]{
                        f.findingId(), applicationId, f.area().name(),
                        f.severity().name(), f.problem(), f.details().checkType().name(),
                        f.details().confidence(), f.details().skuRef(),
                        f.source().callId(), f.source().promptVersion(),
                        f.source().modelName(), Timestamp.from(f.source().calledAt()),
                        // NULL, not false. Not-yet-verified is a third state, and
                        // storing it as "not disproved" would lose the difference
                        // between a finding that survived challenge and one that
                        // was never challenged.
                        f.verdict() == null ? null : f.verdict().disproved(),
                        f.verdict() == null ? null : f.verdict().reason()
                }).toList());

        jdbc.batchUpdate("""
                INSERT INTO finding_evidence (finding_id, document_id, page, quote)
                VALUES (?, ?, ?, ?)
                """,
                findings.stream()
                        .flatMap(f -> f.evidence().stream().map(e -> new Object[]{
                                f.findingId(), e.documentId(), e.page(), e.quote()
                        }))
                        .toList());
    }

    private void insertConflicts(String applicationId, List<Conflict> conflicts) {
        jdbc.batchUpdate("""
                INSERT INTO review_conflict (application_id, conflict_type,
                                             finding_a, finding_b, description)
                VALUES (?, ?, ?, ?, ?)
                """,
                conflicts.stream().map(c -> new Object[]{
                        applicationId,
                        c.getClass().getSimpleName(),
                        c.first().findingId(), c.second().findingId(),
                        c.subject() + ": " + c.describe()
                }).toList());
    }

    private void insertAudit(List<AuditEntry> entries) {
        jdbc.batchUpdate("""
                INSERT INTO audit_entry (call_id, application_id, node_name, prompt_version,
                                         model_name, prompt_text, response_text,
                                         prompt_tokens, completion_tokens,
                                         latency_ms, outcome, failure_detail, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entries.stream().map(e -> new Object[]{
                        e.callId(), e.applicationId(), e.nodeName(), e.promptVersion(),
                        e.modelName(), e.promptText(), e.responseText(),
                        e.promptTokens(), e.completionTokens(),
                        e.latencyMs(), e.outcome().name(), e.failureDetail(),
                        Timestamp.from(e.startedAt())
                }).toList());
    }

    /** Everything stored for one application, for the review pack and the audit API. */
    public List<StoredFinding> findingsFor(String applicationId) {
        return jdbc.query("""
                SELECT finding_id, review_area, severity, problem, check_type,
                       confidence, sku_ref, prompt_version, model_name
                FROM review_finding
                WHERE application_id = ?
                ORDER BY severity DESC, review_area
                """,
                (rs, n) -> new StoredFinding(
                        rs.getString("finding_id"),
                        ReviewArea.valueOf(rs.getString("review_area")),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("problem"),
                        CheckType.valueOf(rs.getString("check_type")),
                        rs.getDouble("confidence"),
                        rs.getString("sku_ref"),
                        rs.getString("prompt_version"),
                        rs.getString("model_name")),
                applicationId);
    }

    public record StoredFinding(String findingId, ReviewArea area, Severity severity,
                                String problem, CheckType checkType, double confidence,
                                String skuRef, String promptVersion, String modelName) {}

    /** Convenience for reporting. */
    public LocalDate goLiveDate(String applicationId) {
        return jdbc.queryForObject(
                "SELECT requested_go_live FROM application WHERE application_id = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                applicationId);
    }
}
