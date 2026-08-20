# auth.api AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다.
> 원칙이 충돌하면 캐논이 이긴다. 아래는 이 저장소만의 특이사항이다.
>
> `CLAUDE.md`는 이 파일(`AGENTS.md`)로의 심링크다 — 둘 중 아무거나 고쳐도 같은 파일이다.

## 이 저장소는 무엇인가

`auth.api`는 PosSelect 쇼핑몰의 인증/회원 도메인 서비스다. Spring Boot 3.5.3 / Java 21 / Gradle.

**중요 — 신원(identity)은 이 서비스가 갖고 있지 않다.** 로그인/비밀번호/계정 생성은 전부
**Keycloak(`customer` realm)에 위임**하고, 이 서비스는 두 가지 일만 한다.

1. Keycloak 앞의 얇은 어댑터: 로그인 요청을 password grant로 대신 던지고, 받은 토큰을 쿠키로 내려준다.
   회원가입·이메일 인증·비밀번호 재설정은 Keycloak Admin API를 호출해서 처리한다 (`KeycloakClient`).
2. Keycloak에 없는 **로컬 회원 도메인**의 주인: 등급, 인증 전화번호, 배송지, 마케팅 수신 동의, 약관.
   Postgres에 `members` 테이블을 두고 `keycloak_user_id`(Keycloak sub)로 신원과 이어 붙인다.

