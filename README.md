# Seat Reservation at Scale

Concurrency-safe assigned-seat reservation service built for the Paytm Money take-home exercise.

## Stack

- Java 17
- Spring Boot 2.7.18
- PostgreSQL 16
- Spring JDBC
- Micrometer + Prometheus
- Docker / Docker Compose

## Correctness model

The database is the source of truth.

### Atomic seat decision

Reservation runs in one PostgreSQL transaction.

Requested seat names are sorted before locking. The service executes:

```sql
SELECT id, seat_number, status
FROM seats
WHERE show_id = ? AND seat_number IN (...)
ORDER BY seat_number
FOR UPDATE;
```

The transaction then verifies every requested row is `available`. Only after the locks are held does it create the reservation and mark those exact seats `confirmed`.

This means two transactions racing for A1 cannot both observe A1 as available. One obtains the row lock first; the other waits and then observes `confirmed` and returns HTTP 409.

Multi-seat requests are **all-or-nothing**. Sorting the seat names gives every transaction the same lock order, preventing the classic A1/A2 vs A2/A1 deadlock pattern.

A database-level `UNIQUE(seat_id)` constraint on `reservation_seats` is an additional invariant guard.

### Per-user limit

The same transaction holds the requested seat locks and counts the user's already-confirmed seats before inserting the new reservation. Therefore concurrent requests for the same user cannot independently pass the limit check for overlapping capacity.

### Idempotency

`idempotency_keys` has:

```sql
UNIQUE(show_id, user_id, idempotency_key)
```

The request hash is stored with the key.

The first request inserts the key and later attaches its reservation ID. A concurrent/retried request with the same key waits for the unique-key decision, then locks the existing idempotency row.

- Same key + same body -> original reservation is returned.
- Same key + different seats -> HTTP 409 `IDEMPOTENCY_KEY_REUSED`.
- A replay does not create another reservation or change seat state.

### Identity

The reserve/cancel APIs never accept `user_id` from JSON. The user identity comes from the bearer token.

For this take-home implementation, tokens are deterministic:

```text
Authorization: Bearer user:<user-id>:<AUTH_SECRET>
```

Admin uses the configured admin token.

In a production service this layer would be replaced by JWT/OIDC verification against the issuer's JWKS.

### Partial requests

The implementation uses **all-or-nothing** semantics. A request for `[A1,A2]` succeeds only if both are available. If either is unavailable, no seat is changed and the request receives HTTP 409.

### Release model

Explicit cancellation is implemented:

```text
POST /reservations/{id}/cancel
```

Only the owning authenticated user can cancel. Cancellation and seat release happen in one database transaction.

## API

### Create show

```http
POST /shows
Authorization: Bearer <ADMIN_TOKEN>
Content-Type: application/json
```

```json
{
  "name": "friday-night",
  "seats": ["A1","A2","A3"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

### Reserve

```http
POST /shows/{showId}/reserve
Authorization: Bearer user:alice:<AUTH_SECRET>
Idempotency-Key: 9c5...
Content-Type: application/json
```

```json
{"seats":["A1"]}
```

### Show state

```http
GET /shows/{showId}
```

The response contains every seat and aggregate counts. The reconciliation invariant is:

```text
available + held + confirmed == total_seats
```

There are currently no time-boxed holds, so newly created reservations move directly from available to confirmed.

### Cancel

```http
POST /reservations/{reservationId}/cancel
Authorization: Bearer user:alice:<AUTH_SECRET>
```

### Health

```text
GET /live
GET /ready
```

`/live` checks only process liveness.

`/ready` executes `SELECT 1` against PostgreSQL and therefore fails when the database dependency is unavailable.

### Metrics

```text
GET /actuator/prometheus
```

Important metrics:

- `reservations_confirmed_total`
- `reservations_declined_total{reason="seat-taken"}`
- `reservations_declined_total{reason="per-user-limit"}`
- `reservations_declined_total{reason="idempotent-replay"}`
- `seats_available{show_id="..."}`

## Run locally

Requirements: Java 17, Maven, Docker.

```bash
docker compose up --build
```

The API is available at:

```text
http://localhost:8080
```

Create a show:

```bash
curl -X POST http://localhost:8080/shows \
  -H 'Authorization: Bearer admin-local' \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5"],"price_paise":25000,"per_user_limit":4}'
