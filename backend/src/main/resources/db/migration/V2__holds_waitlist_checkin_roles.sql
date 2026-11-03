-- Seat holds, booking lifecycle, waitlist, check-in, ticket prices and user roles.
-- Written to work on the original hand-made schema whatever its constraint names are.

-- ---------------------------------------------------------------- users: roles
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));

-- One account per email (case-insensitive). Older versions of the app didn't check
-- this, so only add the index if the existing data allows it; the app checks too.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users GROUP BY lower(email) HAVING count(*) > 1) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uidx ON users (lower(email));
    ELSE
        RAISE NOTICE 'Duplicate emails found in users: skipped the unique email index';
    END IF;
END $$;

-- ---------------------------------------------------------------- events: price
ALTER TABLE events ADD COLUMN IF NOT EXISTS price NUMERIC(10, 2) NOT NULL DEFAULT 250.00;
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_price_check;
ALTER TABLE events ADD CONSTRAINT events_price_check CHECK (price >= 0);

-- ---------------------------------------------------------------- bookings: lifecycle
-- A booking is now:  HELD --confirm--> CONFIRMED --cancel--> CANCELLED
--                      \--release / 10 minutes pass--> EXPIRED
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS status            VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS reference         VARCHAR(20);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS hold_expires_at   TIMESTAMP;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS confirmed_at      TIMESTAMP;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS cancelled_at      TIMESTAMP;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS checked_in_at     TIMESTAMP;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS ticket_emailed_at TIMESTAMP;

-- Existing bookings were confirmed the moment they were made.
UPDATE bookings SET confirmed_at = booked_at WHERE status = 'CONFIRMED' AND confirmed_at IS NULL;
UPDATE bookings
SET reference = 'TKT-' || upper(substr(md5(id::text || clock_timestamp()::text || random()::text), 1, 8))
WHERE reference IS NULL;

ALTER TABLE bookings ALTER COLUMN reference SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS bookings_reference_uidx ON bookings (reference);

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_hold_expiry_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_hold_expiry_check
    CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL);

-- Replace "a seat can only ever appear in one booking" with "a seat can only have one
-- ACTIVE booking". Cancelled and expired bookings stay as history, and the seat can be
-- booked again. Two people grabbing the same seat at the same instant is still stopped
-- by the database itself.
DO $$
DECLARE
    r record;
BEGIN
    -- unique constraints on exactly (seat_id), whatever they were named
    FOR r IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'bookings'::regclass AND c.contype = 'u'
        GROUP BY c.conname
        HAVING array_agg(a.attname::text) = ARRAY['seat_id']
    LOOP
        EXECUTE format('ALTER TABLE bookings DROP CONSTRAINT %I', r.conname);
    END LOOP;

    -- stand-alone unique indexes on exactly (seat_id)
    FOR r IN
        SELECT ic.relname
        FROM pg_index x
        JOIN pg_class ic ON ic.oid = x.indexrelid
        JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = ANY (x.indkey)
        WHERE x.indrelid = 'bookings'::regclass
          AND x.indisunique AND NOT x.indisprimary AND x.indpred IS NULL
          AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
        GROUP BY ic.relname
        HAVING array_agg(a.attname::text) = ARRAY['seat_id']
    LOOP
        EXECUTE format('DROP INDEX %I', r.relname);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS bookings_one_active_per_seat_uidx
    ON bookings (seat_id) WHERE status IN ('HELD', 'CONFIRMED');
CREATE INDEX IF NOT EXISTS bookings_user_idx ON bookings (user_id, booked_at DESC);
CREATE INDEX IF NOT EXISTS bookings_held_idx ON bookings (hold_expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------- waitlist
CREATE TABLE IF NOT EXISTS waitlist_entries (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT    NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    user_id     BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    notified_at TIMESTAMP,
    UNIQUE (event_id, user_id)
);
CREATE INDEX IF NOT EXISTS waitlist_queue_idx ON waitlist_entries (event_id, created_at) WHERE notified_at IS NULL;
