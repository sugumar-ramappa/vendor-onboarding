-- The read-only role the agents connect as.
--
-- This is the guardrail that survives everything else failing. Injection
-- scanning can be evaded, spotlighting can be argued around, a prompt can be
-- talked out of its instructions - all of those are advisory. A missing GRANT
-- is not. Even with the model fully compromised and every prompt-level control
-- bypassed, Postgres refuses the write.
--
-- Two properties worth stating plainly:
--
--   1. SELECT on REFERENCE tables only. The agent can read the rules it is
--      being asked to apply, and nothing else.
--   2. No access at all to review_finding, review_conflict, audit_entry or
--      application. An agent cannot read another vendor's application, cannot
--      see what other reviewers concluded, and cannot alter its own audit trail.
--
-- Point 2 is what enforces reviewer independence at the database level rather
-- than by convention. A compliance agent that could read the quality agent's
-- findings would anchor on them, and the whole multi-agent argument collapses.

-- Roles are cluster-level, not database-level, so this has to tolerate the role
-- already existing - a second database on the same server will re-run it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'review_agent') THEN
        CREATE ROLE review_agent LOGIN PASSWORD '${agentPassword}';
    ELSE
        ALTER ROLE review_agent LOGIN PASSWORD '${agentPassword}';
    END IF;
END
$$;

-- current_database() rather than a placeholder: the database name differs
-- between local, CI and a throwaway test container, and a hardcoded one fails
-- everywhere except the machine it was written on.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO review_agent', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO review_agent;

-- Reference data only.
GRANT SELECT ON required_document      TO review_agent;
GRANT SELECT ON compliance_rule        TO review_agent;
GRANT SELECT ON logistics_requirement  TO review_agent;
GRANT SELECT ON finance_threshold      TO review_agent;

-- Deliberately NOT granted, and the omissions are the point:
--   application, application_sku, application_document
--   review_finding, finding_evidence, review_conflict
--   audit_entry
--
-- Nor INSERT, UPDATE or DELETE on anything, including the reference tables.

-- Future tables default to no access rather than inheriting a grant. Without
-- this, a table added in V7 would be readable by the agent the moment someone
-- forgot to think about it - and the failure would be silent.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM review_agent;
