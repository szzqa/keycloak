CREATE TABLE IF NOT EXISTS platform_user (
 id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(255) NOT NULL,
 display_name VARCHAR(80) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(issuer,subject)
);
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE platform_application (
 id VARCHAR(80) PRIMARY KEY, name VARCHAR(120) NOT NULL, launch_url VARCHAR(1024) NOT NULL,
 client_id VARCHAR(191) NOT NULL UNIQUE, service_client_id VARCHAR(191) NOT NULL UNIQUE,
 audience VARCHAR(191) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true,
 consumer_allowed BOOLEAN NOT NULL DEFAULT true, migration_ready BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE platform_app_account (
 app_id VARCHAR(80) NOT NULL REFERENCES platform_application(id),
 user_id UUID NOT NULL REFERENCES platform_user(id), local_user_id VARCHAR(191) NOT NULL,
 migration_batch VARCHAR(191), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(app_id,user_id), UNIQUE(app_id,local_user_id)
);
CREATE TABLE platform_membership (
 user_id UUID PRIMARY KEY REFERENCES platform_user(id), org_id VARCHAR(191) NOT NULL,
 provider VARCHAR(80) NOT NULL, external_subject VARCHAR(255) NOT NULL,
 enabled BOOLEAN NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(provider,org_id,external_subject)
);
CREATE TABLE platform_wallet (
 user_id UUID PRIMARY KEY REFERENCES platform_user(id),
 policy VARCHAR(16) NOT NULL CHECK(policy IN ('EMPLOYEE','CONSUMER')),
 enabled BOOLEAN NOT NULL DEFAULT true, epoch INTEGER NOT NULL DEFAULT 1 CHECK(epoch > 0),
 daily_credits BIGINT NOT NULL DEFAULT 0 CHECK(daily_credits >= 0),
 initial_credits BIGINT NOT NULL DEFAULT 0 CHECK(initial_credits >= 0),
 reset_time TIME NOT NULL DEFAULT '00:00:00',
 request_enabled BOOLEAN NOT NULL DEFAULT false,
 max_request BIGINT NOT NULL DEFAULT 0 CHECK(max_request >= 0),
 max_daily_request BIGINT NOT NULL DEFAULT 0 CHECK(max_daily_request >= 0),
 immediate_count INTEGER NOT NULL DEFAULT 0 CHECK(immediate_count >= 0),
 delayed_count INTEGER NOT NULL DEFAULT 0 CHECK(delayed_count >= 0),
 delay_minutes INTEGER NOT NULL DEFAULT 0 CHECK(delay_minutes >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE platform_wallet_bucket (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES platform_wallet(user_id),
 epoch INTEGER NOT NULL, period VARCHAR(32) NOT NULL, expires_at TIMESTAMPTZ,
 balance BIGINT NOT NULL CHECK(balance >= 0), reserved BIGINT NOT NULL DEFAULT 0,
 CHECK(reserved >= 0 AND reserved <= balance), UNIQUE(user_id,epoch,period)
);
CREATE TABLE platform_wallet_operation (
 app_id VARCHAR(80) NOT NULL REFERENCES platform_application(id), operation_id VARCHAR(191) NOT NULL,
 user_id UUID NOT NULL REFERENCES platform_user(id), bucket_id UUID NOT NULL REFERENCES platform_wallet_bucket(id),
 mode VARCHAR(8) NOT NULL CHECK(mode IN ('FIXED','TOKEN')),
 request_digest CHAR(64) NOT NULL, context TEXT NOT NULL,
 amount BIGINT NOT NULL CHECK(amount >= 0),
 status VARCHAR(16) NOT NULL CHECK(status IN ('RESERVED','SETTLED','RELEASED')),
 due BIGINT NOT NULL DEFAULT 0 CHECK(due >= 0), charged BIGINT NOT NULL DEFAULT 0 CHECK(charged >= 0),
 uncollected BIGINT NOT NULL DEFAULT 0 CHECK(uncollected >= 0), evidence TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(app_id,operation_id)
);
CREATE INDEX platform_pending_operations ON platform_wallet_operation(user_id,status);
CREATE TABLE platform_wallet_ledger (
 sequence BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES platform_user(id),
 bucket_id UUID NOT NULL REFERENCES platform_wallet_bucket(id), event_key VARCHAR(512) NOT NULL,
 kind VARCHAR(32) NOT NULL, app_id VARCHAR(80), operation_id VARCHAR(191),
 amount BIGINT NOT NULL, reserved_delta BIGINT NOT NULL,
 balance_after BIGINT NOT NULL, reserved_after BIGINT NOT NULL, detail TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(user_id,event_key)
);
CREATE INDEX platform_ledger_user ON platform_wallet_ledger(user_id,sequence DESC);
CREATE TABLE platform_credit_request (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES platform_user(id), amount BIGINT NOT NULL CHECK(amount > 0),
 reason TEXT NOT NULL, calendar_date DATE NOT NULL, sequence_in_day INTEGER NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PROCESSING','APPROVED','REJECTED')),
 decision_mode VARCHAR(16) NOT NULL CHECK(decision_mode IN ('IMMEDIATE','DELAYED','MANUAL')),
 rule_snapshot TEXT NOT NULL, scheduled_at TIMESTAMPTZ, granted_bucket UUID REFERENCES platform_wallet_bucket(id),
 reviewed_by VARCHAR(255), review_note TEXT, epoch INTEGER NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX platform_due_requests ON platform_credit_request(status,scheduled_at);
CREATE INDEX platform_requests_user ON platform_credit_request(user_id,calendar_date);
CREATE TABLE platform_admin_audit (
 id BIGSERIAL PRIMARY KEY, actor VARCHAR(255) NOT NULL, action VARCHAR(80) NOT NULL,
 user_id UUID, request_id VARCHAR(191) NOT NULL UNIQUE, facts TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE platform_migration_record (
 batch_id VARCHAR(191) NOT NULL, app_id VARCHAR(80) NOT NULL, local_user_id VARCHAR(191) NOT NULL,
 record_digest CHAR(64) NOT NULL, user_id UUID NOT NULL, status VARCHAR(16) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(batch_id,app_id,local_user_id)
);
