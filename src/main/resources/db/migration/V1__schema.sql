-- Vendor onboarding review schema.
--
-- Two groups of tables, and the split is a security boundary, not tidiness:
--
--   REFERENCE   the rules. Read by agents through MCP tools. Never written at
--               runtime by anything.
--   OPERATIONAL applications, findings, audit. Written only by the application's
--               own connection, never by the agent connection.
--
-- V2 grants the agent role SELECT on the reference tables only. An agent that
-- is fully compromised still cannot write, and cannot read another vendor's
-- application.

-- ---------------------------------------------------------------- reference --

-- Which document types a category and delivery model require.
-- Drives the completeness reviewer.
CREATE TABLE required_document (
    id                  BIGSERIAL PRIMARY KEY,
    product_category    TEXT NOT NULL,
    delivery_model      TEXT,            -- NULL = required for every model
    document_type       TEXT NOT NULL,
    mandatory           BOOLEAN NOT NULL DEFAULT TRUE,
    note                TEXT,
    UNIQUE (product_category, delivery_model, document_type)
);

-- Which standards a category must evidence.
-- Drives the compliance reviewer. `accepted_standards` is a list because more
-- than one scheme is usually acceptable, and hardcoding one would reject
-- perfectly good vendors.
CREATE TABLE compliance_rule (
    id                  BIGSERIAL PRIMARY KEY,
    product_category    TEXT NOT NULL,
    requirement_code    TEXT NOT NULL,
    accepted_standards  TEXT[] NOT NULL,
    applies_when        TEXT,            -- e.g. 'HAZARDOUS_SKU_PRESENT'
    description         TEXT NOT NULL,
    UNIQUE (product_category, requirement_code)
);

-- What the vendor must be able to do operationally.
-- Drives the logistics reviewer. Note asn_required: a vendor who cannot send
-- an ASN means every delivery is received blind.
CREATE TABLE logistics_requirement (
    id                      BIGSERIAL PRIMARY KEY,
    delivery_model          TEXT NOT NULL UNIQUE,
    asn_required            BOOLEAN NOT NULL,
    asn_lead_hours          INT,         -- how far ahead of arrival
    gs1_registration_required BOOLEAN NOT NULL,
    pallet_labelling        BOOLEAN NOT NULL,
    carton_labelling        BOOLEAN NOT NULL,
    max_lead_time_days      INT NOT NULL,
    note                    TEXT
);

CREATE TABLE finance_threshold (
    id                  BIGSERIAL PRIMARY KEY,
    code                TEXT NOT NULL UNIQUE,
    threshold_value     NUMERIC NOT NULL,
    unit                TEXT NOT NULL,
    description         TEXT NOT NULL
);

-- -------------------------------------------------------------- operational --

CREATE TABLE application (
    application_id      TEXT PRIMARY KEY,
    vendor_name         TEXT NOT NULL,
    product_category    TEXT NOT NULL,
    delivery_model      TEXT NOT NULL,
    requested_go_live   TIMESTAMPTZ NOT NULL,
    -- Repeat submissions of the same pack must not re-run five agents and
    -- re-spend quota. See production-standards.md, section 4.
    idempotency_key     TEXT NOT NULL UNIQUE,
    status              TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_sku (
    id                  BIGSERIAL PRIMARY KEY,
    application_id      TEXT NOT NULL REFERENCES application(application_id) ON DELETE CASCADE,
    vendor_sku          TEXT NOT NULL,
    description         TEXT NOT NULL,
    gtin                TEXT,
    case_pack           INT NOT NULL,
    case_weight_kg      NUMERIC(10,3) NOT NULL,
    hazardous           BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (application_id, vendor_sku)
);

CREATE TABLE application_document (
    document_id         TEXT NOT NULL,
    application_id      TEXT NOT NULL REFERENCES application(application_id) ON DELETE CASCADE,
    document_type       TEXT NOT NULL,
    -- UNTRUSTED. A vendor wrote this. Scanned before any model sees it.
    extracted_text      TEXT NOT NULL,
    page_count          INT,
    PRIMARY KEY (application_id, document_id)
);

CREATE TABLE review_finding (
    finding_id          TEXT PRIMARY KEY,
    application_id      TEXT NOT NULL REFERENCES application(application_id) ON DELETE CASCADE,
    -- Set by which graph node ran, never by the model.
    review_area         TEXT NOT NULL,
    severity            TEXT NOT NULL,
    problem             TEXT NOT NULL,
    check_type          TEXT NOT NULL,
    confidence          NUMERIC(3,2) NOT NULL,
    sku_ref             TEXT,            -- most findings are per-SKU
    -- Provenance. prompt_version is what lets you explain, six weeks later,
    -- why a vendor was flagged in March and not now.
    call_id             UUID NOT NULL,
    prompt_version      TEXT NOT NULL,
    model_name          TEXT NOT NULL,
    called_at           TIMESTAMPTZ NOT NULL,
    -- Verification. NULL means not yet checked, which still surfaces the
    -- finding - a verifier failure must not silently suppress a real problem.
    disproved           BOOLEAN,
    verdict_reason      TEXT
);

CREATE INDEX idx_finding_application ON review_finding(application_id);
CREATE INDEX idx_finding_area        ON review_finding(application_id, review_area);

-- Citations. Kept separate because one finding cites several places, and
-- because the grounding check verifies each quote against the document.
CREATE TABLE finding_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    finding_id          TEXT NOT NULL REFERENCES review_finding(finding_id) ON DELETE CASCADE,
    document_id         TEXT NOT NULL,
    page                INT,
    quote               TEXT NOT NULL
);

CREATE INDEX idx_evidence_finding ON finding_evidence(finding_id);

-- Where two reviewers reached contradictory conclusions. Never auto-resolved:
-- the resolution is a business decision, not a factual one.
CREATE TABLE review_conflict (
    id                  BIGSERIAL PRIMARY KEY,
    application_id      TEXT NOT NULL REFERENCES application(application_id) ON DELETE CASCADE,
    conflict_type       TEXT NOT NULL,
    finding_a           TEXT NOT NULL REFERENCES review_finding(finding_id) ON DELETE CASCADE,
    finding_b           TEXT NOT NULL REFERENCES review_finding(finding_id) ON DELETE CASCADE,
    description         TEXT NOT NULL
);

-- Every model call. For an investigation tool the trail IS the product: a
-- finding a reviewer cannot verify is a finding they will not act on.
CREATE TABLE audit_entry (
    call_id             UUID PRIMARY KEY,
    application_id      TEXT REFERENCES application(application_id) ON DELETE CASCADE,
    node_name           TEXT NOT NULL,
    prompt_version      TEXT NOT NULL,
    model_name          TEXT NOT NULL,
    prompt_text         TEXT NOT NULL,
    response_text       TEXT,
    prompt_tokens       INT,
    completion_tokens   INT,
    latency_ms          BIGINT,
    outcome             TEXT NOT NULL,   -- OK, RATE_LIMITED, TIMEOUT, GUARDRAIL_BLOCKED
    failure_detail      TEXT,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_application ON audit_entry(application_id);
