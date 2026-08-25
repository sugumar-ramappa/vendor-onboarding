package com.learning.onboarding.web;

import com.learning.onboarding.ReviewService;
import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.intake.IntakeException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The front door.
 *
 * <pre>
 * curl -X POST http://localhost:8080/applications \
 *   -F 'submission={"applicationId":"APP-2026-0113", ...};type=application/json' \
 *   -F 'files=@elec-cert.pdf' \
 *   -F 'files=@insurance.pdf' \
 *   -F 'skuSheet=@skus.xlsx'
 * </pre>
 *
 * <p>Thin on purpose. It converts multipart into bytes, hands off to
 * {@link IntakeService}, and translates typed failures into status codes. The
 * decisions live in the two classes it calls.
 *
 * <h2>Synchronous, and that is a real limitation</h2>
 * A review is roughly two minutes, which is a long time to hold a connection.
 * The honest production shape is to accept, return 202 with a location, and let
 * the caller poll - the graph already checkpoints, so the work surviving the
 * request is not the hard part.
 *
 * <p>It is synchronous here because a blocking call is easier to demonstrate and
 * the change is contained. Saying so is better than pretending the trade-off was
 * not made.
 */
@RestController
@RequestMapping("/applications")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private final IntakeService intake;
    private final ReviewService reviews;

    public ApplicationController(IntakeService intake, ReviewService reviews) {
        this.intake = intake;
        this.reviews = reviews;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewService.Submission> submit(
            @RequestPart("submission") @Valid ApplicationSubmission submission,
            @RequestPart("files") MultipartFile[] files,
            @RequestPart(name = "skuSheet", required = false) MultipartFile skuSheet)
            throws IOException {

        Map<String, byte[]> byName = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            byName.put(originalName(file), file.getBytes());
        }

        ReviewContext context = intake.assemble(submission, byName,
                skuSheet == null || skuSheet.isEmpty() ? null : skuSheet.getBytes());

        ReviewService.Submission result = reviews.submit(context);

        log.info("{}: {} finding(s), {}", result.applicationId(), result.findingCount(),
                result.reviewed() ? "reviewed" : "served from a previous run");

        // 200 rather than 201 when the pack was already reviewed: nothing was
        // created, and a caller retrying after a timeout should be able to tell
        // the difference between "done again" and "already done".
        return ResponseEntity
                .status(result.reviewed() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result);
    }

    /**
     * A filename is attacker-controlled text. It is used here only as a map key
     * and later only as a label in a finding's citation - never to open
     * anything - but stripping any path is worth doing at the boundary rather
     * than relying on every later use staying harmless.
     */
    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, "(unnamed)",
                    "a file part arrived with no filename, so it cannot be matched "
                            + "to a declaration");
        }
        return name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /**
     * Typed causes become status codes.
     *
     * <p>The distinction that matters is 4xx versus 5xx. An encrypted PDF or a
     * spreadsheet with no recognisable header is the vendor's to fix; a failure
     * to reach the model is ours. Returning 500 for the first sends someone to
     * read our logs about a problem in someone else's file.
     */
    @ExceptionHandler(IntakeException.class)
    public ResponseEntity<Map<String, String>> onIntakeFailure(IntakeException e) {
        log.warn("intake rejected {}: {}", e.documentId(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INTAKE_" + e.reason(),
                "file", e.documentId(),
                "detail", e.getMessage()));
    }
}
