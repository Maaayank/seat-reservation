-- Domain schema. See docs/DISCOVERY.md §4.2.
-- One row per seat: a seat has exactly one status, so
-- available + held + confirmed == total_seats holds by construction.

CREATE TABLE shows (
    id              UUID        PRIMARY KEY,
    name            TEXT        NOT NULL,
    price_paise     BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit  INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats     INT         NOT NULL CHECK (total_seats > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id            UUID        PRIMARY KEY,
    show_id       UUID        NOT NULL REFERENCES shows (id),
    user_id       TEXT        NOT NULL,
    seat_labels   TEXT[]      NOT NULL,
    amount_paise  BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status        TEXT        NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at  TIMESTAMPTZ
);

CREATE INDEX reservations_show_user_idx ON reservations (show_id, user_id);

CREATE TABLE seats (
    show_id          UUID        NOT NULL REFERENCES shows (id),
    label            TEXT        NOT NULL,
    position         INT         NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'AVAILABLE'
                                 CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    reservation_id   UUID        REFERENCES reservations (id),
    owner_user_id    TEXT,
    hold_expires_at  TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- A free seat has no owner; a taken seat always has one.
    CONSTRAINT seat_owner_matches_status CHECK (
        (status = 'AVAILABLE' AND reservation_id IS NULL AND owner_user_id IS NULL)
        OR (status <> 'AVAILABLE' AND reservation_id IS NOT NULL AND owner_user_id IS NOT NULL)
    )
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- Per-user seat counter per show. Its row lock serialises one user's
-- concurrent reserves for one show (per-user limit).
CREATE TABLE user_show_quota (
    show_id      UUID NOT NULL REFERENCES shows (id),
    user_id      TEXT NOT NULL,
    seats_owned  INT  NOT NULL CHECK (seats_owned >= 0),
    PRIMARY KEY (show_id, user_id)
);

-- Exactly-once record for reserve requests, scoped per user.
CREATE TABLE idempotency_keys (
    user_id         TEXT        NOT NULL,
    key             TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    show_id         UUID        NOT NULL,
    reservation_id  UUID,
    response_json   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
