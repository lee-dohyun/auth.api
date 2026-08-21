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

---

<!-- canon:begin sha=10744dafb2fa src=~/msa/AGENTS.md -->
## 공통 캐논 (모든 AI 도구 공통)

> **공통 캐논 (자동 주입 — 손으로 고치지 말 것).** 원본은 `~/msa/AGENTS.md`이고 이 블록은
> `~/msa/scripts/sync-agents-canon.sh`가 넣는다. 이 저장소만 클론해 도는 도구(Codex, CI,
> 워크스페이스를 저장소로만 연 IDE)는 `~/msa`를 볼 수 없으므로 규칙을 여기 함께 둔다.
> **규칙을 바꿀 때는 원본을 고치고 sync 스크립트를 다시 돌릴 것.**

### 현재 단계: 개발 단계 (운영 제약 유예)

**posselect는 아직 실사용자 트래픽이 없는 개발 단계다.** 사용자가 명시적으로 확인한 사항: 무중단 배포·롤링 안전성·하위 호환 유지 같은 운영 제약을 기본값으로 깔지 말고, 다운타임이 나거나 기존 데이터를 리셋해야 해도 **가장 단순한 방법으로 바로 변경·적용**한다.

- 아래 §3의 **expand-contract(2단계 제거) 규칙은 이 유예가 끝난 뒤 적용**한다. 개발 단계에서는 컬럼/테이블을 한 번에 갈아엎어도 된다. 단 **Flyway 마이그레이션으로만 바꾼다는 규칙 자체는 유예 대상이 아니다**(체크섬 사고 이력).
- 이 유예는 한시적이다. **실 서비스 시작 시점은 사용자가 별도로 통지**하며, 통지 이후에는 이 절을 삭제하고 §3을 그대로 적용한다.

## 3. 불변 개발 규칙 (위반 금지)

실제 사고에서 도출된 규칙이다. 근거 이슈를 함께 표기한다.

