-- =========================
-- V11: Referral system
-- =========================

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS referral_code TEXT UNIQUE,
  ADD COLUMN IF NOT EXISTS referred_by BIGINT,
  ADD COLUMN IF NOT EXISTS referred_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_referral_code ON users(referral_code);
