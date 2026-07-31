# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

UzdenBot is a Telegram VPN bot (Spring Boot 4, Java 17). It sells time-based VPN subscriptions, provisions clients on one or more [3x-ui](https://github.com/MHSanaei/3x-ui) panels, takes payments through YooKassa, and delivers connections as a single re-branded subscription link. Bot UI text, code comments, and git history are in Russian.

The app is simultaneously:
- a **Telegram long-polling bot** (`MainBot extends TelegramLongPollingBot`, registered in `TelegramBotConfig`), and
- an **HTTP server** on port 8080 exposing the YooKassa webhook (`/webhooks/yookassa`) and the subscription proxy (`/sub/{backend}/{subId}`).

## Commands

Uses the Maven wrapper (`./mvnw`). Requires JDK 17.

```bash
# Fast feedback: pure-Mockito unit tests (no infra needed) — everything except UzdenBotApplicationTests
./mvnw test -Dtest='*Test,!UzdenBotApplicationTests'

# Single test class / method
./mvnw test -Dtest=VpnKeyServiceTest
./mvnw test -Dtest='PaymentNotificationListenerTest#newKeyStillGetsDeliveredToUser'

# Full build (runs ALL tests, including the context-loading one — see caveat below)
./mvnw package
./mvnw -DskipTests package        # build jar without tests (this is what the Docker image does)

# Run locally (needs env vars + reachable Postgres & Redis; see application.yml for the full list)
./mvnw spring-boot:run

# Full stack via Docker (build + Postgres + Redis). Run from deploy/ with a .env (copy .env.example)
docker compose -f deploy/docker-compose.yml up -d --build
```

**Test caveat:** `UzdenBotApplicationTests.contextLoads()` is annotated `@SpringBootTest`, so it boots the whole context — that needs Postgres, Redis, and a valid `TELEGRAM_BOT_TOKEN` (it registers the bot with Telegram on startup). It will fail without that infra. **All other tests are plain Mockito unit tests** with no Spring context, so prefer the filtered command above for iteration.

## Architecture

### Update pipeline
`MainBot.onUpdateReceived` → `UpdateGuardService.guard` (Redis rate-limit + duplicate-update dedup, **fails open** if Redis is down) → `BotUpdateHandler.handle`. Handlers are **pure**: they return a `List<BotApiMethod<?>>` that `MainBot` executes, rather than calling the Telegram API directly (this is what makes them unit-testable). Callbacks are routed by a string-prefix convention on `callback_data` (`KEY_SELECT:<id>`, `KEY_RENEW_1M:<id>`, `BUY_1M`, `ADMIN_*`, `MENU_*`).

### VPN key lifecycle — the central pattern
`VpnKeyService` manages a `PENDING → ACTIVE / FAILED` state machine and deliberately **splits DB transactions from slow 3x-ui HTTP calls**. The recurring shape (`issueKey`, `replaceKey`, etc.):
1. `tx.execute(...)` — lock the user row (`userRepository.lockUser`), insert the key row as `PENDING` with `keyValue = "PENDING:<uuid>"`. Commit.
2. `finalizeIssueOutsideTx(keyId)` — **outside any transaction**, call 3x-ui to create the client; on success open a *second* tx to mark `ACTIVE` and store the real link; on failure mark `FAILED` and best-effort disable the client.

Do not move 3x-ui calls inside the DB transaction — holding a DB lock across a multi-second HTTP call is exactly what this design avoids. Stale `PENDING`/`FAILED` keys are retried by `recoverStale` and reaped by the cleanup job.

Per-user key cap is `MAX_KEYS_PER_USER = 3`, enforced in code (`canCreateNewKey` / `ensureKeyLimit`) — **not** by a DB constraint (migration `V8__multi_keys` dropped the old single-active-key unique index).

### Dual 3x-ui backend
`VpnKey.Backend` is `{DEFAULT, RU_EU}`. The `DEFAULT` backend is a normal Spring bean (`ThreeXuiClient` + `XuiProperties`); the optional `RU_EU` backend (admin-only keys) is built **manually** in constructors (`VpnKeyService`, `SubscriptionProxyService`) from `RuEuXuiProperties`, and only when `.configured()` is true. There is no second Spring-managed client bean — if you add backend-aware logic, thread it through the existing `backendConfig(...)` / `clientFor(...)` switches.

One logical key maps to **multiple 3x-ui inbounds** (`XUI_SUBSCRIPTION_INBOUND_IDS`, e.g. `1,2,3,4` = VLESS TCP / XHTTP / Trojan / gRPC). Per-inbound client identity is **derived deterministically** from the base UUID via `panelClientUuid(uuid, inboundId)` (`UUID.nameUUIDFromBytes`) and `panelClientEmail(...)`, so provisioning is idempotent and re-derivable. `ThreeXuiClient` speaks the panel's browser-style form API (cookie auth with lazy re-login, `X-Requested-With` + `/panel/*` Referer headers, envelope unwrapping, "Duplicate email" treated as idempotent success).

### Subscription delivery (the proxy)
Users never receive raw `vless://` lines — they get a subscription URL. `SubscriptionProxyService` + `SubscriptionProxyController` sit in front of the upstream 3x-ui `/sub` endpoint: `GET /sub/{backend}/{subId}` fetches the upstream subscription and `SubscriptionPayloadService.rewrite()` re-brands it (title `WayGuard`, Happ client). Enabled only when `APP_SUBSCRIPTION_PROXY_BASE_URL` is set; otherwise `buildSubscriptionUrl` falls back to the panel's own sub URL. `VpnKey.keyValue` for an ACTIVE key stores this subscription link.

### Payments (YooKassa) — three convergent confirmation paths
`PaymentService.createPayment` records a `pending` `Payment`, calls YooKassa, returns a `confirmationUrl`. Confirmation then arrives via **three independent routes that all funnel into `processVerifiedPayment`**, made idempotent by the `Payment.processedAt` guard:
1. **Webhook** — `POST /webhooks/yookassa` (auth via `Authorization` == `YOOKASSA_WEBHOOK_SECRET`, plain or `Bearer`; open if secret unset).
2. **Scheduled reconcile** — `@Scheduled` every 60s over unprocessed payments.
3. **Fast-check** — one-off `TaskScheduler` probes at `3s,8s,15s` after checkout (`app.payments.fast-check-delays-ms`) for near-instant UX.

Every path **re-fetches and verifies the payment from YooKassa** (never trusts the webhook body) and checks the amount before granting. On `succeeded`: create the key if the payment targeted none, extend the subscription (`extendSubscriptionForKey`), then publish a `PaymentStatusEvent`. `PaymentNotificationListener` handles it `AFTER_COMMIT` to message the user and deliver/refresh the key (first successful purchase includes setup instructions; renewals don't re-send the link).

### Per-key subscriptions & referrals
Subscriptions and payments each carry a nullable `key_id`, so **every key has its own subscription timeline** (migrations V9/V10). `extendSubscriptionForKey` stacks time from the current end date. `SubscriptionExpiryService` (every 5 min) reattaches active-but-unassigned subscriptions and revokes keys whose subscription lapsed. Referrals are two-tier: lightweight `users.referral_code`/`referred_by` columns (`V11`) plus tracked `referral_links` with click counters (`V16`/`V17`), handled on `/start <code>` by `ReferralService`.

### Scheduled jobs (all `@Scheduled`, enabled by `@EnableScheduling` on the main class)
- `PaymentService.reconcilePendingPayments` — 60s, verify pending payments.
- `SubscriptionExpiryService` — 5 min, revoke lapsed keys.
- `SubscriptionNotificationService` — 1h, "2 days / 1 day left" reminders (each fires once via `notified*At` columns).
- `VpnKeyCleanupService` — 1h, delete stale PENDING/FAILED keys and zero-traffic ACTIVE keys past `unused-ttl-hours`.

### State & Redis
- `IdempotencyService` (Redis SET-NX+TTL) and `RateLimiterService` (Redis Lua counter) are composed by `UpdateGuardService`; keys `rl:user:<id>` and `idemp:update:<updateId>`. Callback actions also take short idempotency locks (`idemp:...`).
- `AdminStateService` is an **in-memory `ConcurrentHashMap`**, not Redis — it holds the pending multi-step admin action per chat. This makes admin flows **single-instance only** and lost on restart.

### Admin flows
`/admin` (or "админ") opens the admin menu. Admin actions are stateful conversations: a callback sets an `AdminAction` in `AdminStateService`, the next text message is routed to `AdminFlowService` (add/check/revoke subscription, enable/disable/broadcast, create RU+EU key, create/manage referral links, purge disabled users). `/cancel` (or "отмена") clears state.

## Conventions & gotchas
- **Schema is owned by Flyway** (`src/main/resources/db/migration`, `V1`..`V18`); JPA runs `ddl-auto: validate`. Entity changes require a matching new migration or the app won't start. Migrations `V13`–`V15` (subscription_token) were added then fully reverted — ignore them.
- Config binds via `@ConfigurationProperties` records; all secrets/URLs come from env vars with dev defaults in `application.yml`. Real deployment values live in `deploy/.env` (git-ignored; see `deploy/.env.example`).
- Keep bot handlers returning `BotApiMethod` lists rather than executing Telegram calls inline — the test suite depends on it.
- `open-in-view: false` — no lazy loading outside a transaction; fetch what you need inside the service tx.
