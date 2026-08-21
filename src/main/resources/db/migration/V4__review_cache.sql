-- Cached reviewer output, keyed on everything that could change the answer.
--
-- WHY THIS EXISTS
-- The step-8 measurement runs 30 fixtures through 4 configurations. At roughly
-- two minutes per application that is hours, and it has to be repeatable -
-- "one change per measurement" is a rule you cannot follow if every measurement
-- costs an afternoon.
--
-- With this, editing compliance-v2.txt and re-running the measurement re-runs
-- COMPLIANCE only. The other four reviewers are served from here in
-- milliseconds, because nothing about their inputs changed.
--
-- Backed by the database rather than memory on purpose: a measurement run
-- outlives a JVM, and losing the cache on restart would defeat the point.
--
-- THE KEY IS THE WHOLE CONTRACT
-- review area, prompt version, model name, and a hash of the rendered prompt -
-- which contains the application, the SKUs, the extracted facts and the
-- documents. Change any of those and the answer might legitimately differ, so
-- the key changes and the reviewer runs again.
--
-- Omitting the model name would be the dangerous mistake: swapping models and
-- silently serving the old model's findings would make a measurement compare
-- two things while reporting one.

CREATE TABLE review_cache (
    cache_key       TEXT PRIMARY KEY,

    -- Recorded separately from the key so the cache can be inspected and
    -- selectively invalidated: "drop everything from compliance-v2" is a
    -- DELETE, not a guess at which hashes to remove.
    review_area     TEXT NOT NULL,
    prompt_version  TEXT NOT NULL,
    model_name      TEXT NOT NULL,

    -- The findings, as JSON. Deliberately not normalised into review_finding:
    -- that table is the record of what a real review concluded, and mixing
    -- cached rows into it would corrupt the audit trail.
    findings_json   JSONB NOT NULL,

    -- Kept so a cache hit still records how long the original call took, which
    -- the measurement needs in order to report honest latency.
    original_latency_ms BIGINT NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    hit_count       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_review_cache_prompt ON review_cache(prompt_version);
CREATE INDEX idx_review_cache_area   ON review_cache(review_area);

-- The agent role gets no access. A cache the agents could write is a cache an
-- injected instruction could poison, and a poisoned cache serves a fabricated
-- finding to every later run of the same application.
