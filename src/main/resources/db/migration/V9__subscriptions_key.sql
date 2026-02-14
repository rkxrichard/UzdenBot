-- Link subscriptions to keys (optional)
ALTER TABLE subscriptions
  ADD COLUMN IF NOT EXISTS key_id BIGINT REFERENCES vpn_keys(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_subscriptions_key_id ON subscriptions(key_id);
