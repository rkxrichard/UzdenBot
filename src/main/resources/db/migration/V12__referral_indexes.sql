-- =========================
-- V12: Referral indexes
-- =========================

CREATE INDEX IF NOT EXISTS idx_users_referred_by ON users(referred_by);
