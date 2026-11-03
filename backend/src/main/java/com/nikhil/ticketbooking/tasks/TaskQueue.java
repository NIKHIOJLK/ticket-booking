package com.nikhil.ticketbooking.tasks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adds background tasks to Conveyor's job queue (the "jobs" table in this database).
 *
 * The INSERT runs on the same database connection and transaction as the JPA code
 * calling it. So a task is created if and only if the business change that needs it is
 * committed: a confirmed booking always gets its ticket email, and a booking that fails
 * halfway never sends one. This is the "transactional outbox" pattern.
 *
 * Conveyor's HTTP worker later delivers each task to POST /internal/tasks/{name}.
 */
@Component
public class TaskQueue {

    /** Time-critical state changes (hold expiry) are kept apart from emails, so a slow
     *  or failing email provider can never delay a seat being released. */
    public static final String BOOKING_TASKS = "booking-tasks";
    public static final String BOOKING_EMAILS = "booking-emails";

    private final JdbcTemplate jdbc;

    public TaskQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param delay          run no earlier than this from now (Duration.ZERO = as soon as possible)
     * @param idempotencyKey a second enqueue with the same key (in the same queue) is ignored;
     *                       also used to cancel the task later. May be null.
     */
    public void enqueue(String queue, String name, Map<String, ?> payload, Duration delay, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO jobs (queue, name, payload, run_at, max_attempts, backoff_base_ms, timeout_ms, idempotency_key)
                VALUES (?, ?, ?::jsonb, now() + make_interval(secs => ?), ?, ?, ?, ?)
                ON CONFLICT (queue, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING""",
                queue, name, toJson(payload), Math.max(0, delay.toMillis()) / 1000.0,
                8, 2000, 30_000, idempotencyKey);
    }

    public void enqueue(String queue, String name, Map<String, ?> payload) {
        enqueue(queue, name, payload, Duration.ZERO, null);
    }

    /** Cancels a task that hasn't started yet (e.g. the reminder for a cancelled booking). */
    public int cancel(String queue, String idempotencyKey, String reason) {
        return jdbc.update("""
                WITH c AS (
                  UPDATE jobs SET state = 'cancelled', finished_at = now(), updated_at = now()
                  WHERE queue = ? AND idempotency_key = ? AND state = 'queued'
                  RETURNING id
                )
                INSERT INTO job_events (job_id, type, message) SELECT id, 'cancelled', ? FROM c""",
                queue, idempotencyKey, reason);
    }

    /** Payloads here are tiny and flat (ids and short strings), so a small writer is enough. */
    static String toJson(Map<String, ?> payload) {
        return payload.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + value(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quote(v.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
