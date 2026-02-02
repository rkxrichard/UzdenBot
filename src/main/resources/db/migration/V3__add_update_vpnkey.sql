ALTER TABLE vpn_keys
  ADD COLUMN inbound_id BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN client_uuid UUID,
  ADD COLUMN client_email TEXT;

CREATE INDEX IF NOT EXISTS idx_vpn_keys_user_id ON vpn_keys(user_id);
CREATE INDEX IF NOT EXISTS idx_vpn_keys_client_uuid ON vpn_keys(client_uuid);
CREATE INDEX IF NOT EXISTS idx_vpn_keys_inbound_id ON vpn_keys(inbound_id);
