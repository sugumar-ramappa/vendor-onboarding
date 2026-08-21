package com.learning.onboarding.domain;

/**
 * Which department raised a finding - one constant per team that reviews a new
 * vendor before they are allowed to ship to stores.
 *
 * <p>These are the five reviews a retailer actually runs today, in five
 * different teams, mostly in sequence because each waits on the last. That is
 * why the system is multi-agent: it models reviewers who exist, work
 * independently, and regularly reach conflicting conclusions.
 *
 * <p>Deliberately NOT part of what a model returns. The area is decided by which
 * graph node ran and stamped on afterwards. A compliance reviewer that could
 * label its own output FINANCE would corrupt the per-area measurement, and the
 * conflict detector - which works by comparing findings across areas - would be
 * comparing whatever the model felt like claiming.
 */
public enum ReviewArea {

    /**
     * Is the submission pack complete for this {@link ProductCategory}?
     *
     * <p>Cheap to check and worth checking first: chasing a missing document
     * costs two weeks of calendar time, so it is better to ask for everything
     * missing at once than to discover gaps one review at a time.
     */
    COMPLETENESS,

    /**
     * Is the vendor allowed to sell this category?
     *
     * <p>Electrical safety certification for powered goods, declared conformity
     * and product safety marking, safety data sheets per hazardous SKU, chain of
     * custody for timber, and product liability insurance covering the
     * territories we sell in.
     *
     * <p>Certificate validity, scope and issuing body all matter. <b>A valid
     * certificate for the wrong scope is the most common real failure</b> and
     * the easiest to skim past - the vendor is certified, but the certificate
     * covers hand tools and they are applying to supply cordless drills.
     */
    COMPLIANCE,

    /**
     * Do their audit reports show problems we should worry about?
     *
     * <p>Third-party audit grades, open non-conformances, whether corrective
     * actions were closed or merely promised. Judgement-heavy, so most findings
     * here are {@link CheckType#SEMANTIC}.
     */
    QUALITY,

    /**
     * Can they physically supply us the way we need to be supplied?
     *
     * <p>The most retail-specific area, and the one vendors most often fail:
     * <ul>
     *   <li><b>Can they send an ASN?</b> EDI 856 capability, and whether they
     *       can hit it before the truck arrives. A vendor who cannot means
     *       every delivery is received blind.</li>
     *   <li><b>GS1 registration.</b> GTINs registered to their own GS1 prefix,
     *       and SSCC capability for pallet labels.</li>
     *   <li><b>Case pack and pallet configuration</b> - does the case fit the
     *       shelf, does the pallet fit the racking, is there overhang.</li>
     *   <li><b>Lead times and coverage</b> against the {@link DeliveryModel}
     *       they have applied for.</li>
     * </ul>
     */
    LOGISTICS,

    /**
     * Are they stable enough to rely on?
     *
     * <p>Filing history, credit indicators, and the payment terms they are
     * asking for. A vendor who fails here can still be onboarded with different
     * terms, which is exactly the kind of trade-off the conflict detector
     * surfaces rather than resolves.
     */
    FINANCE
}
