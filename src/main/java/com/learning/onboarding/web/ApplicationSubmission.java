package com.learning.onboarding.web;

import com.learning.onboarding.domain.DeliveryModel;
import com.learning.onboarding.domain.DocumentType;
import com.learning.onboarding.domain.ProductCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * The JSON half of a submission. Files arrive as separate multipart parts.
 *
 * <h2>Why the type of each document is declared rather than inferred</h2>
 * It is tempting to guess from the filename - "insurance.pdf" is obviously the
 * insurance certificate. It is obvious right up to the vendor who names theirs
 * "ACME_2026_final_v3.pdf", and then the guess is silent and wrong: the document
 * is filed as the wrong type, the completeness gate reports a missing insurance
 * certificate, and the vendor is asked for a document they already sent.
 *
 * <p>Declaring it moves that decision to whoever is uploading, who knows. An
 * undeclared file is rejected rather than guessed at.
 *
 * @param documents one entry per file part, matched by {@code filename}
 * @param skus      omit when a spreadsheet is attached as the {@code skuSheet}
 *                  part - the sheet is authoritative when both are present, and
 *                  a mismatch is reported rather than merged
 */
public record ApplicationSubmission(

        @NotBlank(message = "applicationId is required")
        String applicationId,

        @NotBlank(message = "vendorName is required")
        String vendorName,

        @NotNull(message = "category is required - it selects the compliance rules")
        ProductCategory category,

        @NotNull(message = "deliveryModel is required - it selects the logistics checks")
        DeliveryModel deliveryModel,

        @NotNull(message = "requestedGoLive is required - certificate expiry is judged against it")
        Instant requestedGoLive,

        @NotEmpty(message = "declare at least one document")
        @Valid List<DeclaredDocument> documents,

        List<SkuLine> skus
) {

    /**
     * @param filename must match the {@code filename} of an uploaded file part
     */
    public record DeclaredDocument(
            @NotBlank(message = "filename is required") String filename,
            @NotNull(message = "type is required - see DocumentType") DocumentType type) {}

    /** Only used when no spreadsheet is attached. */
    public record SkuLine(
            @NotBlank String vendorSku,
            String description,
            String gtin,
            Integer casePack,
            String caseWeightKg,
            Boolean hazardous) {}
}