과거에는 이 서비스가 직접 RSA 키를 만들어 JWT를 서명하고 JWKS를 노출했으나 **지금은 아니다.**
`JwtProvider` / `JwtKeyProvider` / `JwksController` / `User` 엔티티는 모두 제거됐다. JWT 검증은
gateway가 Keycloak의 JWKS로 직접 한다. 이 저장소에 JWT 서명 코드를 다시 만들지 말 것.

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트 + build/libs/*.jar
./gradlew test           # 테스트만 (JUnit 5)
./gradlew test --tests com.dh.auth.service.PhoneVerificationServiceTest   # 단일 테스트 클래스
./gradlew bootRun        # 로컬 실행 (Postgres + Keycloak 필요, 아래 참고)
```

푸시 전에 `./gradlew test`를 로컬에서 직접 돌려 성공을 확인한다. CI 에러에만 의존하지 말 것.

CI/CD(`.github/workflows/docker-image.yml`): `main` push → `./gradlew build` → Docker Hub 푸시 →
self-hosted runner(`k3s-home`)가 `kubectl set image deployment/auth-api -n customer`로 **즉시 프로덕션 반영**.
문서만 바꾸는 커밋은 커밋 메시지에 `[skip ci]`를 붙인다.

### 로컬 실행

`application-local.yml`은 **없다.** 설정은 `src/main/resources/application.yml` 하나뿐이고, 기본값이
클러스터 내부 주소(`postgres-service.customer.svc.cluster.local`,
`keycloak-service.keycloak.svc.cluster.local`)를 가리킨다. 로컬에서 띄우려면 최소한
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`와 `KEYCLOAK_URL`/`KEYCLOAK_CLIENT_SECRET`을
환경변수로 덮어써야 한다. SMS는 `SMS_*`, 메일은 `MAIL_*`.

테스트 프로파일(`src/test/resources/application-test.yml`)은 H2(`MODE=PostgreSQL`) + `ddl-auto: create-drop`
+ `flyway.enabled: false` + `sms.provider: mock`이다. **즉, 테스트는 Flyway를 타지 않는다** — 아래 참고.

## 스키마 변경은 Flyway로만 (사고 위험 1순위)

- 마이그레이션은 `src/main/resources/db/migration/`에 **V1~V7이 실재한다.**
  V1 회원 앵커/등급, V2 `members.current_phone_number`, V3 `member_addresses`,
  V4 마케팅 수신 동의, V5 전화번호 E.164 정규형, V6 `agreements`/`agreement_articles`,
  V7 `agreements` id BIGINT 전환.
- 운영 설정은 `ddl-auto: validate` + `flyway.enabled: true`(`baseline-on-migrate: true`)다.
  **`ddl-auto: update`로 되돌리지 말 것** (캐논: posselect #104). 엔티티만 고치면 배포 시
  validate가 실패해 부팅이 안 되는 것으로 끝나지만, `update`로 우회하면 운영 스키마가 조용히 변형된다.
- 컬럼/테이블 추가는 **엔티티 수정 + 새 `V{n}__*.sql` 한 세트**로만 한다.
- **이미 배포된 마이그레이션 파일은 절대 수정하지 않는다** — Flyway checksum 불일치로 부팅이 막힌다.
  V7이 존재하는 이유가 정확히 이것이다(V6을 고쳤다가 checksum 사고 → V6 원복 + V7 신규 추가, 커밋 `f2d82c3`).
  잘못 나간 마이그레이션은 되돌리지 말고 **뒤에 새 버전을 덧붙여서** 고친다.
- 제거는 expand-contract 2단계(새것 추가 → 코드 전환 → 다음 릴리스에서 제거).
- **테스트는 마이그레이션 누락을 못 잡는다.** 테스트 프로파일은 Flyway를 끄고 엔티티에서 H2 스키마를
  만들기 때문에, 마이그레이션을 빼먹어도 `./gradlew test`는 초록불이고 프로덕션 배포에서 validate가 터진다.
  스키마를 건드렸다면 마이그레이션 파일이 커밋에 포함됐는지 눈으로 확인할 것.

## 아키텍처

- **패키지 루트** `com.dh.auth`: `controller` / `dto` / `entity` / `repository` / `service`(+`service.sms`) /
  `security` / `support` / `config`.
- **인증 위임** (`security/KeycloakClient`): 로그인은 Direct Access Grant(password grant), 갱신은
  refresh_token grant. 계정 생성/조회/삭제, 이메일 인증, 비밀번호 재설정은 서비스 계정
  (client_credentials, `auth-api-backend`)으로 Admin API를 호출한다. 비밀번호는 이 저장소에 저장되지 않는다.
- **쿠키** (`AuthController`): 로그인 성공 시 `ACCESS_TOKEN`(httpOnly, secure, `SameSite=Lax`,
  domain=`app.cookie-domain`, path `/`, maxAge=토큰 만료). "로그인 상태 유지" 체크 시에만
  `REFRESH_TOKEN`을 path `/api/auth`로 추가 발급하고, `POST /api/auth/refresh`가 회전시킨다.
- **로그인 판정**: Keycloak ROPC는 이메일 미인증 계정에도 토큰을 내주므로, 토큰을 받은 뒤 Admin API로
  `emailVerified`를 조회해 컨트롤러가 판단한다. 미인증이면 쿠키를 심지 않고 403 `EMAIL_NOT_VERIFIED`.
- **회원가입**: ① 휴대폰이 최근 인증됐는지 검사(아니면 400 `PHONE_NOT_VERIFIED`) → ② Keycloak 계정 생성
  (로컬 `members`에 연동 안 된 "좀비" 계정이 있으면 지우고 재생성) → ③ `Member` + 기본 등급 + 전화번호 연결
  → ④ 인증 메일 발송. ③~④에서 예외가 나면 **Keycloak 계정을 삭제해 롤백**한다(커밋 `eb28498`).
- **이메일 인증 / 비밀번호 재설정**: Keycloak 기본 메일 대신 자체 토큰을 Keycloak 사용자 속성에 저장한다
  (`emailVerificationToken` 24시간, `passwordResetToken` 1시간). 메일은 `spring-boot-starter-mail`로 보내고
  링크는 `app.frontend-base-url`을 가리킨다. 이메일 존재 여부를 노출하지 않기 위해
  `resend-verification`/`forgot-password`는 없는 계정에도 200을 반환한다(가입 충돌 409와는 성격이 다름).
- **휴대폰 OTP** (`PhoneVerificationService`, `PhoneController`): OTP TTL 5분, 재발송 쿨다운 60초,
  최대 5회 시도, 가입에 쓸 수 있는 인증 유효창 30분. 저장 형식은 **E.164 정규형**으로 고정
  (`support/PhoneNumbers`, libphonenumber; 입력 검증은 `@ValidPhoneNumber`). 표기가 갈라지면 UNIQUE 제약과
  인증 이력 조회가 동시에 샌다 — 정규화를 우회하는 경로를 만들지 말 것.
- **SMS** (`service/sms`): `SmsProvider` 인터페이스 + `SolapiSmsProvider`(net.nurigo SDK). `sms.provider`가
  `solapi`이고 키가 있을 때만 실제 발송하고, 그 외(`mock` 등)에는 발송하지 않는다. 국내/해외 듀얼 라우팅
  사업자 선정은 아직 미결이라 이 인터페이스가 교체 지점이다.
- **회원 도메인**: `members`(=`keycloak_user_id` 유니크 앵커, 현재 등급, 현재 인증 전화번호, 마케팅 동의/시각),
  `member_grades`(등급 정책 마스터 — 등급을 코드에 하드코딩하지 말 것), `member_grade_history`,
  `member_addresses`(배송지 N개, 기본 배송지 플래그), `phone_verifications`(인증 이력) / `phone_otp_attempts`,
  `agreements`/`agreement_articles`(약관 본문, `GET /api/auth/agreements?type=terms|privacy`).
- **i18n**: `messages*.properties`(ko/en/zh/ja), `fallback-to-system-locale: false`(미지원 언어는 JVM 로케일이
  아니라 한국어 번들로 떨어뜨리기 위함). 사용자에게 보일 문구는 예외에 담지 말고 메시지 키로 넘긴 뒤
  컨트롤러가 요청 로케일로 해석한다(`config/Messages`).
- **CORS** (`WebConfig`): `app.cors-allowed-origin-pattern`(기본 `https://*.posselect.com`) 오리진만,
  credentials 포함, 메서드는 `GET/POST/OPTIONS`. `PUT /api/auth/me`, `PUT|DELETE /api/auth/addresses/**` 같은
  엔드포인트가 이 목록에 없는데도 도는 이유는 프론트가 **같은 오리진(게이트웨이 경유)** 으로 부르기 때문이다.
  다른 오리진에서 부를 일이 생기면 preflight부터 막히므로 `allowedMethods`를 같이 늘려야 한다.

## 게이트웨이와의 신뢰 경계

- gateway는 `ACCESS_TOKEN` 쿠키의 JWT를 Keycloak 공개키로 검증한 뒤 `X-User-Id`(sub) / `X-User-Email` /
  `X-User-Name` / `X-User-Role`을 주입한다. 어떤 분기를 타든 클라이언트가 보낸 동명 헤더는 **항상 먼저 제거**된다.
- 이 저장소의 로그인 후 엔드포인트(`/api/auth/me` GET·PUT·DELETE, `/api/auth/addresses/**`)는 JWT를 **다시
  검증하지 않고** 그 헤더를 그대로 믿는다. 게이트웨이를 거치지 않고 직접 호출하면 헤더가 없어 401이 되지만,
  **게이트웨이 뒤가 아닌 곳에 이 서비스를 노출하면 그대로 인증 우회**가 된다.
- **캐논과의 알려진 격차**: 캐논은 소유자 키로 Keycloak sub(`X-User-Id`)만 쓰라고 못박는데(posselect #210),
  현재 코드는 `X-User-Email`을 받아 Keycloak에서 사용자를 조회해 `keycloak_user_id`로 되짚는다
  (`MemberAddressService.resolveMember`). 이메일 변경 시 소유권이 끊기고 Keycloak 왕복도 낭비다.
  **새 코드는 `X-User-Id`를 직접 쓰고**, 기존 경로를 손볼 일이 생기면 같이 전환한다.

## 로그인 전에 부르는 경로는 gateway 화이트리스트에도 등록해야 한다

`customer.posselect.com`은 gateway의 `protected-hosts`다. 이 호스트로 온 요청이 화이트리스트에 없고
유효한 `ACCESS_TOKEN` 쿠키도 없으면, 호출자에게 에러를 주지 않고 **로그인 페이지로 302 리다이렉트**된다
(원래 경로는 `redirect_uri`로 실림). API 호출이 이렇게 리다이렉트되면 프론트에서는 "이유 없이 실패"로만 보인다.

이 저장소의 라우팅은 그 판단에 **아무 영향이 없다.** 로그인 전에 호출돼야 하는 엔드포인트를 추가하면
`gateway`의 `JwtAuthenticationFilter.java`에 있는 `PUBLIC_EXACT_PATHS`(또는 `PUBLIC_PATH_PREFIXES`)에
반드시 같이 등록해야 한다. 현재 등록된 이 저장소 경로는 `/api/auth/login`, `/signup`, `/logout`,
`/verify-email`, `/resend-verification`, `/refresh`, `/find-id`, `/forgot-password`, `/reset-password`,
`/phone/send-otp`, `/phone/verify-otp`.

이미 두 번 당했다: verify-email API 경로 자체에서 한 번, 그리고 `customer.front`의 `/verify` **페이지** 경로에서
한 번 더(2026-08-02 — API 경로와 페이지 경로는 별개 항목이다). 프론트 페이지와 그 페이지가 부르는 API는
각각 등록해야 한다. 메커니즘과 사고 이력은 `gateway/CLAUDE.md`의 "Key implication for changes" 참고.
이 저장소에는 이 점검을 자동화한 `.claude/agents/gateway-route-guard.md` 서브에이전트가 있다.

## 관련 서비스

- [gateway](../gateway) — Keycloak JWKS로 JWT를 검증하고 `X-User-*` 헤더를 주입하는 단일 진입점.
- Keycloak(`customer` realm) — 실제 신원 저장소. 계정/비밀번호/이메일 인증 상태의 원본.
- [customer.front](../customer.front) — 이 API의 주 소비자. 전화번호 형식 검증 등 일부 규칙이 화면 쪽에도
  복제돼 있어 백엔드만 고치면 화면에서 먼저 막힌다.

