CREATE TABLE platform_wallet_rule_schedule (
  request_id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES platform_wallet(user_id),
  epoch integer NOT NULL,
  effective_at timestamptz NOT NULL,
  daily_credits bigint NOT NULL CHECK(daily_credits>=0),
  request_enabled boolean NOT NULL,
  max_request bigint NOT NULL CHECK(max_request>=0),
  max_daily_request bigint NOT NULL CHECK(max_daily_request>=0),
  immediate_count integer NOT NULL CHECK(immediate_count>=0),
  delayed_count integer NOT NULL CHECK(delayed_count>=0),
  delay_minutes integer NOT NULL CHECK(delay_minutes>=0),
  active boolean NOT NULL DEFAULT true,
  facts text NOT NULL,
  UNIQUE(user_id,epoch,effective_at)
);
