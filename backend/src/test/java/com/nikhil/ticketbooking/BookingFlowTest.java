package com.nikhil.ticketbooking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class BookingFlowTest extends IntegrationTest {

    private String admin;
    private long eventId;

    @BeforeEach
    void setUp() throws Exception {
        admin = signUp(ADMIN_EMAIL);
        eventId = createEvent(admin, LocalDateTime.now().plusDays(10), 2, 4);
    }

    @Test
    void holdThenConfirmQueuesTheTicketEmailAndTheReminderInTheSameTransaction() throws Exception {
        String user = signUp("meera@example.com");
        Res held = hold(user, seatId(eventId, "A1"));
        assertThat(held.status()).isEqualTo(201);
        assertThat(held.json()).containsEntry("status", "HELD");
        assertThat(String.valueOf(held.json().get("reference"))).matches("TKT-[A-Z2-9]{8}");
        assertThat(jobs()).containsExactly("expire-hold:queued");

        Res confirmed = post("/api/bookings/" + id(held) + "/confirm", user, null);
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.json()).containsEntry("status", "CONFIRMED").containsEntry("cancellable", true);

        // The release is no longer needed; the ticket email and the reminder are queued.
        assertThat(jobs()).containsExactly("expire-hold:cancelled", "send-ticket-email:queued", "send-reminder:queued");
        // The reminder is scheduled for 2 hours before the event, not now.
        assertThat(jdbc.queryForObject("SELECT run_at > now() + interval '9 days' FROM jobs WHERE name = 'send-reminder'",
                Boolean.class)).isTrue();
    }

    @Test
    void confirmingTwiceIsHarmless() throws Exception {
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(eventId, "A1")));
        assertThat(post("/api/bookings/" + bookingId + "/confirm", user, null).status()).isEqualTo(200);
        assertThat(post("/api/bookings/" + bookingId + "/confirm", user, null).status()).isEqualTo(200);
        assertThat(jobs()).filteredOn(j -> j.startsWith("send-ticket-email")).hasSize(1);
    }

    @Test
    void twoPeopleClickingTheSameSeatAtOnceOnlyOneGetsIt() throws Exception {
        long seat = seatId(eventId, "B2");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) tokens.add(signUp("fan" + i + "@example.com"));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        List<Future<Integer>> results = new ArrayList<>();
        for (String token : tokens) {
            Callable<Integer> attempt = () -> {
                go.await();
                return hold(token, seat).status();
            };
            results.add(pool.submit(attempt));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(tokens.size() - 1);
    }

    @Test
    void aHoldThatRunsOutCantBeConfirmedAndTheSeatIsReleased() throws Exception {
        String slow = signUp("slow@example.com");
        long seat = seatId(eventId, "A2");
        long bookingId = id(hold(slow, seat));

        Thread.sleep(3500); // test hold duration is 3 s

        Res late = post("/api/bookings/" + bookingId + "/confirm", slow, null);
        assertThat(late.status()).isEqualTo(410);
        assertThat(late.error()).contains("ran out");

        // The scheduled task (normally run by Conveyor) releases it and asks the waitlist task to run.
        Res task = runTask("expire-hold", "{\"bookingId\":" + bookingId + "}");
        assertThat(task.status()).isEqualTo(200);
        assertThat(task.json()).containsEntry("outcome", "done");
        assertThat(status(bookingId)).isEqualTo("EXPIRED");
        assertThat(jobs()).contains("notify-waitlist:queued");

        // Running it again (Conveyor may retry) does nothing.
        assertThat(runTask("expire-hold", "{\"bookingId\":" + bookingId + "}").json()).containsEntry("outcome", "skipped");
    }

    @Test
    void anExpiredHoldDoesNotBlockTheSeatEvenIfTheWorkerIsDown() throws Exception {
        long seat = seatId(eventId, "A3");
        hold(signUp("first@example.com"), seat);
        Thread.sleep(3500);
        // No task has run, but the hold is over: the next person can take the seat.
        assertThat(hold(signUp("second@example.com"), seat).status()).isEqualTo(201);
    }

    @Test
    void anEarlyExpiryTaskAsksToBeRetriedLater() throws Exception {
        long bookingId = id(hold(signUp("meera@example.com"), seatId(eventId, "A4")));
        Res early = runTask("expire-hold", "{\"bookingId\":" + bookingId + "}");
        assertThat(early.status()).isEqualTo(503);
        assertThat(status(bookingId)).isEqualTo("HELD");
    }

    @Test
    void releasingAHoldFreesTheSeat() throws Exception {
        String user = signUp("meera@example.com");
        long seat = seatId(eventId, "B1");
        long bookingId = id(hold(user, seat));
        assertThat(post("/api/bookings/" + bookingId + "/release", user, null).status()).isEqualTo(200);
        assertThat(status(bookingId)).isEqualTo("CANCELLED");
        assertThat(jobs()).contains("expire-hold:cancelled", "notify-waitlist:queued");
        assertThat(hold(signUp("other@example.com"), seat).status()).isEqualTo(201);
    }

    @Test
    void cancellingAConfirmedBookingCancelsTheReminderAndQueuesTheEmail() throws Exception {
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(eventId, "A1")));
        post("/api/bookings/" + bookingId + "/confirm", user, null);

        Res cancelled = post("/api/bookings/" + bookingId + "/cancel", user, null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json()).containsEntry("status", "CANCELLED");
        assertThat(jobs()).contains("send-reminder:cancelled", "send-cancellation-email:queued", "notify-waitlist:queued");

        // The seat can be booked again; the cancelled booking stays as history.
        assertThat(hold(signUp("next@example.com"), seatId(eventId, "A1")).status()).isEqualTo(201);
    }

    @Test
    void noCancellingWithinTwoHoursOfTheShow() throws Exception {
        long soonEvent = createEvent(admin, LocalDateTime.now().plusMinutes(90), 1, 2);
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(soonEvent, "A1")));
        Res confirmed = post("/api/bookings/" + bookingId + "/confirm", user, null);
        assertThat(confirmed.json()).containsEntry("cancellable", false);
        // ...and no reminder is scheduled, since "2 hours before" is already in the past.
        assertThat(jobs()).noneMatch(j -> j.startsWith("send-reminder"));

        Res res = post("/api/bookings/" + bookingId + "/cancel", user, null);
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.error()).contains("less than 2 hours");
    }

    @Test
    void atMostSixSeatsPerPersonPerEvent() throws Exception {
        String user = signUp("bulk@example.com");
        String[] seats = {"A1", "A2", "A3", "A4", "B1", "B2"};
        for (String s : seats) assertThat(hold(user, seatId(eventId, s)).status()).isEqualTo(201);
        Res seventh = hold(user, seatId(eventId, "B3"));
        assertThat(seventh.status()).isEqualTo(409);
        assertThat(seventh.error()).contains("at most 6");
    }

    @Test
    void youCantTouchSomeoneElsesBooking() throws Exception {
        long bookingId = id(hold(signUp("owner@example.com"), seatId(eventId, "A1")));
        String stranger = signUp("stranger@example.com");
        assertThat(post("/api/bookings/" + bookingId + "/confirm", stranger, null).status()).isEqualTo(404);
        assertThat(get("/api/bookings/" + bookingId + "/ticket.pdf", stranger).status()).isEqualTo(404);
    }

    @Test
    void seatMapShowsHeldBookedAndYourOwnSeats() throws Exception {
        String me = signUp("me@example.com");
        long myHold = id(hold(me, seatId(eventId, "A1")));
        String other = signUp("other@example.com");
        long theirs = id(hold(other, seatId(eventId, "A2")));
        post("/api/bookings/" + theirs + "/confirm", other, null);

        List<Object> seats = get("/api/events/" + eventId + "/seats", me).list();
        Map<?, ?> a1 = (Map<?, ?>) seats.get(0);
        Map<?, ?> a2 = (Map<?, ?>) seats.get(1);
        Map<?, ?> a3 = (Map<?, ?>) seats.get(2);
        assertThat(a1.get("status")).isEqualTo("HELD");
        assertThat(a1.get("mine")).isEqualTo(true);
        assertThat(((Number) a1.get("bookingId")).longValue()).isEqualTo(myHold);
        assertThat(a2.get("status")).isEqualTo("BOOKED");
        assertThat(a2.get("mine")).isEqualTo(false);
        assertThat(a2.get("bookingId")).isNull(); // other people's booking ids are not exposed
        assertThat(a3.get("status")).isEqualTo("AVAILABLE");

        Map<String, Object> event = get("/api/events/" + eventId, me).json();
        assertThat(((Number) event.get("availableSeats")).longValue()).isEqualTo(6);
    }

    @Test
    void myBookingsListsEverythingIncludingHistory() throws Exception {
        String user = signUp("meera@example.com");
        long a = id(hold(user, seatId(eventId, "A1")));
        post("/api/bookings/" + a + "/confirm", user, null);
        long b = id(hold(user, seatId(eventId, "A2")));
        post("/api/bookings/" + b + "/release", user, null);

        List<Object> mine = get("/api/bookings/me", user).list();
        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(o -> ((Map<?, ?>) o).get("status")).containsExactlyInAnyOrder("CONFIRMED", "CANCELLED");
    }

    @Test
    void ticketPdfCanBeDownloadedForConfirmedBookingsOnly() throws Exception {
        String user = signUp("meera@example.com");
        long bookingId = id(hold(user, seatId(eventId, "A1")));
        assertThat(get("/api/bookings/" + bookingId + "/ticket.pdf", user).status()).isEqualTo(409);
        post("/api/bookings/" + bookingId + "/confirm", user, null);
        Res pdf = get("/api/bookings/" + bookingId + "/ticket.pdf", user);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.body()).startsWith("%PDF");
    }

    private String status(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
