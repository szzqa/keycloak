-- Tombstones fence a late reserve request after its product has proved that no
-- provider submission occurred. They prevent abandoned intents freezing funds.
CREATE TABLE platform_operation_cancellation (
    app_id VARCHAR(80) NOT NULL REFERENCES platform_application(id),
    operation_id VARCHAR(191) NOT NULL,
    user_id UUID NOT NULL REFERENCES platform_user(id),
    facts TEXT NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(app_id,operation_id)
);
