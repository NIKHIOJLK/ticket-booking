package com.nikhil.ticketbooking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The tasks Conveyor delivers to /internal/tasks/{name}: emails, waitlist, and the secret check. */
class BackgroundTasksTest extends IntegrationTest {

    private static final Path OUTBOX = Path.of("target/test-outbox");

    private String admin;
    private long eventId;

    @BeforeEach
    void setUp() throws Exception {
        admin = signUp(ADMIN_EMAIL);
        eventId = createEvent(admin, LocalDateTime.now().plusDays(10), 1, 2);
        if (Files.exists(OUTBOX)) {
            try (Stream<Path> files = Files.walk(OUTBOX)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void tasksWithoutTheSecretAreRefused() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/tasks/expire-hold"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":{\"bookingId\":1}}"))
                .header("Content-Type", "application/json")
                .header("X-Conveyor-Secret", "guess")
                .build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void unknownTasksAndBadPayloadsAreNotRetried() throws Exception {
        assertThat(runTask("make-coffee", "{}").status()).isEqualTo(404);
        assertThat(runTask("send-ticket-email", "{\"bookingId\":\"abc\"}").status()).isEqualTo(422);
    }

    @Test
    void ticketEmailHasAPdfAttachedAndIsOnlySentOnce() throws Exception {
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(eventId, "A1")));
        post("/api/bookings/" + bookingId + "/confirm", user, null);

        Res first = runTask("send-ticket-email", "{\"bookingId\":" + bookingId + "}");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json()).containsEntry("outcome", "done");

        List<Path> pdfs = filesNamed(".pdf");
        assertThat(pdfs).hasSize(1);
        assertThat(Files.readString(pdfs.getFirst().resolveSibling("email.html")))
                .contains("meera@example.com").contains("Your ticket for Arijit Singh Live");
        assertThat(new String(Files.readAllBytes(pdfs.getFirst()), 0, 4)).isEqualTo("%PDF");

        // A retry (Conveyor delivers at least once) must not email the ticket twice.
        assertThat(runTask("send-ticket-email", "{\"bookingId\":" + bookingId + "}").json())
                .containsEntry("outcome", "skipped");
        assertThat(filesNamed(".pdf")).hasSize(1);
    }

    @Test
    void reminderIsSkippedForACancelledBooking() throws Exception {
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(eventId, "A1")));
        post("/api/bookings/" + bookingId + "/confirm", user, null);
        post("/api/bookings/" + bookingId + "/cancel", user, null);
        assertThat(runTask("send-reminder", "{\"bookingId\":" + bookingId + "}").json()).containsEntry("outcome", "skipped");
        assertThat(runTask("send-cancellation-email", "{\"bookingId\":" + bookingId + "}").json())
                .containsEntry("outcome", "done");
    }

    @Test
    void waitlistOnlyOpensWhenSoldOutAndTheLongestWaitingPersonIsToldFirst() throws Exception {
        String early = signUp("early@example.com");
        String late = signUp("late@example.com");
        assertThat(post("/api/events/" + eventId + "/waitlist", early, null).status()).isEqualTo(409); // seats free

        // Sell out both seats.
        String buyer = signUp("buyer@example.com");
        long a1 = id(hold(buyer, seatId(eventId, "A1")));
        long a2 = id(hold(buyer, seatId(eventId, "A2")));
        post("/api/bookings/" + a1 + "/confirm", buyer, null);
        post("/api/bookings/" + a2 + "/confirm", buyer, null);
        assertThat(get("/api/events/" + eventId, early).json()).containsEntry("soldOut", true);

        assertThat(post("/api/events/" + eventId + "/waitlist", early, null).json()).containsEntry("onWaitlist", true);
        Thread.sleep(20);
        post("/api/events/" + eventId + "/waitlist", late, null);

        // One seat frees up -> exactly one person (the earliest) is emailed.
        post("/api/bookings/" + a1 + "/cancel", buyer, null);
        Res notify = runTask("notify-waitlist", "{\"eventId\":" + eventId + "}");
        assertThat(notify.json()).containsEntry("outcome", "done");
        assertThat(notify.json().get("message").toString()).contains("early@example.com");
        assertThat(get("/api/events/" + eventId, early).json()).containsEntry("onWaitlist", false);
        assertThat(get("/api/events/" + eventId, late).json()).containsEntry("onWaitlist", true);

        // If someone grabs the seat before the next notification runs, nobody else is emailed.
        hold(signUp("quick@example.com"), seatId(eventId, "A1"));
        assertThat(runTask("notify-waitlist", "{\"eventId\":" + eventId + "}").json())
                .containsEntry("outcome", "skipped");
    }

    @Test
    void checkInWorksOnceAndOnlyForConfirmedTickets() throws Exception {
        String user = signUp("meera@example.com");
        Res held = hold(user, seatId(eventId, "A1"));
        String reference = String.valueOf(held.json().get("reference"));

        assertThat(post("/api/checkin/" + reference, admin, null).status()).isEqualTo(409); // not paid yet
        post("/api/bookings/" + id(held) + "/confirm", user, null);

        Res lookUp = get("/api/checkin/" + reference.toLowerCase(), admin);
        assertThat(lookUp.json()).containsEntry("seatLabel", "A1").containsEntry("holderEmail", "meera@example.com");

        assertThat(post("/api/checkin/" + reference, admin, null).status()).isEqualTo(200);
        Res second = post("/api/checkin/" + reference, admin, null);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.error()).contains("Already used");

        // A used ticket can no longer be cancelled.
        assertThat(post("/api/bookings/" + id(held) + "/cancel", user, null).status()).isEqualTo(409);
        assertThat(get("/api/checkin/TKT-NOPE2345", admin).status()).isEqualTo(404);
    }

    private List<Path> filesNamed(String suffix) throws Exception {
        if (!Files.exists(OUTBOX)) return List.of();
        try (Stream<Path> files = Files.walk(OUTBOX)) {
            return files.filter(p -> p.toString().endsWith(suffix)).toList();
        }
    }
}
