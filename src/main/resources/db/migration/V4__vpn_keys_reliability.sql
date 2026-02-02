-- 1) статусы и сервисные поля для устойчивости к сбоям
ALTER TABLE vpn_keys
  ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS last_error TEXT;

-- 2) один активный/ожидающий ключ на пользователя (защита от параллельных запросов)
-- Postgres partial unique index
CREATE UNIQUE INDEX IF NOT EXISTS ux_vpn_keys_one_active_per_user
ON vpn_keys(user_id)
WHERE (is_revoked = false AND status IN ('PENDING','ACTIVE'));

-- 3) индексы для быстрых выборок (опционально, но полезно)
CREATE INDEX IF NOT EXISTS idx_vpn_keys_status ON vpn_keys(status);
CREATE INDEX IF NOT EXISTS idx_vpn_keys_updated_at ON vpn_keys(updated_at);
