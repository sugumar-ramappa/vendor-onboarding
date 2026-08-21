package com.learning.onboarding.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * A vendor asking to supply stores, and the pack of documents they submitted.
 *
 * <p>The three declared fields - category, delivery model, requested go-live -
 * are supplied by a human on the application form and are never inferred by a
 * model. They select which rules apply, so a vendor able to influence them could
 * choose their own standard: declaring HAND_TOOLS instead of POWER_TOOLS skips
 * every electrical safety requirement.
 *
 * @param applicationId  our reference, e.g. "APP-2026-0113"
 * @param vendorName     the trading name on the application
 * @param category       what they want to supply - selects the rule set
 * @param deliveryModel  how goods reach the shelf - selects the logistics checks
 * @param skus           the lines they are applying to supply
 * @param requestedGoLive when they want to start shipping; drives certificate
 *                        validity checks, since a certificate expiring before
 *                        go-live is no use even if it is valid today
 * @param documents      the submitted pack
 */
public record VendorApplication(
        String applicationId,
        String vendorName,
        ProductCategory category,
        DeliveryModel deliveryModel,
        Instant requestedGoLive,
        List<Sku> skus,
        List<SubmittedDocument> documents
) implements Serializable {

    public VendorApplication {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required");
        }
        if (vendorName == null || vendorName.isBlank()) {
            throw new IllegalArgumentException("vendorName is required");
        }
        if (category == null) {
            throw new IllegalArgumentException(
                    "category is required - it selects which rules apply");
        }
        if (deliveryModel == null) {
            throw new IllegalArgumentException(
                    "deliveryModel is required - it selects the logistics checks");
        }
        if (requestedGoLive == null) {
            throw new IllegalArgumentException("requestedGoLive is required");
        }
        if (skus == null || skus.isEmpty()) {
            throw new IllegalArgumentException(
                    "an application must list the SKUs it covers - most findings "
                            + "are per-SKU, not per-vendor");
        }
        skus = List.copyOf(skus);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /** Used by the grounding check: does this document actually exist in the pack? */
    public SubmittedDocument document(String documentId) {
        return documents.stream()
                .filter(d -> d.documentId().equals(documentId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Powered goods need electrical safety certification, battery and WEEE
     * obligations. A screwdriver does not.
     */
    public boolean isPowered() {
        return switch (category) {
            case POWER_TOOLS, ELECTRICAL, LIGHTING, GARDEN_AND_OUTDOOR -> true;
            default -> false;
        };
    }

    /**
     * True if any SKU carries a hazard classification.
     *
     * <p>Per-SKU rather than per-category, because one aerosol in an otherwise
     * ordinary range changes carrier, storage and store handling for the whole
     * delivery. A vendor supplying 40 paint brushes and 1 solvent is a hazmat
     * vendor.
     */
    public boolean hasHazardousSkus() {
        return skus.stream().anyMatch(Sku::hazardous);
    }

    /** Cases nobody at a store can lift unaided. */
    public java.util.List<Sku> overweightSkus() {
        return skus.stream().filter(Sku::exceedsManualHandlingLimit).toList();
    }
}
