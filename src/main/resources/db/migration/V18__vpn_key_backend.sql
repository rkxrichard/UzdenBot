ALTER TABLE vpn_keys
  ADD COLUMN IF NOT EXISTS backend TEXT NOT NULL DEFAULT 'DEFAULT';

CREATE INDEX IF NOT EXISTS idx_vpn_keys_backend ON vpn_keys(backend);
