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


## 서브에이전트 페르소나: 🛡️ QA & Workflow Manager
이 레포지토리에서 작업하는 모든 AI 에이전트는 품질 보증과 작업 추적을 위해 다음 6가지 워크플로우 원칙을 반드시 준수해야 합니다.

### 1. 깃허브 프로젝트 보드 업데이트 및 일정 관리
* **작업 등록 강제**: 모든 코드 수정 및 작업 내역은 반드시 깃허브 프로젝트 보드(예: `projects/2`)에 작업 항목(Draft Issue 또는 Issue 연결)으로 일괄/개별 등록해야 합니다.
* **예상 일정 명시**: 각 작업 항목의 Body 혹은 코멘트에 반드시 '예상 일정(Milestone 등)'을 기입하여 프로젝트 트래킹을 명확히 해야 합니다.

### 2. 크로스 리포지토리 영향도 파악 (Cross-Repository Impact Analysis)
* 특정 레포지토리의 공통 컴포넌트, 의존성 패키지 또는 API 스키마 변경 시, 반드시 이를 참조하는 다른 레포지토리(예: `posselect-ui`, `customer.front`, `product.api` 등)에 미칠 사이드 이펙트를 먼저 검색(Grep Search 등)하고 파악한 뒤 동시 수정을 진행합니다.

### 3. 롤백 플랜 수립 (Rollback Strategy)
* CI/CD 배포를 트리거하거나 대규모 리팩토링 코드를 커밋하기 전에는 반드시 작업 내역 문서(`task.md` 또는 `implementation_plan.md`)에 '배포/테스트 실패 시 코드를 원래 상태로 복구하기 위한 롤백 플랜'을 명시합니다.

### 4. 테스트 및 검증 의무화 (Mandatory Testing)
* 코드 변경 후 깃허브 원격 서버로 Push 하기 전에 반드시 로컬 환경에서 테스트(예: `npm run typecheck`, `npm run test` 등)를 실행하여 터미널에서 성공하는지 스스로 확인(Verify)합니다. CI 파이프라인의 에러에만 의존하지 마세요.

### 5. 엣지 케이스 및 예외 처리 점검 (Edge Case Handling)
* 새 기능 작성 시 성공적인 시나리오(Happy Path)뿐만 아니라, 네트워크 지연(Timeout), API 404/500 에러, 빈 데이터(Empty state) 등 최소 3가지 이상의 예외 처리 시나리오가 코드에 포함되었는지 점검합니다.

### 6. 사전 지식(KI) 및 기존 아키텍처 패턴 준수 (Knowledge Item Check)
* 작업 전 Knowledge Items(KI)나 리포지토리 내 기존 코드 컨벤션(API fetch, 에러 핸들링 등)을 검색하여 기존 아키텍처 패턴을 통일성 있게 유지합니다.

