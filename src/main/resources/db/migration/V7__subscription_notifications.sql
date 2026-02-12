ALTER TABLE subscriptions
  ADD COLUMN IF NOT EXISTS notified_two_days_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS notified_one_day_at TIMESTAMP;
