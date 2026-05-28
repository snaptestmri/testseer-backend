CREATE TABLE analysis_runs (
    job_id       VARCHAR(255) PRIMARY KEY,
    org_id       VARCHAR(100) NOT NULL,
    service_id   VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha   VARCHAR(40)  NOT NULL,
    job_type     VARCHAR(20)  NOT NULL CHECK (job_type IN ('PR', 'PUSH', 'NIGHTLY')),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'DLQ')),
    attempt      SMALLINT     NOT NULL DEFAULT 1,
    enqueued_at  TIMESTAMPTZ  NOT NULL,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_detail TEXT
);

CREATE INDEX idx_analysis_runs_service ON analysis_runs(service_id, status);
