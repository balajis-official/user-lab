# User Lab

One service, studied in depth: PostgreSQL, Kafka, Keycloak, advanced RestAssured, and k6 (TypeScript).
`user-service` is the application under test. `api-tests` and `perf` are written by you.

## Build status

| Phase | Content | Status |
|---|---|---|
| 1 | Schema, CRUD, nested JSON, ETag / If-Match, merge patch, search, errors, test controls, dashboard | **Done. Run on PostgreSQL 16.15; dashboard checked in jsdom** |
| 2 | PostgreSQL depth: JSONB queries + GIN, audit trigger, reports (window functions), 100k seed, EXPLAIN, keyset paging | Next |
| 3 | Kafka: `user.events` producer, `user.commands` consumer, dead-letter topic | Planned |
| 4 | Keycloak + dashboard role switcher | Planned |
| 5 | Avatar upload, CSV export/import (async job), rate limit, Idempotency-Key | Planned |
| 6 | k6 in TypeScript | Planned |
| 7 | Docker Compose + GitHub Actions | Planned |

Versions: Java 21, Spring Boot 4.1.1, PostgreSQL 16.

## Run (Windows PowerShell)

```powershell
psql -U postgres -c "CREATE USER agri WITH PASSWORD 'agri';"   # skip if you created it before
psql -U postgres -c "CREATE DATABASE user_db OWNER agri;"
cd user-service
mvn package -DskipTests
java -Xmx256m -jar target\user-service-0.1.0.jar "--spring.profiles.active=test"
```

Open http://localhost:8080 for the dashboard. Use Test controls: **Seed data**, then **Pin date** 2026-10-15.
Override the database with `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## API (port 8080)

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/users` | 201 + `Location` + `ETag` | 400, 409, 422 |
| GET | `/users/{id}` | 200 + `ETag`, `Cache-Control: no-cache`; **304** with matching `If-None-Match` | 404, 410 |
| PUT | `/users/{id}` (full replace, needs `If-Match`) | 200 + new `ETag` | 400, 404, 409, 410, **412**, **428**, 422 |
| PATCH | `/users/{id}` (`application/merge-patch+json`, `If-Match` optional) | 200 + new `ETag` | 400, 404, 409, 410, 412, **415**, 422 |
| DELETE | `/users/{id}` (soft delete, idempotent) | 204 | 404 |
| GET | `/users?q=&status=&role=&city=&sort=field,dir&page=&size=` | 200 + `X-Total-Count` + `Link` | 400 |

**ETag** is the version in quotes: `"3"`. Every successful write adds 1.

**Status codes in this project**

| Code | Meaning here |
|---|---|
| 400 | A field is wrong on its own. Body has `errors[] {field, message, rejectedValue}` with full paths like `addresses[0].geo.lat` |
| 404 | The user never existed |
| 409 | State blocks it (`INVALID_STATUS_TRANSITION`) or a DB unique constraint fired (`constraint`, `sqlState`) |
| 410 | The user existed but is deleted |
| 412 | `If-Match` is stale or malformed. Body has `currentVersion`, `currentETag` |
| 415 | PATCH sent with `application/json` instead of `application/merge-patch+json` |
| 422 | Fields are valid, but together break a rule: `UNDERAGE`, `MULTIPLE_PRIMARY_ADDRESSES`, `PRIMARY_ADDRESS_REQUIRED`, `SMS_NEEDS_PHONE`, `SMS_CHANNEL_DISABLED` |
| 428 | PUT without `If-Match` |

**Merge patch rules (RFC 7396):** objects merge key by key; `null` removes the key; arrays and plain values
replace the old value. The result is validated after the merge, so `{"profile":{"firstName":null}}` is a 400.

**Status changes through PATCH:** PENDING_VERIFICATION to ACTIVE, ACTIVE to SUSPENDED, SUSPENDED to ACTIVE.
DELETED only through DELETE. Anything else is 409.

**Search:** DELETED users are hidden unless `status=DELETED`. `q` matches username, email or full name,
case-insensitive; `%` and `_` in `q` match literally. `sort` fields: id, username, email, lastName, createdAt, status.

## Test controls (profile `test` only, 404 otherwise)

