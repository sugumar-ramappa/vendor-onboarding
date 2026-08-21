package com.learning.onboarding.domain;

/**
 * What the vendor wants to supply. <b>This selects which rules apply.</b>
 *
 * <p>Not decoration. A vendor supplying hand tools and a vendor supplying
 * cordless drills are held to different standards: the second is a powered
 * electrical product with battery and WEEE obligations, and none of that is
 * relevant to a screwdriver. The category chosen on the application decides
 * which rule set the compliance and logistics reviewers work against.
 *
 * <p>Declared by a human on the application, never inferred by a model. A vendor
 * who could get themselves classified as HAND_TOOLS would skip every electrical
 * safety requirement.
 */
public enum ProductCategory {

    /** Non-powered tools. Product safety marking, materials declaration. */
    HAND_TOOLS,

    /**
     * Corded and cordless power tools.
     * Electrical safety certification, battery regulations, WEEE registration.
     */
    POWER_TOOLS,

    /**
     * Timber, boards, aggregates, plaster, fixings.
     * Construction product certification, and chain-of-custody evidence for
     * timber. Also the heaviest and bulkiest, so pallet configuration matters
     * more here than anywhere else.
     */
    BUILDING_MATERIALS,

    /**
     * Cable, sockets, consumer units, switchgear.
     * Electrical safety certification and declared conformity per SKU.
     */
    ELECTRICAL,

    /**
     * Pipe, fittings, taps, cylinders.
     * Anything contacting drinking water needs water-regulations approval.
     */
    PLUMBING_AND_HEATING,

    /**
     * Paint, adhesives, solvents, fillers, aerosols.
     * Safety data sheet per SKU, hazard classification, flammable storage and
     * segregation rules, and restrictions on carrier and store handling.
     */
    PAINT_AND_CHEMICALS,

    /** Luminaires, lamps, fittings. Electrical safety plus energy labelling. */
    LIGHTING,

    /** Tiles, laminate, carpet, vinyl. Reaction-to-fire classification. */
    FLOORING,

    /**
     * Powered garden machinery, furniture, growing media.
     * Mixed: powered items carry electrical and noise obligations, and some
     * growing media and treatments are regulated separately.
     */
    GARDEN_AND_OUTDOOR,

    /** Locks, alarms, safes. Security-rating certification per SKU. */
    SECURITY_AND_IRONMONGERY,

    /** Sanitaryware, kitchen units, worktops. Bulky, damage-prone in transit. */
    KITCHEN_AND_BATHROOM,

    /** Christmas, summer, promotional. Short windows, so lead time dominates. */
    SEASONAL
}
