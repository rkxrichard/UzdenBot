-- =========================
-- V1: Initial schema
-- =========================

-- Пользователи
CREATE TABLE users (
                       id            BIGSERIAL PRIMARY KEY,
                       telegram_id   BIGINT NOT NULL UNIQUE,
                       username      TEXT,
                       created_at    TIMESTAMP NOT NULL DEFAULT now()
);


-- Подписки пользователей
CREATE TABLE subscriptions (
                               id           BIGSERIAL PRIMARY KEY,
                               user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               start_date   DATE NOT NULL,
                               end_date     DATE NOT NULL,
                               is_active    BOOLEAN NOT NULL DEFAULT true,
                               created_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- VPN ключи / конфиги
CREATE TABLE vpn_keys (
                          id               BIGSERIAL PRIMARY KEY,
                          user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          key_value        TEXT NOT NULL UNIQUE,
                          is_revoked       BOOLEAN NOT NULL DEFAULT false,
                          created_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- Платежи
CREATE TABLE payments (
                          id            BIGSERIAL PRIMARY KEY,
                          user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          amount        NUMERIC(10,2) NOT NULL,
                          currency      TEXT NOT NULL DEFAULT 'RUB',
                          provider      TEXT,
                          status        TEXT NOT NULL,
                          created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- Индексы (очень важно)
CREATE INDEX idx_users_telegram_id ON users(telegram_id);
CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);
CREATE INDEX idx_vpn_keys_user_id ON vpn_keys(user_id);
CREATE INDEX idx_payments_user_id ON payments(user_id);
