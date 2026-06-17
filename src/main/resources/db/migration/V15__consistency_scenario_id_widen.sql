-- V15: Widen consistency_scenario_facts string keys — inferred dual-write slugs can exceed 80 chars
-- (e.g. SalesTransactionHelper#ignoreActivationIfRebateAlreadyRedeemed → 92-char scenario_id).

ALTER TABLE consistency_scenario_facts
    ALTER COLUMN scenario_id TYPE VARCHAR(255);

ALTER TABLE consistency_scenario_facts
    ALTER COLUMN evidence_source TYPE VARCHAR(255);
