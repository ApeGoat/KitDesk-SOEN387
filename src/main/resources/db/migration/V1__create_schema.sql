-- Kit Desk schema, version 1: catalogue, accounts, reservations, idempotency keys.
--
-- Tables keep a numeric id for joins, plus a public_id (e.g. unt_3f9a...) that is the only id the
-- API ever shows. The contract says ids are opaque, so clients can't guess or count records.

-- Needed so the reservation overlap rule can combine "same unit" (=) with "dates overlap" (&&).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(255) NOT NULL UNIQUE,
    password_hash       VARCHAR(100) NOT NULL,
    full_name           VARCHAR(255) NOT NULL,
    role                VARCHAR(20)  NOT NULL CHECK (role IN ('STUDENT', 'STAFF')),
    -- The "subscription" is just a flag for now; payment is out of scope.
    subscription_active BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
    id                     BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE DEFAULT ('cat_' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 16)),
    code                   VARCHAR(30)  NOT NULL UNIQUE,
    name                   VARCHAR(100) NOT NULL,
    -- How many active reservations one student may hold in this category.
    -- Stored here so staff can change it without a code change.
    rental_limit           INTEGER      NOT NULL CHECK (rental_limit > 0)
);

CREATE TABLE models (
    id          BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE DEFAULT ('mdl_' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 16)),
    category_id BIGINT       NOT NULL REFERENCES categories (id),
    brand       VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    UNIQUE (brand, name)
);

-- A unit is one physical item. asset_tag is the label printed on it, e.g. CAM-R5-03.
-- Students book units.
CREATE TABLE units (
    id        BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE DEFAULT ('unt_' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 16)),
    asset_tag VARCHAR(30) NOT NULL UNIQUE,
    model_id BIGINT      NOT NULL REFERENCES models (id),
    active   BOOLEAN     NOT NULL DEFAULT TRUE,
    notes    TEXT
);
CREATE INDEX idx_units_model ON units (model_id);

CREATE TABLE reservations (
    id           BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE DEFAULT ('res_' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 16)),
    student_id   BIGINT      NOT NULL REFERENCES users (id),
    unit_id      BIGINT      NOT NULL REFERENCES units (id),
    start_date   DATE        NOT NULL,
    end_date     DATE        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'RESERVED'
                 CHECK (status IN ('RESERVED', 'PICKED_UP', 'RETURNED', 'CANCELLED', 'EXPIRED')),
    picked_up_at TIMESTAMPTZ,
    returned_at  TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    -- Optimistic-locking version for cancel / pickup / return.
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date),

    -- Database backstop against double-booking: two *active* reservations for the same unit
    -- can never have overlapping dates, even if the service code had a bug.
    -- '[]' means both the start and end day are included.
    CONSTRAINT no_overlapping_active_reservations
        EXCLUDE USING gist (
            unit_id WITH =,
            daterange(start_date, end_date, '[]') WITH &&
        ) WHERE (status IN ('RESERVED', 'PICKED_UP'))
);
CREATE INDEX idx_reservations_student_status ON reservations (student_id, status);

-- Saved results of requests sent with an Idempotency-Key header, scoped to the user who sent them.
CREATE TABLE idempotency_keys (
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,   -- detects the same key reused with a different body
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);
