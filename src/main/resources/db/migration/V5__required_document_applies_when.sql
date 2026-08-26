-- Conditional document requirements: give the condition a column.
--
-- WHY THIS MIGRATION EXISTS
-- required_document had `mandatory boolean` and a free-text `note`. Three of its
-- rows are conditionally mandatory, and every one of those conditions was living
-- in the note:
--
--   TIMBER_CHAIN_OF_CUSTODY  mandatory=true   'Timber and timber-derived only'
--   SAFETY_DATA_SHEET        mandatory=true   'One per hazardous SKU, not one per vendor'
--   QUALITY_AUDIT_REPORT     mandatory=false  'Required above a spend threshold'
--
-- A reviewer reads `mandatory = true` as a hard rule and the note as commentary,
-- which is exactly what the schema tells it to do. So the completeness gate looked
-- at a vendor selling steel wood screws, found no timber certificate, and blocked
-- the application BLOCKING at confidence 1.0 - quoting the very text that refutes
-- it, 'Timber and timber-derived only', as its evidence.
--
-- The model was not wrong. The rulebook was.
--
-- WHY IT COST TWICE
-- The completeness check is a GATE: an incomplete pack skips the four substantive
-- reviews and goes back to the vendor for documents. So one false 'incomplete'
-- produced a false positive AND a missed defect, because the reviewers that would
-- have caught the real problem - a vendor unable to send an ASN into a
-- distribution centre - never ran. That single row is the entire gap between the
-- single-agent baseline's recall of 1.00 and the multi-agent configuration's 0.80.
--
-- WHY A COLUMN AND NOT A BETTER PROMPT
-- compliance_rule already has applies_when, and the gatherer renders it as
-- 'accepts X when Y'. The same information was expressible for compliance and not
-- for required documents, purely because this table was written first. Prompt
-- wording cannot fix a rulebook that states an unconditional rule; it can only
-- ask the model to second-guess data it was told is authoritative.

ALTER TABLE required_document
    ADD COLUMN applies_when text;

COMMENT ON COLUMN required_document.applies_when IS
    'Condition under which `mandatory` holds. NULL means unconditional. Rendered '
    'into the reviewer prompt as "mandatory when <condition>", so it must read as '
    'a checkable statement about the application, not as prose.';

-- The three conditional rows.
--
-- Format is the condition CODE followed by what it means. The code keeps the
-- vocabulary identical to compliance_rule.applies_when, which already uses
-- TIMBER_PRESENT and HAZARDOUS_SKU_PRESENT - two tables naming the same condition
-- two different ways would be its own trap. The gloss is there because a bare code
-- is what this bug was made of: the gate had the condition in front of it as
-- 'Timber and timber-derived only' and acted on `mandatory` anyway. A condition a
-- reviewer cannot evaluate against the application is not a condition.
UPDATE required_document
   SET applies_when = 'TIMBER_PRESENT - any SKU is a timber or timber-derived product'
 WHERE document_type = 'TIMBER_CHAIN_OF_CUSTODY';

UPDATE required_document
   SET applies_when = 'HAZARDOUS_SKU_PRESENT - any SKU is marked hazardous'
 WHERE document_type = 'SAFETY_DATA_SHEET';

UPDATE required_document
   SET applies_when = 'ABOVE_AUDIT_SPEND_THRESHOLD - the vendor is above the spend '
                      'level at which an audit report is required'
 WHERE document_type = 'QUALITY_AUDIT_REPORT';

-- Leaves every other row NULL, and that is deliberate rather than lazy: the
-- gatherer renders the condition only when one is present, so an unconditional
-- rule produces byte-identical prompt text to before this migration. That keeps
-- the review cache valid for every fixture whose rulebook did not change, which
-- is what makes re-measuring affordable on a free tier.
