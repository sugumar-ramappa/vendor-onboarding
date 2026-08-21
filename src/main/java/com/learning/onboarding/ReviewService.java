package com.learning.onboarding;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.graph.ReviewGraph;
import com.learning.onboarding.graph.ReviewState;
import com.learning.onboarding.persistence.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Submits an application for review and stores the result.
 *
 * <h2>Why this wraps the graph rather than living inside it</h2>
 * The idempotency check has to happen <b>before</b> the work, not after. Running
 * five reviewers, spending five model calls and several minutes, and then
 * discovering the result was already stored would make the check pointless -
 * the expensive part has already happened.
 *
 * <p>Keeping persistence out of the graph also keeps the graph testable without
 * a database, which is why every graph test runs in milliseconds with no
 * container.
 *
 * <h2>The key is derived from content</h2>
 * A caller could supply one, but deriving it from the application means a vendor
 * resubmitting an unchanged pack is recognised as the same submission even if
 * the caller forgot. Change a document and the key changes, and the review runs
 * again - which is correct, because the answer might now be different.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewGraph graph;
    private final ReviewRepository repository;

    public ReviewService(ReviewGraph graph, ReviewRepository repository) {
        this.graph = graph;
        this.repository = repository;
    }

    /**
     * @return the outcome, and whether any model calls were made
     */
    public Submission submit(ReviewContext context) {
        String key = idempotencyKey(context);
        String applicationId = context.applicationId();

        var existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            // The whole point: no graph run, no model calls, no quota spent.
            log.info("{}: identical pack already reviewed as {} - returning stored result",
                    applicationId, existing.get());
            return new Submission(existing.get(), key, false,
                    repository.findingsFor(existing.get()).size());
        }

        log.info("{}: reviewing (idempotency key {})", applicationId, key);
        ReviewState state = graph.review(context);
        repository.save(state, key);

        return new Submission(applicationId, key, true, state.findings().size());
    }

    /**
     * A stable fingerprint of everything that could change the answer.
     *
     * <p>Deliberately includes the document text: two submissions with the same
     * application id but a corrected certificate are different submissions and
     * must be reviewed again.
     *
     * <p>Deliberately excludes timestamps and the submission id, which change on
     * every retry and would make every retry look new - defeating the purpose.
     */
    static String idempotencyKey(ReviewContext context) {
        var application = context.application();
        StringBuilder material = new StringBuilder()
                .append(application.applicationId()).append('|')
                .append(application.vendorName()).append('|')
                .append(application.category()).append('|')
                .append(application.deliveryModel()).append('|')
                .append(application.requestedGoLive()).append('|');

        application.skus().forEach(sku -> material
                .append(sku.vendorSku()).append(':')
                .append(sku.gtin()).append(':')
                .append(sku.casePack()).append(':')
                .append(sku.caseWeightKg()).append(':')
                .append(sku.hazardous()).append('|'));

        context.documents().forEach(doc -> material
                .append(doc.documentId()).append(':')
                .append(doc.type()).append(':')
                .append(doc.text()).append('|'));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param reviewed false when the result came from storage rather than a run
     */
    public record Submission(String applicationId, String idempotencyKey,
                             boolean reviewed, int findingCount) {}
}
