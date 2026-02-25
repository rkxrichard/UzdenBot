-- =========================
-- V15: Remove subscription token (subscription links)
-- =========================

DROP INDEX IF EXISTS idx_users_subscription_token;

ALTER TABLE users
  DROP COLUMN IF EXISTS subscription_token;
