package com.learning.onboarding.domain;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * One line the vendor is applying to supply.
 *
 * <p>A vendor application is not "can this company supply us" - it is "can this
 * company supply these SKUs". Most of the interesting failures are per-SKU, not
 * per-vendor:
 *
 * <ul>
 *   <li>the vendor is certified, but <em>this</em> SKU is outside the
 *       certificate's scope</li>
 *   <li>the vendor has a GS1 prefix, but <em>this</em> GTIN was borrowed from
 *       their own supplier and is registered to someone else</li>
 *   <li>the case pack is fine for 11 SKUs and 30cm too deep for the twelfth</li>
 *   <li>one SKU in an otherwise ordinary range is an aerosol, which changes
 *       carrier, storage and store handling for the whole delivery</li>
 * </ul>
 *
 * <p>That is why findings cite a SKU where one applies. "Vendor is
 * non-compliant" is not actionable; "SKU 4471029 is outside the certificate
 * scope" is.
 *
 * @param vendorSku    the vendor's own code, e.g. "ACM-DRL-18V-2AH"
 * @param description  what it is, e.g. "18V cordless drill, 2Ah battery, 2-pack"
 * @param gtin         13 or 14 digit barcode number; null if not yet assigned
 * @param casePack     units per case - drives shelf fit and pallet build
 * @param caseWeightKg weight per case - drives manual-handling limits
 * @param hazardous    carries a hazard classification, e.g. aerosol, solvent
 */
public record Sku(
        String vendorSku,
        String description,
        String gtin,
        int casePack,
        BigDecimal caseWeightKg,
        boolean hazardous
) implements Serializable {

    /**
     * Above this, a case cannot be lifted by one person at a store without
     * mechanical help. A manual-handling limit, not a preference - and the
     * reason it lives here rather than in a prompt is that no vendor document
     * should be able to argue it upward.
     */
    public static final BigDecimal MANUAL_HANDLING_LIMIT_KG = new BigDecimal("25");

    public Sku {
        if (vendorSku == null || vendorSku.isBlank()) {
            throw new IllegalArgumentException("vendorSku is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        if (casePack < 1) {
            throw new IllegalArgumentException(
                    "casePack must be at least 1, got " + casePack);
        }
        if (caseWeightKg == null || caseWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("caseWeightKg must be positive");
        }
    }

    /**
     * A GTIN is 8, 12, 13 or 14 digits. Deliberately a format check only -
     * whether the number is registered to THIS vendor is a lookup the logistics
     * reviewer does through a tool, not something derivable from the digits.
     */
    public boolean hasWellFormedGtin() {
        return gtin != null && gtin.matches("\\d{8}|\\d{12}|\\d{13}|\\d{14}");
    }

    public boolean exceedsManualHandlingLimit() {
        return caseWeightKg.compareTo(MANUAL_HANDLING_LIMIT_KG) > 0;
    }
}
