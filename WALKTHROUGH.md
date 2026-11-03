# Walkthrough: explaining this project

This guide is for interviews and viva questions. It covers what the project does, why it's built this way, and answers to the questions you're most likely to get.

## The 30-second pitch

> "It's a ticket booking system: Spring Boot, PostgreSQL and React. The interesting part is everything around the booking. Seats are held for 10 minutes while you pay. Tickets are emailed as a PDF with a QR code. There are reminders, a waitlist and check-in at the door. None of that runs inside the web request. The API writes a background job into the same database transaction as the booking, and my own job queue, Conveyor, runs it later with retries. So a booking can never lose its ticket email, and double booking is impossible even under concurrent clicks, because the database enforces it."

## What changed from the first version

| Before | After |
|---|---|
| Clicking a seat booked it instantly | The seat is **held** for 10 min, then you pay (mock) or it's released |
| Two fast clicks could both "succeed" | A partial unique index makes a second live booking impossible |
| No emails | Ticket email with a PDF and QR code, reminder 2 h before, cancellation email |
| No way to cancel | Cancel until 2 h before; the seat goes back on sale |
| Sold out = dead end | Waitlist; the longest-waiting person is emailed when a seat frees up |
| Anyone could create events | Admin role (JWT claim); admins create events and check people in |
| Tables made by hand / Hibernate | Flyway migrations that upgrade the old database without losing data |
| Password in `application.properties` | Secrets in a git-ignored `.env` |
| No tests | Integration tests against real PostgreSQL, CI on every push |

## One booking, end to end

1. **Pick a seat.** `POST /api/bookings` inserts a booking with `status = HELD` and `hold_expires_at = now + 10 min`. In the **same transaction** it inserts an `expire-hold` job into Conveyor's `jobs` table, due when the hold ends.
2. **Someone else clicks the same seat.** Their insert hits the unique index `(seat_id) WHERE status IN ('HELD','CONFIRMED')` and fails, so they get "409 seat just taken".
3. **Pay.** `POST /api/bookings/{id}/confirm` runs `UPDATE bookings SET status='CONFIRMED' WHERE id=? AND status='HELD' AND hold_expires_at > now()`. If that changes 0 rows, the hold has run out (410). In the same transaction it adds `send-ticket-email` (due now) and `send-reminder` (due 2 h before the show), and calls off the `expire-hold` job.
4. **The worker.** Conveyor's HTTP worker claims the email job (`FOR UPDATE SKIP LOCKED`) and calls `POST /internal/tasks/send-ticket-email` with a shared secret. The API renders the PDF, sends the email and answers 200. If the mail server is down, the API answers 500 and Conveyor retries with backoff.
5. **Reminder.** Two hours before the show the reminder job becomes due and the same thing happens.
6. **If they never pay.** `expire-hold` runs, sees the booking is still `HELD`, marks it `EXPIRED` and adds `notify-waitlist`.

## Design decisions (and why)

### Why not just send the email inside the request?

The SMTP call can take seconds or fail. If it fails, should the booking fail too? The user already paid. And if the server crashes between "payment saved" and "email sent", the email is lost forever. A job queue makes the email a durable to-do item that is retried until it works.

### The transactional outbox

The naive version is "save the booking, then push a message to a queue". That's two separate systems, and the app can crash between the two writes (the *dual write* problem). Because Conveyor stores jobs in PostgreSQL, the job row is written **in the same transaction** as the booking. Either both exist or neither does. See `TaskQueue.enqueue`, a plain `INSERT INTO jobs` through `JdbcTemplate`, which joins the JPA transaction that's already open.

### At-least-once delivery means handlers must be idempotent

Suppose the worker sends the email but the HTTP response times out. Conveyor can't know the email went out, so it retries. Every handler therefore checks first whether there is still anything to do:

- `send-ticket-email`: skips if `ticket_emailed_at` is set
- `send-reminder` / `expire-hold`: skip unless the booking is still in the right state
- idempotency keys (`ticket-email:{id}`): a double-clicked Pay can't add the job twice (`ON CONFLICT DO NOTHING`)

### Preventing double booking

Two layers, both in the database, so it holds even with several API servers:

1. **Partial unique index.** At most one `HELD` or `CONFIRMED` booking per seat. Expired and cancelled ones don't count, so a seat keeps its history and can be sold again.
2. **Compare-and-set updates.** Every state change is `UPDATE ... WHERE status = <expected>`. The row count says whether you won. No locks are held while the user is thinking.

The test `twoPeopleClickingTheSameSeatAtOnceOnlyOneGetsIt` has 8 users request the same seat at the same moment from 8 threads, and checks that exactly one gets it.

### Hold expiry: why both a job and a lazy check?

The `expire-hold` job frees the seat on time and tells the waitlist. But if the worker is down, an expired hold would block the seat. So when someone asks for a seat, any expired hold on it is cleared first (`expireStaleHoldOnSeat`). Correctness doesn't depend on the worker; the worker only makes things timely.

### Waitlist

When a seat frees up, `notify-waitlist` locks the longest-waiting entry with `FOR UPDATE SKIP LOCKED`, marks it notified, and emails them in one transaction. If the email fails, the transaction rolls back, the person stays first in line, and the retry tries them again. The email says a seat is free; it doesn't reserve it, so it's first come, first served (like BookMyShow alerts).

### Why does the worker call back over HTTP?

