# auth.api AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다. 아래는 이 저장소만의 특이사항이다.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`auth.api` is the standalone authentication service for the leedohyun.com system. It issues and signs JWTs and exposes a JWKS endpoint so that [gateway](../gateway) can verify tokens independently. It is a Spring Boot 3.5 / Java 21 / Gradle project.

## Commands

```bash
./gradlew build          # compile, run tests, produce build/libs/*.jar
./gradlew test           # run tests only (JUnit 5 via junit-platform)
./gradlew test --tests com.dh.auth.ApplicationTests   # run a single test class
./gradlew bootRun         # run the app locally (needs Postgres, see below)
```

CI (`.github/workflows/docker-image.yml`) runs `./gradlew build` on push to `main`, then builds and pushes a Docker image tagged with the `rootProject.name` from `settings.gradle` to Docker Hub.

### Running locally

The app needs Postgres. Connection defaults come from `application-local.yml` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` env vars, defaulting to `localhost:5432/authdb` / `authuser:authpass`). `spring.jpa.hibernate.ddl-auto=update`, so schema is auto-migrated from the entities — there are no Flyway/Liquibase migrations.

## Architecture

- **Package root**: `com.dh.auth`, layered as `controller` / `domain` / `dto` / `repository` / `security` / `config`.
- **Auth flow** (`AuthController`): signup/login/logout issue and clear an httpOnly `ACCESS_TOKEN` cookie (`SameSite=Lax`). Passwords are BCrypt-hashed. There is no refresh token — the access token cookie is the only credential.
- **Trust boundary with the gateway**: `GET /api/auth/me` does **not** re-verify the JWT itself. It trusts `X-User-Id` / `X-User-Role` headers that [gateway](../gateway) is expected to inject after validating the token upstream. This endpoint is only safe to expose behind that gateway; calling it directly bypasses auth.
- **Key material** (`JwtKeyProvider`): an RSA-2048 keypair is generated once in memory at process startup (POC-scale — not persisted, not shared across instances). The key ID (`kid`) is a random UUID picked at boot, so **restarting the service invalidates every previously issued token**, and running multiple replicas would each sign with a different key (only fine as long as `JwksController` is queried per-instance or there's a single instance).
- **JWKS endpoint** (`JwksController`, `GET /.well-known/jwks.json`): exposes the public key in JWKS format (via Nimbus JOSE) so `gateway` can fetch it and verify RS256-signed tokens without shared secrets.
- **Tokens** (`JwtProvider`): RS256, subject = email, custom `role` claim, expiration configurable via `jwt.access-token-expiration-minutes` (default 30).
- **CORS** (`WebConfig`): only `https://*.leedohyun.com` origins are allowed, credentials included, methods limited to GET/POST/OPTIONS.
- **Persistence**: single `User` JPA entity/table (`email`, `password` hash, `role`, default role `"USER"`), via `UserRepository`.

## Related services

- [gateway](../gateway) — validates JWTs against this service's JWKS endpoint and forwards identity via `X-User-Id`/`X-User-Role` headers.

## Important: new endpoints under `customer.leedohyun.com` need a gateway whitelist edit too

`customer.leedohyun.com` is a `PROTECTED_HOSTS` entry in `gateway`'s `JwtAuthenticationFilter` — any
request to it without a valid `ACCESS_TOKEN` cookie gets 302-redirected to `home.leedohyun.com`, silently,
with no error surfaced to the caller. Whenever a new endpoint here is meant to be callable **before
login** (like `/api/auth/login`, `/api/auth/signup`, `/api/auth/verify-email`,
`/api/auth/resend-verification` already are), it must also be added to `PUBLIC_EXACT_PATHS` (or
`PUBLIC_PATH_PREFIXES`) in `gateway`'s `JwtAuthenticationFilter.java` — this repo's own route mapping
has no effect on that decision, since the two repos are decoupled. This bit twice already: once for the
verify-email API path itself, and once more (2026-08-02) for `customer.front`'s `/verify` *page* path,
which is a separate whitelist entry from the API path it calls. See `gateway/CLAUDE.md`'s "Key
implication for changes" section for the mechanics and incident history.
