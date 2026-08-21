package com.learning.onboarding.domain;

/**
 * How the vendor's goods reach the shelf.
 *
 * <p>This changes what the logistics reviewer has to check, quite sharply.
 * A vendor delivering into one distribution centre needs pallet-level
 * compliance and a single appointment. The same vendor delivering directly to
 * 400 stores needs carton-level labelling, store-level ASNs, and the ability to
 * cope with 400 different goods-in doors, most of them without a forklift.
 *
 * <p>Vendors routinely underestimate direct-to-store, which is why it is a
 * declared field on the application rather than something inferred later.
 */
public enum DeliveryModel {

    /**
     * Into a distribution centre, which then breaks down to stores.
     * Pallet-level labelling, DC appointments, one ASN per load.
     */
    DISTRIBUTION_CENTRE,

    /**
     * Direct to each store.
     * Carton-level labelling, per-store ASNs, tail-lift and non-forklift
     * delivery, store receiving hours.
     */
    DIRECT_TO_STORE,

    /**
     * Vendor ships to the end customer on the retailer's behalf.
     * No physical receiving at all, so logistics checks shift to despatch
     * accuracy, carrier integration and returns handling.
     */
    DROP_SHIP
}
