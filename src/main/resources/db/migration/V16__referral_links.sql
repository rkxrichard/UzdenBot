-- =========================
-- V16: Tracked referral links
-- =========================

CREATE TABLE IF NOT EXISTS referral_links (
  id                BIGSERIAL PRIMARY KEY,
  referrer_user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code              TEXT NOT NULL UNIQUE,
  created_at        TIMESTAMP NOT NULL DEFAULT now()
);

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS referred_link_id BIGINT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE constraint_name = 'fk_users_referred_link'
      AND table_name = 'users'
  ) THEN
    ALTER TABLE users
      ADD CONSTRAINT fk_users_referred_link
      FOREIGN KEY (referred_link_id) REFERENCES referral_links(id) ON DELETE SET NULL;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_referral_links_referrer_user_id ON referral_links(referrer_user_id);
CREATE INDEX IF NOT EXISTS idx_users_referred_link_id ON users(referred_link_id);
