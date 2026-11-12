CREATE TABLE platform_wallet_refund (
    app_id VARCHAR(80) NOT NULL,
    refund_id UUID NOT NULL,
    operation_id VARCHAR(191) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount>0),
    reason VARCHAR(1000) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(app_id,refund_id),
    FOREIGN KEY(app_id,operation_id) REFERENCES platform_wallet_operation(app_id,operation_id)
);
