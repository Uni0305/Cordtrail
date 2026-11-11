# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project

Cordtrail: a Kotlin/JDA Discord bot that records guild events (messages, reactions, members, voice) into a single ClickHouse table, `discord_events`. Kotlin 2.4, JDK 25 toolchain, JDA 6.7, ClickHouse `client-v2`. Root package: `com.github.uni0305.cordtrail`.

## Commands

```bash
./gradlew test                                   # all tests (JUnit 5 + MockK)
./gradlew test --tests '*EventBufferTest'        # one test class
./gradlew test --tests '*EventBufferTest.someMethod'  # one test method
./gradlew koverHtmlReport                        # coverage (CordtrailBot entry point is excluded)
./gradlew shadowJar                              # fat jar -> build/libs/Cordtrail-1.0-SNAPSHOT.jar (plain `jar` task is disabled)
docker compose up -d --build                     # bot + ClickHouse, reads .env (copy from .env.example)
```

Run locally: `DISCORD_TOKEN=... CLICKHOUSE_URL=http://localhost:8123 java -jar build/libs/Cordtrail-1.0-SNAPSHOT.jar`. Configuration is env-only; see the table in README.md and `config/BotConfig.kt`. Code style is `kotlin.code.style=official`. Dependency versions live in `gradle/libs.versions.toml`.

`.env` holds the real bot token and DB password — never commit it or print its contents.

## Architecture

Data flow: Discord gateway → `listener/*` → `RecordConsumer` → `storage/EventBuffer` → `ClickHouseEventSink` → ClickHouse.

- **Wiring** (`CordtrailBot.kt`): load `BotConfig` → create ClickHouse client → `SchemaInitializer` runs `schema.sql` (retries 10×3s) → start `EventBuffer` → build JDA with listeners. Two privileged intents (`GUILD_MEMBERS`, `MESSAGE_CONTENT`) are required; login fails without them. Shutdown hook stops JDA, then flushes the buffer for up to 10s.
- **Listeners** (`listener/`): each `ListenerAdapter` converts a JDA event into an `EventRecord` and calls `RecordConsumer.accept` — they must never block or do I/O. Shared builders live in `Payloads.kt` (`record(...)`, `userPayload`, `channelPayload`, `emojiPayload`, `messagePayload`, ...). Common fields go into top-level columns (`guild_id`, `channel_id`, `user_id`, `message_id`); everything else goes into the `payload` map, which becomes a ClickHouse `JSON` column. Payload keys are snake_case and are part of the queryable data contract — renaming them breaks existing queries. When adding/changing an `event_type`, update the "Recorded events" table in README.md.
- **EventBuffer** (`storage/EventBuffer.kt`): the core reliability logic. Non-blocking `offer`; drops oldest when over capacity; single flusher thread; failed batches are requeued at the front with exponential backoff (1s→60s); after 5 consecutive failures it bisects the batch to isolate "poison" records and drops only those (only if some part succeeded — otherwise it's treated as an outage and everything is requeued). `close()` refuses to flush concurrently with a stuck flusher thread to preserve ordering. Read the KDoc before changing it.
- **Sink/encoding**: `JsonEachRowEncoder` serializes each record independently (unserializable records are dropped individually via callback). `EventSink` is a `fun interface` so tests can substitute it.
- **ClickHouse client** (`storage/ClickHouse.kt`): `client-v2` is synchronous, so `future.get(timeout)` doesn't enforce anything — the connect/socket timeouts set in `createClickHouseClient` are the only timeout. `schema.sql` is split on `;`, `--` comment lines are stripped, and `{db}` is substituted (database name validated as an identifier in `BotConfig`). Retries can create duplicate rows; dedupe by `event_id` at query time.

## Testing

- `listener/JdaStubs.kt`: non-relaxed MockK stubs for JDA types plus `CapturingConsumer`. Calling an unstubbed getter fails the test, so new payload fields require extending the stubs.
- `storage/FakeClickHouseServer.kt`: an in-process HTTP server (JDK `HttpServer`) that imitates the ClickHouse HTTP endpoint with scriptable status codes/delays — used for sink, schema, and timeout tests instead of a real ClickHouse.