### DB / 스키마
- **스키마 변경은 Flyway 마이그레이션으로만.** `ddl-auto`는 `validate` 유지, `update` 복귀 금지 (posselect #104).
- 스키마 변경은 **expand-contract**: 컬럼/테이블 제거는 "새것 추가 → 코드 전환 → 다음 릴리스에서 제거" 2단계로.
- `@Enumerated(STRING)` enum에 값 추가 시 기존 CHECK 제약은 자동으로 안 넓혀짐 — 마이그레이션에 `ALTER` 포함할 것.
- 재고 음수 방지 CHECK, 멱등성 유니크 인덱스 등 **DB 레벨 제약은 애플리케이션 로직과 별개로 유지**한다 (posselect #211 V3).

### 트랜잭션 / 정합성
- **`@Transactional` 안에서 원격 HTTP 호출 금지**(보상 로직 없이). 로컬 롤백돼도 원격은 롤백 안 된다 (posselect #140, order.api 사례).
- **모든 상태 변경(쓰기) API는 멱등해야 한다.** 재시도/중복 호출이 이중 차감·이중 결제가 되지 않게 멱등성 키(예: orderId) 기반 dedup을 넣는다 (posselect #211).
- 클래스 레벨 `@Transactional(readOnly = true)`인 클래스에 쓰기 경로 추가 금지 — 전파 함정으로 UPDATE가 조용히 사라진다. 쓰기는 별도 클래스 또는 `REQUIRES_NEW` (posselect #211 롤백 사례).
- **트랜잭션 전파·멱등성 변경은 단위 테스트로 검증이 성립하지 않는다.** 실제 DB 상태 변화 실측(같은 키로 2회 호출 → 1회만 반영)으로 검증하고, 실측 후 데이터 원복까지 한 세트로 수행 (posselect #211).

### 보안 / 인가
- **사용자 식별 키는 Keycloak sub(`X-User-Id`)만.** 이메일은 변경 가능하므로 소유자 키로 쓰지 않는다 (posselect #210).
- 게이트웨이 주입 헤더(`X-User-*`)는 게이트웨이가 항상 **덮어써야** 한다 — 클라이언트가 보낸 값을 통과시키면 인증 우회가 된다 (msa #87).
- **리소스 조회/변경 API에는 소유자 검사 필수.** 소유자 불일치는 403이 아니라 **404**로 응답(순번 ID에서 403은 유효 ID 범위를 노출) (posselect #214).
- **새로 외부에 노출되는 리소스는 순번 PK(BIGSERIAL)를 URL/응답에 노출하지 말 것** — public_id(UUIDv7/ULID) 별도 부여 (posselect #214 재발 방지).
- 로그인 전 호출되는 경로를 추가하면 gateway `PUBLIC_EXACT_PATHS`에도 **반드시 같이** 등록 (라우팅과 인증 화이트리스트가 다른 저장소에 있음).
- 의존성 보안 패치(특히 Next.js/Spring)는 미루지 않는다 — store-front가 Next.js RCE(CVE-2025-66478)로 실제 침해 정황을 겪음 (msa #155).

### K8s / 배포
- stateful Deployment(PVC 사용)는 `strategy: Recreate`. 모든 PV는 `reclaimPolicy: Retain`. apply 전 `claimName`을 `kubectl get pvc`와 대조.
- 새 도메인은 기존 와일드카드 TLS 시크릿을 참조만 할 것 — Ingress에 `cert-manager.io/cluster-issuer` 어노테이션 추가 금지(와일드카드 인증서를 덮어쓰는 사고 이력).
- Ingress는 `leedohyun-com-ingress.yaml`/`posselect-com-ingress.yaml` 두 파일에 host만 추가. 서비스별 개별 Ingress 금지.
- CI는 main push → Docker 이미지 → CD(self-hosted runner) 즉시 프로덕션 반영. **문서만 바꿀 땐 커밋 메시지에 `[skip ci]`.**
- 여러 서비스에 걸친 변경은 **배포 순서**를 먼저 설계할 것(예: gateway → front → api 순서를 지켜야 게스트 결제가 안 끊기는 사례, posselect #210).
- `@posselect/ui` 변경은 Storybook만 자동 배포됨 — 소비 저장소 5개(customer/store/product/admin.front + posselect-shell)를 각각 재빌드해야 화면에 반영 (posselect #197).
- **`[skip ci]`는 커밋 제목뿐 아니라 본문에서도 인식된다.** 다른 커밋을 인용하려고 본문에 그 문자열을 적으면 배포가 조용히 건너뛰어진다 — 실제로 product.api 캐시 수정이 이 때문에 배포되지 않았다(gateway#204).
- **`[skip ci]`로 건너뛴 배포를 되살릴 때**: `docker-image.yml`에 `workflow_dispatch`만 추가하면 부족하다. `deploy` 잡의 `if:`가 `github.event_name == 'push'`로 고정돼 있어 수동 실행은 빌드만 하고 배포는 skip된다. 조건도 `push || workflow_dispatch`로 함께 풀 것(현재 product.api만 적용됨).
- **`pull_request` 워크플로는 PR head 브랜치의 파일로 돈다.** main의 워크플로를 고쳐도 이미 열려 있는 PR에는 반영되지 않고, `gh run rerun`은 원래 런의 워크플로 버전을 재사용한다. 수정 확인은 **브랜치를 리베이스한 뒤** 새 런으로 할 것.
- **Dependabot PR에는 저장소 시크릿이 전달되지 않는다.** 시크릿을 쓰는 스텝(`docker/login-action`)은 `if: github.event_name == 'push'`로 막고, `secrets.X`를 문자열에 끼워 넣는 곳(이미지 태그)은 `${{ secrets.X || 'ci-local' }}` 폴백을 줄 것 — 안 그러면 모든 Dependabot PR이 상시 실패해 PR 게이트 신호가 죽는다(gateway#209).

### CLI / 스크립팅
- **SSH를 통한 원격 bash 명령 실행 시 따옴표 이스케이프 주의:** PowerShell에서 변수(`$BODY`)를 따옴표 안에 넣어 원격 `curl` 등을 호출하면 bash 쪽에서 JSON 포맷 에러(`400 Bad Request` 등)가 발생하기 쉽다. 복잡한 인용부호(JSON 등)가 포함된 스크립트는 **전체를 Base64로 인코딩한 뒤 원격에서 디코딩하여 `bash`로 실행**한다 (`echo $b64 | base64 -d | bash`).

## 4. 작업 기록 및 관리 (GitHub & Memory) — 모든 도구 공통

모든 에이전트는 더 이상 Redmine을 사용하지 않으며, 아래의 **Task Execution Workflow**에 따라 GitHub Projects 및 Issues를 단일 소스(SSOT)로 활용합니다.

1. **명령 인식 (Command Recognition)**: 사용자의 의도와 작업 범위를 명확히 파악합니다.
2. **깃허브 이슈 확인 및 즉시 선점 (Check & Claim)**: 작업을 시작하기 전에 반드시 GitHub Project #2와 관련 저장소 이슈를 조회하여 동일/겹치는 작업이 이미 `In Progress`인지 확인합니다. 조회·클레임은 `~/msa/scripts/claim.sh <repo> <issue>` 한 줄로 수행한다(다른 세션이 잡고 있으면 스크립트가 막는다). 겹치는 항목이 없으면 **코드를 건드리기 전에** 해당 이슈를 만들거나 열어 Status를 `In Progress`로 즉시 전환합니다. **이 서버는 Claude Code/Codex/Antigravity 등 여러 AI 도구를 여러 세션으로 동시에 띄워 작업하는 환경이므로, "조회만 하고 착수 시점에 클레임하지 않는" 흐름으로는 다른 세션과 같은 소스/같은 작업이 겹칠 수 있다.** 조회 시 대상 항목이 이미 `In Progress`(특히 최근 갱신)이면 같은 작업을 새로 시작하지 말고 사용자에게 확인한다.
3. **작업 수행 (Task Execution)**: 파악된 작업을 순차적으로 수행하며 필요한 코드를 수정하거나 작성합니다.
4. **커밋 전 서브에이전트 검수 (Pre-commit Subagent Review)**: 코드를 커밋하기 전에 해당 레포지토리의 서브에이전트(또는 특화된 페르소나 규칙)를 활용하여 코드를 검수합니다.
5. **검수 후 주석 및 커밋 메시지 표준화 작성 (Standardized Comments & Commit Message)**: 검수가 완료된 코드에 대해 표준화된 주석을 달고, 일관된 양식의 커밋 메시지를 작성합니다.
6. **배포 (Deployment)**: 작성된 코드를 알맞은 파이프라인이나 환경으로 배포합니다.
7. **배포 후 정상 동작 확인 (Post-deployment Verification)**: 배포가 완료된 후 시스템이 정상적으로 동작하는지 반드시 테스트하고 검증합니다.

**지속적인 업데이트 (Continuous Updates)**: 위 과정을 진행하면서 진행 상황은 아래 §4-1 인계 프로토콜(`progress.sh`)로 이슈에 남깁니다. (예전 이 문단은 "내부 `task.md` 를 동기화하라"고 지시했으나, 그런 파일은 이 머신에 존재한 적이 없다 — 선언만 있고 실체가 없는 규칙이었으므로 제거했다.) 특히, **작업이 완전히 끝났을 때는 커밋 메시지(`Closes #이슈번호`)를 활용하거나 `gh issue close` 명령어를 통해 반드시 깃허브 이슈를 '완료(Closed)' 처리해야 합니다.**

**세션 격리 (Worktree, Check & Claim의 보완책)**: Check & Claim은 "같은 작업"의 중복 착수를 막는 조치이고, 이것과 별개로 여러 세션(도구 무관)이 **같은 저장소**(`~/git/<repo>`)의 공용 클론을 동시에 건드리면 서로 다른 작업이어도 파일/브랜치가 물리적으로 충돌할 수 있다. 저장소 작업을 시작할 때는 공용 클론을 직접 건드리기보다 별도 worktree를 기본으로 삼는다.
- Claude Code는 `EnterWorktree` 도구로 `.claude/worktrees/<repo>/<name>` 아래 자동 생성/전환한다 — 기본 경로를 그대로 쓴다.
- Codex/Antigravity 등 자체 worktree 기능이 없는 도구는 `git worktree add ../<repo>-<slug> -b <branch>`로 수동 생성하고, 작업 종료 후 `git worktree remove`로 정리한다.
- **각 저장소 `.gitignore`에 `.claude/worktrees/`가 반드시 있어야 한다.** 없으면 `git add -A`/`git add .` 한 번에 worktree 디렉터리 전체가 gitlink(모드 160000)로 커밋되어 origin까지 올라갈 수 있다 — 2026-08-21 `customer.front`에서 실제로 발생·이미 push된 상태로 확인됨(별도 정리 필요, 이 문서 편집만으로는 해결되지 않음).

## 4-1. 인계 프로토콜 — 다른 도구가 중간부터 이어받게 하기

세 도구(Claude Code / Codex / Antigravity)가 **전부 같은 GitHub 계정으로 커밋**하므로 assignee·커밋 author 로는 누가 무엇을 잡고 있는지 구분되지 않는다. 진행 상태를 공유할 수 있는 매체는 **이슈 코멘트 하나뿐**이다. 도구별 메모리(예: Claude의 `~/.claude/projects/.../memory`)나 로컬 파일에 적으면 다른 도구는 영원히 못 읽는다.

### 세션 시작 (도구 무관, 필수)

```bash
~/msa/scripts/session-start.sh      # 활성/스테일 클레임 + 저장소별 브랜치·미커밋·미푸시 상태
```

Claude Code 는 SessionStart 훅이 자동 실행한다(로컬 모드). **훅이 없는 도구는 세션의 첫 명령으로 직접 실행할 것.**

### 코멘트 규격 (기계 판독용 첫 줄 + 사람이 읽는 본문)

| 종류 | 언제 | 명령 |
|------|------|------|
| `CLAIM` | 코드를 건드리기 **전** | `~/msa/scripts/claim.sh <repo> <issue>` |
| `PROGRESS` | 의미 있는 단위마다 | `~/msa/scripts/progress.sh <repo> <issue> "한 일\|다음 단계\|검증 방법"` |
| `HANDOFF` | 중단하거나 끝낼 때 | `~/msa/scripts/handoff.sh <repo> <issue> "남은 일/위험" [--done]` |
| `TAKEOVER` | 남의 스테일 클레임을 인수할 때 | `~/msa/scripts/claim.sh <repo> <issue> --takeover` |

- 코멘트 첫 줄은 ```CLAIM tool=... branch=... started=...``` 형태로 고정된다. 손으로 쓰지 말고 스크립트를 쓸 것 — 포맷이 깨지면 다른 세션의 클레임 판정이 틀린다.
- **실행 도구 식별은 자동이다 — 세션마다 뭘 설정할 필요 없다.** 스크립트가 `/proc` 조상 체인에서 이 셸을 띄운 주체(ccd-cli / codex / antigravity IDE 서버 …)를 찾아 판별한다. 환경변수는 자식으로 새기 때문에(Claude 세션 안에서 codex 를 띄우면 `CLAUDECODE` 를 물려받는다) 조상 체인을 먼저 본다.
  - 판별 결과가 `unknown` 으로 남는 도구가 생기면, 그때마다 `AGENT_TOOL` 을 치지 말고 **`~/msa/scripts/lib/agent-protocol.sh` 의 `_agent_ancestry_scan()` 에 패턴 한 줄을 추가**한다(한 번만 하면 그 도구의 모든 세션에 적용된다).
  - 일회성으로 다르게 기록해야 할 때만 `AGENT_TOOL=... ` 또는 `--tool` 로 덮어쓴다.

### 스테일 클레임 만료 (2시간)

마지막 프로토콜 코멘트가 **2시간**(`MSA_CLAIM_STALE_SECONDS`) 넘게 없으면 그 클레임은 만료된 것으로 보고 `--takeover` 로 인수할 수 있다. 반납되지 않은 `In Progress` 가 영원히 남아 다른 세션을 막는 문제를 이 규칙으로 푼다(2026-08-21 실측: In Progress 11건 중 클레임 기록이 있는 것 0건, 일부는 며칠째 정지).

### 인계 가능 = 원격에 push된 상태

로컬 worktree 의 브랜치는 다른 도구·다른 세션 눈에 **보이지 않는다.** 작업을 중단할 때는 `wip:` 커밋이라도 push 한 뒤 `handoff.sh` 를 실행한다(미푸시 상태로 인계하려 하면 스크립트가 막는다). `--done` 없이 실행하면 Status 는 `In Progress` 로 남고 클레임만 반납되어, 다른 도구가 `--takeover` 로 바로 이어받는다.

### 어디에 무엇을 쓰나

| 내용 | 위치 |
|------|------|
| 진행 중 상태·다음 단계·인계 정보 | **이슈 코멘트**(위 프로토콜) |
| 확정된 개발 규칙 | `~/msa/AGENTS.md` (이 문서) |
| 사고 기록·ADR 등 장기 지식 | GitHub Wiki(gateway/order.api) |
| 도구 자신의 작업 효율용 메모 | 각 도구의 메모리 — **다른 도구는 못 읽는다는 전제로만 사용** |

## 5-1. 자동 점검 장치 — 도구 무관 (2026-08-21 배선, 같은 날 도구 무관화)

규칙을 문서로만 선언하지 않고 실제로 강제하는 장치다. **어떤 AI 도구도 이 장치들을 우회하지 말 것** —
우회하면 이 문서의 규칙이 다시 선언으로만 남는다.

- **`<저장소>/scripts/verify.sh`** — push 전 검증의 **단일 진입점**. 스택을 자동 판별해
  `./gradlew test` 또는 `npm run typecheck/lint/test` 를 돌리고, `scripts/verify.d/*.sh` 추가 검사를 실행한다.
  문서·도구 설정만 바뀐 push 는 스스로 건너뛴다. 우회는 `MSA_SKIP_VERIFY=1`, 우회했다면 그 사실을 보고/이슈에 남길 것.
  - 호출자 3곳이 **같은 스크립트**를 부른다: `.githooks/pre-push`(도구 무관) / `.claude/hooks/pre-push-verify.sh`(Claude) / CI.
  - `.githooks/pre-push` 는 클론마다 `~/msa/scripts/bootstrap-hooks.sh` 를 1회 돌려 `core.hooksPath` 를 걸어야 활성화된다
    (이 설정은 커밋되지 않는 로컬 설정이다). **새 클론·새 머신에서 제일 먼저 할 일.**
  - 2026-08-21 이전에는 검증이 `.claude/hooks/` 아래에만 있어 Claude 이외의 도구가 push 하면 아무 검증도 걸리지 않았다.
- **`<저장소>/AGENTS.md` 의 `<!-- canon:begin -->` 블록** — 이 문서의 공통 규칙이 각 저장소에 주입된 사본이다.
  `~/msa` 는 git 저장소가 아니라 저장소만 클론해 도는 도구(Codex, CI, IDE)는 원본을 읽을 수 없기 때문이다.
  **손으로 고치지 말 것.** 규칙 변경은 이 문서를 고치고 `~/msa/scripts/sync-agents-canon.sh` 를 다시 돌린다
  (`--check` 로 어긋난 저장소를 찾는다). `CLAUDE.md`/`GEMINI.md` 는 `AGENTS.md` 심링크다.
- **`<저장소>/.claude/agents/*.md`** — 저장소별 가드(게이트웨이 화이트리스트, Flyway, 트랜잭션/멱등성,
  캐시 무효화, 디자인 토큰, 셸 계약). Claude Code는 자동 위임하고, **다른 도구는 해당 파일을 읽어 같은 점검을 수행할 것.**
- **결정적 검사 스크립트** — `check-token-mirror.sh`(posselect-ui), `check-i18n-keys.sh`/`check-mermaid.sh`
  (architecture), `~/msa/scripts/check-architecture-drift.sh`. LLM 없이 동작하므로 어떤 도구에서든 그냥 실행하면 된다.
- **CI** — 각 저장소 `pr-check.yml`(PR 단계 게이트), `claude-review.yml`(자동 리뷰, `ANTHROPIC_API_KEY` 필요).
  단 `pr-check.yml` 은 `pull_request` 에서만 돈다 — **main 직push 는 CI 게이트가 없고 곧 배포다.**
  그래서 push 전 검증은 `.githooks/pre-push` 가 유일한 방어선이다.

작업 기록은 `msa-work-log` 스킬(Claude Code) 또는 `~/.claude/skills/msa-work-log/SKILL.md`(다른 도구는 이 파일을
읽고 같은 절차 수행)를 따른다. **Project에 저장소 미연결 Draft issue를 만들지 말 것** — 2026-08-17 이관 때
중복 카드 210여 건이 생긴 원인이다. 항상 실제 저장소 Issue를 만들어 Project #2에 연결한다.
<!-- canon:end -->
