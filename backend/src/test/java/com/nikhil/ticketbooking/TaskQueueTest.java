package com.nikhil.ticketbooking;

import com.nikhil.ticketbooking.tasks.TaskQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Java side of the contract with Conveyor: rows in its jobs table. */
class TaskQueueTest extends IntegrationTest {

    @Autowired
    TaskQueue taskQueue;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aTaskOnlyExistsIfItsTransactionCommits() {
        tx.executeWithoutResult(status -> {
            taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-ticket-email", Map.of("bookingId", 1));
            status.setRollbackOnly(); // e.g. the booking failed halfway
        });
        assertThat(jobs()).isEmpty();

        tx.executeWithoutResult(status ->
                taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-ticket-email", Map.of("bookingId", 1)));
        assertThat(jobs()).containsExactly("send-ticket-email:queued");
    }

    @Test
    void sameIdempotencyKeyIsQueuedOnceAndCanBeCancelled() {
        tx.executeWithoutResult(s -> {
            taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-reminder", Map.of("bookingId", 7), Duration.ofHours(5), "reminder:7");
            taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-reminder", Map.of("bookingId", 7), Duration.ofHours(5), "reminder:7");
        });
        assertThat(jobs()).containsExactly("send-reminder:queued");
        assertThat(jdbc.queryForObject("SELECT payload->>'bookingId' FROM jobs", String.class)).isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT run_at > now() + interval '4 hours' FROM jobs", Boolean.class)).isTrue();

        Integer cancelled = tx.execute(s -> taskQueue.cancel(TaskQueue.BOOKING_EMAILS, "reminder:7", "test"));
        assertThat(cancelled).isEqualTo(1);
        assertThat(jobs()).containsExactly("send-reminder:cancelled");
        // Conveyor's timeline for the job records why.
        assertThat(jdbc.queryForList("SELECT type FROM job_events ORDER BY id", String.class))
                .containsExactly("enqueued", "cancelled");
    }

    @Test
    void payloadIsValidJsonEvenWithAwkwardText() {
        tx.executeWithoutResult(s -> taskQueue.enqueue(TaskQueue.BOOKING_TASKS, "x",
                Map.of("note", "He said \"hi\"\nand left \\ ok")));
        assertThat(jdbc.queryForObject("SELECT payload->>'note' FROM jobs", String.class))
                .isEqualTo("He said \"hi\"\nand left \\ ok");
    }
}