Conveyor is written in TypeScript and the app in Java. Instead of rewriting the queue client in Java, the worker delivers jobs as HTTP calls, the way Google Cloud Tasks does. The business logic stays in the Java app, and any language can be a job handler. The status code tells Conveyor what to do: 2xx done, 5xx retry, other 4xx give up (dead-letter list).

### Security

- **JWT with a role claim.** `ROLE_ADMIN` protects creating events and check-in. Unauthenticated requests get 401, not a redirect.
- **The internal task endpoint** has no JWT but needs `X-Conveyor-Secret`. It's compared with `MessageDigest.isEqual` (constant time, so response timing doesn't leak the secret).
- **Secrets** live in `backend/.env`, which git ignores.
- **Registration** never returns the password hash, and emails are unique regardless of case.

### Migrating the existing database

The old tables had been created by hand, so Flyway uses `baseline-on-migrate` with version 0. `V1` only creates tables that don't exist yet (`CREATE TABLE IF NOT EXISTS`). `V2` adds columns, backfills booking references for old rows, and swaps the old "one booking per seat ever" constraint for the partial index. It finds that constraint by its columns, because its name was auto-generated. Hibernate runs with `ddl-auto=validate`: it checks the schema but never changes it.

## Likely interview questions

**Q: What happens if two users book the last seat at the same millisecond?**
Both inserts reach PostgreSQL. The unique index lets exactly one commit, and the other gets a unique-violation error, which I turn into a 409 "seat just taken". It's the database deciding, so it works across threads and across servers.

**Q: Why not use `synchronized` or a Java lock?**
It only works inside one JVM. Run two instances behind a load balancer and it's useless. The database is the one thing all instances share.

**Q: Why not pessimistic locking (`SELECT ... FOR UPDATE`) on the seat?**
It would work, but you'd hold a lock while doing other work. A unique index plus conditional updates needs no lock-holding at all, and the losing request fails immediately.

**Q: What if the worker is down for an hour?**
Jobs wait in the table. Nothing is lost. When it comes back, it catches up. Holds still expire correctly thanks to the lazy check. Emails arrive late but they arrive.

**Q: What if the email provider is down?**
The handler throws, the API answers 500, and Conveyor retries with exponential backoff and jitter, up to 8 attempts. After that the job is in the dead-letter list on the dashboard, where it can be retried with one click.

**Q: Can a user get two ticket emails?**
Very unlikely, but not impossible: if the email is sent and the app crashes before saving `ticket_emailed_at`, the retry sends it again. True exactly-once delivery over SMTP isn't possible. The design makes duplicates rare and harmless instead of losing emails.

**Q: How does the reminder get cancelled when a booking is cancelled?**
Two ways. Cancelling marks the pending reminder job as cancelled, found by its idempotency key (`reminder:{id}`). And even if that missed, the handler checks the booking is still `CONFIRMED` before sending.

**Q: How do you test background jobs?**
The integration tests run the real app against PostgreSQL. They check that the right job rows exist after an action, then call the task endpoint directly, the same way the worker would, and assert the result. That includes calling it twice to prove it's idempotent. Emails go to an outbox folder, so tests can open the PDF.

**Q: How does the QR code check-in work?**
The QR encodes `.../checkin?ref=TKT-XXXXXXXX`. The admin page looks up the ticket and checks it in with `UPDATE ... SET checked_in_at = now() WHERE reference = ? AND status = 'CONFIRMED' AND checked_in_at IS NULL`. A second scan changes 0 rows, so it says "Already used: this ticket was checked in at ...".

**Q: Why are booking references like `TKT-7KQ2M9XC`?**
They're random (not guessable from the database id), short enough to read over a phone, and leave out 0/O and 1/I so nobody confuses them.

## Limitations (say these before they ask)

- **Payment is mocked.** A real gateway such as Razorpay would call a webhook. That webhook would do exactly what `confirm` does now, so it slots in at the same place.
- **Times use the server's local time** (`LocalDateTime`). Fine for one city. A multi-region app would store UTC (`timestamptz`).
- **The JWT is in `localStorage`.** Simple, but readable by any script on the page. Production would use an httpOnly cookie with refresh tokens.
- **One price per event**, with no seat categories.

## Demo script (3 minutes)

1. Open the app and an event, then pick two seats. Show the countdown.
2. Open the same event in a private window as another user. Those seats show as held.
3. Pay in the first window. Open the Conveyor dashboard: the `send-ticket-email` job succeeded and `send-reminder` is scheduled.
4. Open `backend/outbox` and show the email and the PDF with the QR code.
5. Stop the worker, cancel a booking, start the worker again. The cancellation email still arrives.
6. As admin, open Check-in, scan the ticket, scan it again: "Already used".

## Resume bullets

- Built a ticket booking platform (Spring Boot, PostgreSQL, React) with 10-minute seat holds, emailed PDF/QR tickets, reminders, waitlist and QR check-in. Double booking is prevented at the database level (partial unique index + compare-and-set updates), with a concurrent-request test to prove it.
- Moved all slow or delayed work (emails, hold expiry, reminders) to background jobs on my own PostgreSQL job queue (Conveyor) using the transactional outbox pattern, with idempotent handlers for at-least-once delivery and retries with backoff.
- Wrote Flyway migrations that upgrade the existing schema in place, plus integration tests against real PostgreSQL that run in GitHub Actions CI.
