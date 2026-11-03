-- The original four tables, as they were first created by hand in pgAdmin.
--
-- On an existing database these tables are already there, so every statement is
-- "IF NOT EXISTS" and nothing changes. On a brand-new database this creates them.
-- (application.properties sets spring.flyway.baseline-version=0 so that Flyway runs
-- this file on existing databases too, where it is a harmless no-op.)

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS events (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    venue      VARCHAR(255) NOT NULL,
    event_date TIMESTAMP    NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seats (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    seat_label VARCHAR(10) NOT NULL,
    UNIQUE (event_id, seat_label)
);

-- seat_id UNIQUE was the original double-booking protection. V2 replaces it with a
-- partial unique index, because a seat can now be booked again after a cancellation.
CREATE TABLE IF NOT EXISTS bookings (
    id        BIGSERIAL PRIMARY KEY,
    seat_id   BIGINT    NOT NULL UNIQUE REFERENCES seats (id),
    user_id   BIGINT    NOT NULL REFERENCES users (id),
    booked_at TIMESTAMP NOT NULL DEFAULT now()
);
