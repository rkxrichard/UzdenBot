-- =========================
-- V14: Ensure subscription_token exists
-- =========================

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS subscription_token TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_subscription_token ON users(subscription_token);
