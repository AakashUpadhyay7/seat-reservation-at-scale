# Seat Reservation at Scale — Design Write-up

## 1. Atomic decision

The authoritative state is PostgreSQL.

For a reservation request, the application starts one transaction and sorts the requested seat identifiers. It then locks all corresponding seat rows using PostgreSQL `SELECT ... FOR UPDATE` in deterministic seat order.

The decision is therefore:

1. Lock all requested seat rows.
2. Verify that every requested row is `available`.
3. Count the user's already-confirmed seats.
4. Verify the per-user limit.
5. Insert one reservation.
6. Attach the locked seat rows.
7. Change their state to `confirmed`.
8. Persist the idempotency key -> reservation mapping.
9. Commit.

If two requests target A12 concurrently, they cannot both pass step 2. The first transaction locks A12 and commits it as confirmed. The second transaction waits on the row lock and subsequently reads A12 as confirmed, returning a domain HTTP 409.

The `reservation_seats.seat_id` unique constraint provides a second database-level guard against accidental double assignment.

## 2. Multi-seat concurrency and deadlocks

Multi-seat requests are all-or-nothing.

Seat names are sorted before the `FOR UPDATE`. Therefore:

```text
Request 1: A1, A2
Request 2: A2, A1
```

both acquire locks in:

```text
A1 -> A2
```

rather than opposite orders.

This removes the common application-level deadlock cycle.

## 3. Per-user limit concurrency

Seat row locks alone are insufficient for a per-user aggregate limit because two requests from the same user could target different seats. The transaction therefore first obtains a PostgreSQL transaction-scoped advisory lock derived from `(show_id,user_id)`. All reservations for that user/show serialize at the limit-check boundary. Different users do not block each other.

## 4. Idempotency

The idempotency table stores:

- show
- authenticated user
- idempotency key
- SHA-256 request hash
- resulting reservation ID

A database unique constraint enforces:

```text
(show_id, user_id, idempotency_key)
```

The first request creates the idempotency row.

A concurrent retry attempts the same insert. PostgreSQL resolves the unique-key race. The retry then locks the existing row and checks its stored request hash.

Same key + same request:

```text
return original reservation
```

Same key + different seats:

```text
HTTP 409 IDEMPOTENCY_KEY_REUSED
```

The key is scoped to the authenticated user and show, so a different user cannot consume another user's key.

## 5. Payment/double-charge consideration

This assignment's reservation response is the authoritative booking operation and does not call an external payment provider.

For a real payment system, I would not put an unbounded external payment call inside the database transaction. I would use a reservation/payment state machine plus an outbox:

```text
RESERVATION_PENDING
        |
        v
PAYMENT_REQUESTED
        |
   +----+----+
   |         |
SUCCESS    FAILURE
   |         |
CONFIRMED   RELEASED
```

The payment provider's idempotency key would be the reservation ID or a separately persisted payment operation ID. This makes retries safe at the payment boundary as well.

## 6. Holds and expiry

This implementation chooses explicit cancellation rather than automatic expiry.

That keeps the correctness model small for a one-day take-home.

If timed holds were required, I would add:

```text
available -> held -> confirmed
                  |
                  +-> available on expiry
```

with an expiry timestamp and a worker/DB query that transitions only still-held rows. The expiry update would also use row locking/conditional state checks so it could never resurrect a confirmed seat.

## 7. Consistency vs availability

For a given show, seat ownership must be strongly consistent. Returning a seat as available when another buyer owns it is worse than temporarily declining a request.

Therefore the database transaction is the source of truth and the service prefers consistency over accepting a write when its authoritative database is unavailable.

The readiness endpoint explicitly checks PostgreSQL.

## 8. Observability

The service exposes:

- liveness
- readiness
- Prometheus metrics
- request IDs in response headers/log context
- reservation outcome counters
- available-seat gauges

Important operational alerts would include:

- readiness failures
- sustained HTTP 5xx
- database connection pool exhaustion
- reservation transaction latency spikes
- unusual `seat-taken` spikes during an on-sale event
- reconciliation invariant failures
- database lock/wait time growth
- unexpected decline-rate changes
- available-seat gauge becoming inconsistent with API state

The key business invariant is:

```text
available + held + confirmed = total
```

The API itself validates this invariant when returning show state.

## 9. AI usage

AI was used deliberately rather than blindly:

- brainstormed concurrency failure modes
- reviewed database transaction boundaries
- generated initial implementation scaffolding
- helped structure the metrics and burst test
- reviewed edge cases
- drafted documentation

The engineer made and reviewed the important decisions: database as source of truth, PostgreSQL row locking, deterministic lock order, all-or-nothing multi-seat semantics, unique idempotency constraint, request hashing, and token-derived identity.

## 10. Next steps

For production I would add:

- real OIDC/JWT verification
- payment-provider idempotency
- outbox/event publishing
- explicit holds and expiry if required
- distributed tracing
- rate limiting
- production dashboards
- load testing beyond the local burst script
- failure-injection tests for database/network failures
- reconciliation tooling and alerts
- a clear single-writer strategy per show/partition
