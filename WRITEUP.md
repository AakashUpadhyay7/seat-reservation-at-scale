# Seat Reservation at Scale — Design Write-up

## 1. Atomic decision

PostgreSQL is the source of truth for seat ownership.

A reservation runs in one transaction:

1. Validate the authenticated request and idempotency key.
2. Serialize concurrent requests for the same `(show,user)` using a transaction-scoped PostgreSQL advisory lock.
3. Sort requested seat identifiers and lock the corresponding seat rows with `SELECT ... FOR UPDATE` in deterministic order.
4. Verify that every requested seat is `available`.
5. Count the user's already-confirmed seats and enforce the per-user limit.
6. Insert one reservation and attach the locked seats.
7. Mark those seats `confirmed`.
8. Attach the resulting reservation ID to the idempotency record.
9. Commit.

For two concurrent requests targeting A12, only one transaction can hold the A12 row lock at a time. The first confirms it; the next waits and then observes `confirmed`, producing a domain HTTP 409 rather than a 5xx.

The `seats.status` row is the authoritative ownership state. Historical `reservation_seats` links are retained for auditability, which also allows a cancelled seat to be booked again.

## 2. Multi-seat concurrency and deadlocks

Multi-seat requests are all-or-nothing. Seat names are sorted before locking, so requests for `[A1,A2]` and `[A2,A1]` both lock A1 then A2. This removes the classic opposite-lock-order deadlock pattern.

## 3. Per-user limit concurrency

Seat locks alone do not protect an aggregate per-user limit because two requests from one user could target different seats. The transaction-scoped advisory lock derived from `(show_id,user_id)` serializes that user's limit check while allowing different users to proceed independently.

## 4. Idempotency

The `idempotency_keys` table stores the show, authenticated user, idempotency key, SHA-256 request hash and resulting reservation ID. A unique constraint enforces `(show_id,user_id,idempotency_key)`.

- Same key + same request: the original reservation is returned.
- Same key + different seats: HTTP 409 `IDEMPOTENCY_KEY_REUSED`.
- A replay never creates a second reservation.

The idempotency insert and reservation are in the same transaction. If the reservation fails, the idempotency row is rolled back, so the key is not permanently consumed by a failed booking.

## 5. Identity and authorization

The reserve/cancel APIs never trust a `user_id` supplied by the request body. Identity is derived from the bearer token.

For this take-home implementation the token format is deterministic:

```text
Bearer user:<user-id>:<AUTH_SECRET>
```

Admin operations use the configured admin token. In production this would be replaced by JWT/OIDC verification using the issuer's JWKS.

## 6. Release model

The implementation chooses explicit cancellation rather than automatic expiry:

```text
available -> confirmed -> available
```

Cancellation locks both the reservation row and its physical seat rows before releasing them. This prevents a concurrent reservation from observing a partially released booking. The historical reservation remains `cancelled`, while the seat becomes available for a future booking.

## 7. Consistency vs availability

For a show, seat ownership must be strongly consistent. The service therefore treats PostgreSQL as the authoritative write store and returns dependency/domain errors rather than pretending a seat is available when the database cannot be trusted.

The API exposes reconciliation as:

```text
available + held + confirmed = total_seats
```

The show endpoint validates this invariant before returning state.

## 8. Observability

The service provides:

- `/live` for process liveness
- `/ready` for PostgreSQL readiness
- `/actuator/prometheus` for metrics
- request/correlation IDs in MDC and JSON logs
- confirmed reservation counters
- decline counters by reason
- available-seat gauges per show

Important metrics include `reservations_confirmed_total`, `reservations_declined_total{reason=...}` and `seats_available{show_id=...}`.

## 9. Burst testing

`scripts/burst.py` provides three modes:

- `hot-seat`: many authenticated users compete for one seat; expected result is one confirmation and domain declines, with no 5xx.
- `idempotency`: many retries use one idempotency key; expected result is one reservation reused by all same-body retries.
- `per-user`: many concurrent requests use one user and different seats; the final confirmed seat count must respect the configured per-user limit.

After every run the script fetches the show and verifies the reconciliation invariant.

## 10. AI usage

AI was used as an engineering assistant for initial decomposition, concurrency analysis, implementation scaffolding, edge-case review, test-harness design and documentation. The important design decisions were reviewed explicitly: PostgreSQL as source of truth, row locking, deterministic lock order, transaction-scoped per-user serialization, idempotency with request hashing, token-derived identity and database-backed reconciliation.

## 11. Production next steps

- Replace assignment authentication with OIDC/JWT verification.
- Add payment-provider idempotency and an outbox/event model.
- Add explicit time-boxed holds if checkout requires them.
- Add distributed tracing and production dashboards/alerts.
- Add rate limiting and backpressure.
- Run k6/Gatling/JMeter tests alongside the supplied burst tool.
- Add failure-injection tests for database/network failures.
- Move schema changes to Flyway/Liquibase migrations.