| Call | Effect |
|---|---|
| `POST /test/reset` | Empty tables, ids restart at 1, clock unpinned, bugs off |
| `POST /test/seed` | reset + 12 known users |
| `PUT /test/clock {"today":"2026-10-15"}` | Pin today. `{"today":null}` unpins |
| `PUT /test/bugs {"ignoreIfMatch":true,"mergePatchNullIgnored":true}` | Known bugs on |
| `GET /test/state` | Current date, pin, bugs |

## Seed data (after /test/seed)

| id | username | status | Why it is there |
|---|---|---|---|
| 1 | admin.arun | ACTIVE | ADMIN + USER roles |
| 2 | support.priya | ACTIVE | SUPPORT + USER |
| 3 | selvi.r | ACTIVE | Main example user |
| 4 | karthik.m | ACTIVE | Email stored as `Karthik.M@Example.in` (mixed case) |
| 5 | meena.s | PENDING_VERIFICATION | No addresses, no phone |
| 6 | rahul.k | SUSPENDED | Address without coordinates |
| 7 | divya.n | ACTIVE | Two addresses (HOME primary, WORK) |
| 8 | old.user | DELETED | Hidden from search, GET gives 410 |
| 9 | anand.v | ACTIVE | `sms: true`, so clearing phone gives 422 |
| 10 | lakshmi.p | ACTIVE | `sms: false`, so clearing phone works |
| 11 | vijay.t | ACTIVE | SUPPORT role, WORK address is primary |
| 12 | nisha.j | PENDING_VERIFICATION | Born 2008-10-15: exactly 18 on the pinned date, 17 on the real date |

## Things found while building (worth a test each)

1. **All seed users share one `created_at`.** `now()` in PostgreSQL is the transaction start time. That is why every
   sort ends with `id` as a tie-breaker. Without it, pages can repeat or skip rows.
2. **Failed inserts leave id gaps.** Identity values are not rolled back. Never assert `id == max + 1`.
3. **Nisha (id 12) can't be updated on the real date.** She is 17 until 2026-10-15, so any PUT fails with UNDERAGE unless the clock is pinned.

## Phase 1 exercises (you write these, in order)

RestAssured
1. `RequestSpecification` + `ResponseSpecification` built once in a base class. Every test uses them.
2. A custom `Filter` that prints request and response **only when a test fails**.
3. Create a user, deserialize into your own POJO, and compare with the request POJO (nested `equals`).
4. JSON schema for `UserResponse`: enums for status, roles, channels; `addresses[].geo` may be null.
5. GPath on `/users?size=50`: `content.findAll { it.status == 'ACTIVE' }.username`, `content.collect { it.roles.size() }.max()`.
6. `TypeRef<PageResponse<UserSummary>>` to deserialize the generic page.
7. `@DataProvider` 400 table: one row per bad field. Assert `errors.field` has the exact nested path.
8. 422 table: one row per rule code. Each row must break **only one** rule.
9. ETag flow: GET, then GET with `If-None-Match` (304, empty body), PUT with old ETag (412), PUT without If-Match (428).
10. Lost update proof: two "clients" read version 1, both PUT. First gets 200, second gets 412.
    Turn on `ignoreIfMatch`: the same test must now **fail**.
11. Merge patch: `null` clears phone (user 10); `null` on firstName is 400; arrays replace; wrong Content-Type is 415.
    Turn on `mergePatchNullIgnored`: your null test must fail.
12. Status transitions: a `@DataProvider` of (from, to, expected code) covering every pair.
13. Paging: follow the `Link` header's `next` until it disappears. Collect all ids. Assert no duplicates and the
    count equals `X-Total-Count`.
14. 404 vs 410: GET 999 and GET 8. Assert different status and title.

JDBC (PostgreSQL)
15. After POST, read the row with JDBC. Assert `version = 1`, `preferences` JSONB equals what you sent
    (hint: `preferences->'notifications'->>'sms'`).
16. Insert a user with email in different case directly with JDBC. Catch `SQLException`, assert SQLState `23505`
    and that the message names `uq_users_email_lower`.
17. Insert a second primary address with JDBC. Assert `uq_addresses_one_primary` fires. Then insert a second
    **non-primary** address and assert it works. Explain why in a comment.
18. After PUT, assert with JDBC that the old address ids are gone and new ones exist.
19. After DELETE, assert the row still exists with `status = 'DELETED'` and `deleted_at IS NOT NULL`, and that
    `ck_users_deleted` blocks setting status back without clearing `deleted_at`.
