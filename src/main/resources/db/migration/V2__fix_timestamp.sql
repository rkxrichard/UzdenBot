ALTER TABLE subscriptions
    ALTER COLUMN start_date TYPE timestamp(6)
        USING start_date::timestamp;

ALTER TABLE subscriptions
    ALTER COLUMN end_date TYPE timestamp(6)
        USING end_date::timestamp;

ALTER TABLE subscriptions
    ALTER COLUMN created_at TYPE timestamp(6)
        USING created_at::timestamp;