```

Copy the returned show `id`, then:

```bash
curl -X POST http://localhost:8080/shows/SHOW_ID/reserve \
  -H 'Authorization: Bearer user:alice:local-secret' \
  -H 'Idempotency-Key: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}'
```

## Burst test

Hot-seat storm:

```bash
python3 scripts/burst.py http://localhost:8080 SHOW_ID 500
```

The script prints the outcome distribution and then fetches the final show state to verify reconciliation.

For a production/live test, use:

```bash
python3 scripts/burst.py https://YOUR-LIVE-URL SHOW_ID 20000
```

Start smaller first (100, 500, 2000) to avoid overwhelming a free-tier database.

## AI usage

AI was used as an engineering assistant for:

- initial API decomposition
- identifying concurrency failure modes
- reviewing transaction boundaries
- generating test/burst harness scaffolding
- documentation structure
- checking edge cases such as same-key/different-body idempotency

The final design decisions were reviewed and implemented explicitly: PostgreSQL row locking for seat contention, deterministic lock ordering for multi-seat reservations, a unique idempotency key constraint, request hashing, token-derived identity, and database-backed reconciliation.

## What I would add next

For production:

1. OIDC/JWT verification with issuer/JWKS rather than the assignment token format.
2. Redis only for non-authoritative caching; never for seat ownership.
3. Payment integration with an outbox/state machine so payment retries cannot create duplicate charges.
4. A real hold state with expiry if the product requires checkout time.
5. Partition/region strategy with a single authoritative write region for each show.
6. OpenTelemetry traces and dashboards/alerts.
7. Load tests using k6/Gatling/JMeter in addition to the supplied burst tool.
8. Database indexes and partitioning based on measured traffic.
9. Rate limiting/backpressure at the edge.
10. Automated reconciliation jobs that compare seat and reservation invariants.

## Git commit history

Commit incrementally rather than one giant commit. Suggested sequence:

```text
1. chore: bootstrap Spring Boot service
2. feat: add PostgreSQL schema and show API
3. feat: implement transactional seat reservation
4. feat: add idempotency and per-user limit
5. feat: add authenticated cancellation
6. feat: add health and Prometheus metrics
7. test: add concurrency and invariant tests
8. chore: add Docker Compose and production image
9. test: add hot-seat burst harness
10. docs: add architecture and assignment write-up
```

## Render deployment

Render supports Docker-based web services and managed Postgres. Create a PostgreSQL database and a Docker Web Service from this repository. Set the service health-check path to `/ready`. Render web services must listen on the `PORT` environment variable; this application already uses `${PORT:8080}`.

Set these environment variables on the web service:

```text
DATABASE_URL=jdbc:postgresql://<render-postgres-host>:5432/<database>
DB_USERNAME=<database-user>
DB_PASSWORD=<database-password>
AUTH_SECRET=<long-random-secret>
ADMIN_TOKEN=<long-random-admin-token>
```

The app initializes its schema from `schema.sql` on startup. For a real production deployment, use versioned migrations (Flyway/Liquibase) instead of startup initialization.

After deployment:

```bash
curl https://YOUR-SERVICE.onrender.com/live
curl https://YOUR-SERVICE.onrender.com/ready
```

Then create a show using the admin token and run the burst script against the public URL.

Render's free web services can spin down after inactivity, so the first request can be a cold-start request. Free Render Postgres currently has a 30-day lifetime and other resource limitations; this is suitable for an interview demo but not a production database. For the evaluator's 20,000-request burst, use a sufficiently sized temporary service/database if the free instance becomes a bottleneck.
