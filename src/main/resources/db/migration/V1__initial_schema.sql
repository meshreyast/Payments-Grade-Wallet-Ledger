CREATE TABLE users (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  email VARCHAR(320) NOT NULL UNIQUE,
  phone VARCHAR(32) NOT NULL UNIQUE,
  kyc_status VARCHAR(24) NOT NULL CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT REFERENCES users(id),
  name VARCHAR(120) NOT NULL,
  account_type VARCHAR(24) NOT NULL CHECK (account_type IN ('WALLET','CASH_IN','CASH_OUT','FEE_REVENUE')),
  currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  balance_paise BIGINT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((account_type='WALLET' AND user_id IS NOT NULL) OR (account_type<>'WALLET' AND user_id IS NULL)),
  CHECK (account_type<>'WALLET' OR balance_paise >= 0)
);
CREATE INDEX idx_accounts_user ON accounts(user_id,id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_system_account_type ON accounts(account_type) WHERE user_id IS NULL;
CREATE TABLE transfers (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_type VARCHAR(24) NOT NULL CHECK (transfer_type IN ('TOPUP','PEER','WITHDRAWAL','FEE','REVERSAL')),
  status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','COMPLETED','HELD','REJECTED','REVERSED')),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency='INR'),
  source_account_id BIGINT REFERENCES accounts(id),
  destination_account_id BIGINT REFERENCES accounts(id),
  reversal_of_transfer_id BIGINT REFERENCES transfers(id),
  note VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  CHECK (source_account_id IS NULL OR destination_account_id IS NULL OR source_account_id <> destination_account_id)
);
CREATE INDEX idx_transfers_created ON transfers(created_at DESC,id DESC);
CREATE INDEX idx_transfers_source_created ON transfers(source_account_id,created_at DESC,id DESC);
CREATE INDEX idx_transfers_destination_created ON transfers(destination_account_id,created_at DESC,id DESC);
CREATE TABLE ledger_entries (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  account_id BIGINT NOT NULL REFERENCES accounts(id),
  direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account_created ON ledger_entries(account_id,created_at DESC,id DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entries(transfer_id);
CREATE FUNCTION prevent_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ledger_entries are append-only'; END;
$$;
CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();
CREATE TABLE idempotency_keys (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  key UUID NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED')),
  response_status INTEGER,
  response_body JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id,key)
);
CREATE TABLE transfer_status_audit (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  old_status VARCHAR(24),
  new_status VARCHAR(24) NOT NULL,
  changed_by VARCHAR(160) NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason TEXT
);
CREATE TABLE risk_rules (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_code VARCHAR(80) NOT NULL UNIQUE,
  description TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  threshold_paise BIGINT,
  window_seconds INTEGER,
  action VARCHAR(20) NOT NULL CHECK (action IN ('HOLD','FLAG')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE risk_flags (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  rule_code VARCHAR(80) NOT NULL,
  explanation TEXT,
  label VARCHAR(20) CHECK (label IN ('LIKELY_OK','REVIEW','LIKELY_FRAUD')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(transfer_id,rule_code)
);
CREATE TABLE reconciliation_runs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','CLEAN','ISSUES','FAILED','SKIPPED')),
  checks_run INTEGER NOT NULL DEFAULT 0,
  issues_found INTEGER NOT NULL DEFAULT 0,
  details JSONB
);
CREATE TABLE reconciliation_issues (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES reconciliation_runs(id),
  check_code VARCHAR(80) NOT NULL,
  entity_id BIGINT,
  expected_value TEXT,
  actual_value TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_recon_issues_run ON reconciliation_issues(run_id);
CREATE TABLE job_locks (job_name VARCHAR(100) PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO job_locks(job_name) VALUES ('nightly-reconciliation');
INSERT INTO accounts(user_id,name,account_type) VALUES
(NULL,'Cash-in clearing','CASH_IN'),(NULL,'Cash-out clearing','CASH_OUT'),(NULL,'Fee revenue','FEE_REVENUE');
INSERT INTO risk_rules(rule_code,description,enabled,threshold_paise,window_seconds,action) VALUES
('LARGE_TRANSFER','Hold transfers above configured threshold',TRUE,5000000,NULL,'HOLD'),
('VELOCITY_10M','Flag high transfer count in ten minutes',TRUE,NULL,600,'FLAG');
