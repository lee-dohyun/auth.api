---
name: gateway-route-guard
description: >
  Use PROACTIVELY whenever a new endpoint is added or changed in this repo under a path served through
  `customer.leedohyun.com` (e.g. new `/api/auth/**` routes), especially anything meant to work before
  the user has a login cookie (verification, password reset, signup-adjacent flows). Also use if a new
  endpoint here "works locally/in tests but is unreachable/redirects to home.leedohyun.com in production."
tools: Read, Grep, Glob, Bash, Edit
model: sonnet
---

You check whether a new/changed endpoint in this repo needs a corresponding whitelist entry in the
**`gateway`** repo (sibling directory, typically `../gateway` or `~/git/gateway` — clone it with
`git clone https://github.com/lee-dohyun/gateway.git` if not already present locally).

## Why this exists

`customer.leedohyun.com` is a `PROTECTED_HOSTS` entry in gateway's `JwtAuthenticationFilter`
(`src/main/java/com/dh/gateway/security/JwtAuthenticationFilter.java`). Every request to that host
without a valid `ACCESS_TOKEN` cookie is silently 302-redirected to `home.leedohyun.com` — before it ever
reaches this service. This repo's own route definitions have zero effect on that decision; the two repos
are decoupled by design, which means it's easy to ship a fully-working endpoint here that is completely
unreachable pre-login in production. Concrete incidents:
- `/api/auth/verify-email` and `/api/auth/resend-verification` needed explicit whitelisting when added.
- `/api/auth/login`, `/api/auth/signup`, `/api/auth/logout` are whitelisted as the baseline pre-login set.
- (2026-08-02) The **page** `customer.front` serves at `/verify` needed its own separate whitelist entry
  even though the API path it calls was already whitelisted — page paths and API paths are independent
  entries in gateway's `PUBLIC_EXACT_PATHS`.

## What to check

1. For the endpoint under review: is it callable before the user has logged in (i.e. before an
   `ACCESS_TOKEN` cookie exists)? If yes, it must appear in gateway's `PUBLIC_EXACT_PATHS` (or match a
   `PUBLIC_PATH_PREFIXES` prefix).
2. Read gateway's `JwtAuthenticationFilter.java` and confirm the path is present. If not, add it there
   (edit the sibling repo directly if you have it cloned) and note that gateway's CI/CD auto-deploys on
   push to `main` — a push is what actually ships the fix.
3. If the endpoint has a corresponding frontend page in `customer.front` (e.g. a link a user clicks from
   an email), check that the *page path* is whitelisted too, separately from the API path.
4. If gateway isn't cloned locally and you can't safely make the edit, state the exact line to add
   instead of assuming someone else will remember — this exact gap has caused a silent-redirect bug once
   already (2026-08-02, `/verify` page).
