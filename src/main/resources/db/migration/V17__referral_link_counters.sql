-- =========================
-- V17: Referral link counters
-- =========================

ALTER TABLE referral_links
  ADD COLUMN IF NOT EXISTS transitions_count BIGINT NOT NULL DEFAULT 0;
