# Interview cheat sheet

## 30-second architecture

"PostgreSQL is my source of truth. A reservation is one transaction. I sort requested seats, acquire a per-user/show transaction advisory lock for the aggregate limit, then `SELECT ... FOR UPDATE` on the requested seat rows in deterministic order. I only create the reservation after every seat is confirmed available. A unique constraint on reservation_seats is a second guard. Idempotency is a unique `(show,user,key)` row storing a request hash and reservation ID."

## Why not read then update?

Because this is unsafe:

```text
SELECT status -> available
UPDATE status -> confirmed
```

Two transactions can read `available` before either writes.

`FOR UPDATE` makes the database serialize the decision on the actual seat row.

## Why advisory lock?

The per-user limit is an aggregate across multiple seat rows. Two requests from the same user could otherwise lock different seats and both pass a stale count. The transaction-scoped advisory lock serializes only that user/show pair.

## Why deterministic ordering?

For `[A1,A2]` and `[A2,A1]`, both transactions lock A1 then A2. There is no lock-order cycle.

## Why PostgreSQL instead of Redis?

Seat ownership needs durable transactional semantics and unique constraints. Redis can be useful for rate limiting/cache, but it should not be the system of record for a financial/booking invariant unless the entire consistency model is designed around it.

## What happens with 500 users for A12?

One transaction locks A12 and confirms it. The other transactions wait on that row lock and then see `confirmed`; they return 409 `SEAT_TAKEN`. There is no application-level race window.

## What happens with same idempotency key?

The unique constraint means only one idempotency row can exist for a user/show/key. The request hash detects a reused key with different seats. A retry with the same body returns the existing reservation.

## What would you change for production?

Replace assignment authentication with OIDC/JWT validation; add payment-provider idempotency; use an outbox for events; add explicit holds/expiry if checkout requires it; add tracing, rate limiting and production dashboards; and run failure-injection/load tests.
