ALTER TABLE platform_wallet ADD COLUMN credit_active boolean NOT NULL DEFAULT true;
CREATE TABLE platform_credit_opening (
  request_id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES platform_wallet(user_id),
  epoch integer NOT NULL,
  batch_id varchar(191) NOT NULL,
  source_digest varchar(64) NOT NULL,
  actor varchar(255) NOT NULL,
  reason text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(user_id,epoch)
);
