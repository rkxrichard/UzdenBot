-- Link payments to keys (optional)
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS key_id BIGINT REFERENCES vpn_keys(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_payments_key_id ON payments(key_id);
