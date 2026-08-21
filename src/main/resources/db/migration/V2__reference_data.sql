-- The rules, seeded.
--
-- These are a retailer's own supplier standards, which is what makes them
-- knowable and testable. The system does not encode law - it encodes policy,
-- and policy lives in a table where it can be read, changed and version
-- controlled.
--
-- CRITICALLY: agents read these through MCP tools. They are never recited from
-- a model's training data, because that would be undated, unverifiable, and
-- occasionally invented.

-- ------------------------------------------------- required documents -------

-- Every category needs these, whatever they sell.
INSERT INTO required_document (product_category, delivery_model, document_type, mandatory, note) VALUES
    ('*', NULL, 'COMPANY_PROFILE',        TRUE,  'Legal entity and registration'),
    ('*', NULL, 'PRODUCT_LIST',           TRUE,  'The SKUs being applied for'),
    ('*', NULL, 'INSURANCE_CERTIFICATE',  TRUE,  'Product and public liability'),
    ('*', NULL, 'FINANCIAL_STATEMENTS',   TRUE,  'Filed accounts or credit report'),
    ('*', NULL, 'QUALITY_AUDIT_REPORT',   FALSE, 'Required above a spend threshold');

-- Powered goods carry electrical obligations a screwdriver does not.
INSERT INTO required_document (product_category, delivery_model, document_type, mandatory, note) VALUES
    ('POWER_TOOLS', NULL, 'ELECTRICAL_SAFETY_CERTIFICATE', TRUE, 'Per powered SKU range'),
    ('POWER_TOOLS', NULL, 'DECLARATION_OF_CONFORMITY',     TRUE, NULL),
    ('ELECTRICAL',  NULL, 'ELECTRICAL_SAFETY_CERTIFICATE', TRUE, NULL),
    ('ELECTRICAL',  NULL, 'DECLARATION_OF_CONFORMITY',     TRUE, NULL),
    ('LIGHTING',    NULL, 'ELECTRICAL_SAFETY_CERTIFICATE', TRUE, NULL);

INSERT INTO required_document (product_category, delivery_model, document_type, mandatory, note) VALUES
    ('PAINT_AND_CHEMICALS', NULL, 'SAFETY_DATA_SHEET',       TRUE, 'One per hazardous SKU, not one per vendor'),
    ('BUILDING_MATERIALS',  NULL, 'TIMBER_CHAIN_OF_CUSTODY', TRUE, 'Timber and timber-derived only'),
    ('TOYS_AND_BABY',       NULL, 'PRODUCT_SAFETY_CERTIFICATE', TRUE, NULL);

-- Delivery model drives its own paperwork.
INSERT INTO required_document (product_category, delivery_model, document_type, mandatory, note) VALUES
    ('*', 'DISTRIBUTION_CENTRE', 'EDI_CAPABILITY_FORM', TRUE, 'ASN capability is mandatory into a DC'),
    ('*', 'DISTRIBUTION_CENTRE', 'GS1_REGISTRATION',    TRUE, 'SSCC needed for pallet labels'),
    ('*', 'DIRECT_TO_STORE',     'EDI_CAPABILITY_FORM', TRUE, 'Per-store ASNs'),
    ('*', 'DIRECT_TO_STORE',     'GS1_REGISTRATION',    TRUE, 'Carton-level GTINs'),
    -- Drop ship never enters our supply chain, so no ASN and no labelling.
    ('*', 'DROP_SHIP',           'GS1_REGISTRATION',    FALSE, 'Nothing is received, so no SSCC');

-- ---------------------------------------------------- compliance rules ------

INSERT INTO compliance_rule (product_category, requirement_code, accepted_standards, applies_when, description) VALUES
    ('POWER_TOOLS', 'ELECTRICAL_SAFETY',
        ARRAY['EN 62841','IEC 62841','EN 60745'], NULL,
        'Powered hand-held tools must be certified to a recognised electrical safety standard'),
    ('POWER_TOOLS', 'BATTERY_COMPLIANCE',
        ARRAY['IEC 62133','UN 38.3'], 'LITHIUM_BATTERY_PRESENT',
        'Lithium cells require transport and safety certification'),
    ('ELECTRICAL', 'ELECTRICAL_SAFETY',
        ARRAY['EN 60598','EN 61439','BS 7671'], NULL,
        'Fixed electrical products must evidence conformity'),
    ('LIGHTING', 'ELECTRICAL_SAFETY',
        ARRAY['EN 60598'], NULL,
        'Luminaires must be certified'),
    ('PAINT_AND_CHEMICALS', 'HAZARD_CLASSIFICATION',
        ARRAY['CLP','GHS'], 'HAZARDOUS_SKU_PRESENT',
        'Hazardous SKUs need a classified safety data sheet'),
    ('BUILDING_MATERIALS', 'TIMBER_SOURCING',
        ARRAY['FSC','PEFC'], 'TIMBER_PRESENT',
        'Timber must evidence a chain of custody'),
    ('BUILDING_MATERIALS', 'CONSTRUCTION_PRODUCTS',
        ARRAY['EN 13986','EN 13501'], NULL,
        'Structural and fire performance declarations'),
    ('PLUMBING_AND_HEATING', 'WATER_CONTACT',
        ARRAY['WRAS','KIWA','NSF 61'], 'POTABLE_WATER_CONTACT',
        'Anything touching drinking water needs water-regulations approval'),
    ('FLOORING', 'FIRE_PERFORMANCE',
        ARRAY['EN 13501-1'], NULL,
        'Reaction-to-fire classification required');

-- Applies to every category.
INSERT INTO compliance_rule (product_category, requirement_code, accepted_standards, applies_when, description) VALUES
    ('*', 'PRODUCT_LIABILITY_INSURANCE',
        ARRAY['GBP_5M_MINIMUM'], NULL,
        'Cover must be at least GBP 5,000,000 and include the territories we sell in');

-- ------------------------------------------------ logistics requirements ----

INSERT INTO logistics_requirement
    (delivery_model, asn_required, asn_lead_hours, gs1_registration_required,
     pallet_labelling, carton_labelling, max_lead_time_days, note) VALUES
    ('DISTRIBUTION_CENTRE', TRUE,  4,    TRUE,  TRUE,  FALSE, 14,
        'One ASN per load, SSCC pallet labels, booked dock appointment'),
    ('DIRECT_TO_STORE',     TRUE,  24,   TRUE,  FALSE, TRUE,  10,
        'Per-store ASNs and carton labels. Many stores have no forklift, so '
        || 'cases must be manually handleable'),
    ('DROP_SHIP',           FALSE, NULL, FALSE, FALSE, FALSE, 3,
        'Nothing is received. Checks move to carrier integration, despatch '
        || 'accuracy and returns handling');

-- ------------------------------------------------------ finance thresholds --

INSERT INTO finance_threshold (code, threshold_value, unit, description) VALUES
    ('MIN_PUBLIC_LIABILITY',      5000000, 'GBP',   'Minimum public liability cover'),
    ('MIN_PRODUCT_LIABILITY',     5000000, 'GBP',   'Minimum product liability cover'),
    ('MIN_TRADING_YEARS',               2, 'YEARS', 'Below this, additional guarantees are required'),
    ('MAX_PAYMENT_TERMS_DAYS',         90, 'DAYS',  'Longest terms we will agree'),
    ('MANUAL_HANDLING_LIMIT_KG',       25, 'KG',    'Above this a case cannot be lifted unaided at a store');
