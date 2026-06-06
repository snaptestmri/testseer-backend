-- Expand job_type check to include MANUAL (on-demand GitHub trigger) and LOCAL (filesystem trigger)
ALTER TABLE analysis_runs
    DROP CONSTRAINT IF EXISTS analysis_runs_job_type_check;

ALTER TABLE analysis_runs
    ADD CONSTRAINT analysis_runs_job_type_check
        CHECK (job_type IN ('PR', 'PUSH', 'NIGHTLY', 'MANUAL', 'LOCAL'));
