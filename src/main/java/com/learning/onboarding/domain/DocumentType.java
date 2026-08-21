package com.learning.onboarding.domain;

/**
 * What a submitted document claims to be.
 *
 * <p><b>A claim, not a fact.</b> The vendor labels their own upload, and a file
 * named "electrical-safety-cert.pdf" may be an expired certificate, a
 * certificate for a different product range, or a quotation. The completeness
 * reviewer checks that a document of each required type is present; the
 * compliance reviewer checks whether it actually says what it should.
 *
 * <p>Which types are required is driven by {@link ProductCategory} and
 * {@link DeliveryModel}, looked up through a tool rather than known by a model.
 */
public enum DocumentType {

    /** Trading name, registration number, addresses, contacts. */
    COMPANY_PROFILE,

    /** The SKU list being applied for - descriptions, GTINs, case packs. */
    PRODUCT_LIST,

    /** Third-party product safety test report. */
    PRODUCT_SAFETY_CERTIFICATE,

    /** Electrical safety certification, for powered goods. */
    ELECTRICAL_SAFETY_CERTIFICATE,

    /** Vendor's own declaration of conformity to applicable standards. */
    DECLARATION_OF_CONFORMITY,

    /** Safety data sheet - required per hazardous SKU, not per vendor. */
    SAFETY_DATA_SHEET,

    /** Chain-of-custody evidence for timber and timber-derived products. */
    TIMBER_CHAIN_OF_CUSTODY,

    /** Third-party quality management audit, with grade and non-conformances. */
    QUALITY_AUDIT_REPORT,

    /** Product and public liability cover, with limits and territories. */
    INSURANCE_CERTIFICATE,

    /**
     * Declares EDI capability: can they send an ASN, in which format, and how
     * far ahead of the delivery. The single most consequential logistics
     * document - a vendor who cannot send a usable ASN means every delivery is
     * received blind.
     */
    EDI_CAPABILITY_FORM,

    /** GS1 membership showing the company prefix their GTINs derive from. */
    GS1_REGISTRATION,

    /** Filed accounts or credit report. */
    FINANCIAL_STATEMENTS,

    /** Anything not recognised. Counts towards nothing. */
    OTHER
}
