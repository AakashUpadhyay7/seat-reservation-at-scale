CREATE TABLE IF NOT EXISTS shows (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS seats (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('available','held','confirmed')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(show_id, seat_number)
);

CREATE INDEX IF NOT EXISTS idx_seats_show_status ON seats(show_id, status);

CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('confirmed','cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_reservations_show_user_status
    ON reservations(show_id, user_id, status);

CREATE TABLE IF NOT EXISTS reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    seat_id UUID NOT NULL REFERENCES seats(id),
    PRIMARY KEY(reservation_id, seat_id)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(300) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID REFERENCES reservations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(show_id, user_id, idempotency_key)
);
