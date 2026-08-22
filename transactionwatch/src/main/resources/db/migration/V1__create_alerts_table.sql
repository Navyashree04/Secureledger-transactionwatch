-- V1: alerts table — the fraud-alert record produced by RuleEngine.
-- One row per triggered rule (see AlertService for the rationale).

CREATE TABLE alerts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     VARCHAR(64)   NOT NULL,
    transaction_id VARCHAR(64)   NOT NULL,
    rule_name      VARCHAR(64)   NOT NULL,
    reason         VARCHAR(512)  NOT NULL,
    severity       VARCHAR(16)   NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Every admin-API filter path (by account, by recency) goes through one of these.
CREATE INDEX idx_alerts_account_id ON alerts (account_id);
CREATE INDEX idx_alerts_created_at ON alerts (created_at DESC);
CREATE INDEX idx_alerts_status ON alerts (status);
