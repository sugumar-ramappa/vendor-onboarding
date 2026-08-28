-- A cache for the verifier, which never had one.
--
-- WHY IT WAS MISSING, AND WHY THAT WAS BACKWARDS
-- ReviewerAgent has consulted review_cache since V4. VerifierAgent never did,
-- so every configuration-3 run paid for every challenge again. That is the
-- reverse of where a cache is worth most: the verifier is the most expensive
-- call in the system - it runs after four reviewers have produced findings, it
-- can loop through gatherMore and challenge the same finding twice, and it is
-- the only one that scales with how many findings were raised rather than with
-- how many fixtures there are.
--
-- The cost was not theoretical. On 2026-08-28 a re-run whose reviewers were all
-- served from cache still met the per-minute token limit and failed on two
-- fixtures, purely on verifier traffic - which meant configuration 3 could not
-- be re-scored after a metric was corrected, while configurations 1 and 2 could
-- be re-scored for free.
--
-- SEPARATE TABLE, NOT A COLUMN ON review_cache
-- The two store different things. A reviewer row holds a list of findings; a
-- verifier row holds one verdict about one finding. Sharing a table would mean
-- a nullable column that is always null for one kind of row and never null for
-- the other, and a reader having to know which kind it is holding.
CREATE TABLE verifier_cache (
    cache_key           text PRIMARY KEY,
    prompt_version      text        NOT NULL,
    model_name          text        NOT NULL,
    verdict_json        jsonb       NOT NULL,
    original_latency_ms bigint      NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_used_at        timestamptz NOT NULL DEFAULT now(),
    hit_count           integer     NOT NULL DEFAULT 0
);

-- Mirrors review_cache: the prompt version is the axis a measurement filters on
-- when asking "which rows belong to the run I am about to repeat".
CREATE INDEX idx_verifier_cache_prompt ON verifier_cache (prompt_version);

COMMENT ON TABLE verifier_cache IS
    'One row per challenged finding. The key covers the fully rendered challenge '
    'prompt, so a re-challenge carrying extra reference data from a gatherMore '
    'pass is correctly a different key and a different question.';
