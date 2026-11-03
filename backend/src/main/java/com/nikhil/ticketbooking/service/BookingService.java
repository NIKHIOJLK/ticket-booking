package com.nikhil.ticketbooking.service;

import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.dto.BookingResponse;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;
import com.nikhil.ticketbooking.model.Event;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.model.User;
import com.nikhil.ticketbooking.repository.BookingRepository;
import com.nikhil.ticketbooking.repository.SeatRepository;
import com.nikhil.ticketbooking.repository.UserRepository;
import com.nikhil.ticketbooking.repository.WaitlistRepository;
import com.nikhil.ticketbooking.tasks.TaskQueue;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Booking a seat is two steps, like on real ticketing sites:
 *   1. hold  - the seat is reserved for you for a few minutes (HOLD_DURATION)
 *   2. confirm - you "pay" (a demo step here) and the booking becomes CONFIRMED
 * A hold that isn't confirmed in time is released by a scheduled background task.
 */
@Service
public class BookingService {

    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a");
    private static final Duration EXPIRY_GRACE = Duration.ofSeconds(2);

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final WaitlistRepository waitlistRepository;
    private final TaskQueue taskQueue;
    private final AppProperties.Booking rules;
    private final SecureRandom random = new SecureRandom();

    // Spring automatically supplies these when it creates this class —
    // this pattern is called "constructor injection".
    public BookingService(BookingRepository bookingRepository, SeatRepository seatRepository,
                          UserRepository userRepository, WaitlistRepository waitlistRepository,
                          TaskQueue taskQueue, AppProperties props) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.waitlistRepository = waitlistRepository;
        this.taskQueue = taskQueue;
        this.rules = props.booking();
    }

    // ------------------------------------------------------------------ step 1: hold

    @Transactional
    public BookingResponse holdSeat(Long seatId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> ApiException.notFound("Seat not found"));
        Event event = seat.getEvent();
        if (!event.getEventDate().isAfter(now)) {
            throw ApiException.conflict("This event has already started, so it can't be booked any more");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in again"));

        // A hold whose time ran out must not block the seat, even if the background
        // task that releases it hasn't run yet (e.g. the worker is down).
        bookingRepository.expireStaleHoldOnSeat(seatId, now);

        long mine = bookingRepository.countOccupyingForUserAndEvent(userId, event.getId(), now);
        if (mine >= rules.maxSeatsPerUser()) {
            throw ApiException.conflict("You can book at most " + rules.maxSeatsPerUser() + " seats for this event");
        }

        Booking booking = new Booking();
        booking.setSeat(seat);
        booking.setUser(user);
        booking.setStatus(BookingStatus.HELD);
        booking.setReference(newReference());
        booking.setBookedAt(now);
        booking.setHoldExpiresAt(now.plus(rules.holdDuration()));

        try {
            booking = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            // Postgres rejected a second active booking for this seat
            // (unique index bookings_one_active_per_seat_uidx), now caught in Java.
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("bookings_one_active_per_seat_uidx") || detail.contains("seat_id")) {
                throw new SeatAlreadyBookedException("Seat " + seat.getSeatLabel() + " was just taken by someone else");
            }
            throw e;
        }

        // Someone who was on the waitlist and got a seat doesn't need to stay on it.
        waitlistRepository.deleteForUser(event.getId(), userId);

        // Release the seat automatically if it isn't paid for in time.
        taskQueue.enqueue(TaskQueue.BOOKING_TASKS, "expire-hold", Map.of("bookingId", booking.getId()),
                rules.holdDuration().plus(EXPIRY_GRACE), "expire-hold:" + booking.getId());

        return BookingResponse.of(booking, false);
    }

    // ------------------------------------------------------------------ step 2: confirm

    @Transactional
    public BookingResponse confirm(Long bookingId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        int changed = bookingRepository.confirmHold(bookingId, userId, now);
        Booking booking = findOwned(bookingId, userId);

        if (changed == 0) {
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                return toResponse(booking, now); // already confirmed (double-click): nothing to do
            }
            if (booking.getStatus() == BookingStatus.HELD || booking.getStatus() == BookingStatus.EXPIRED) {
                throw new ApiException(HttpStatus.GONE,
                        "Your hold on seat " + booking.getSeat().getSeatLabel() + " ran out. Pick the seat again.");
            }
            throw ApiException.conflict("This booking was cancelled");
        }

        // Payment would happen here; this demo app skips it.

        // Both tasks are written in this same transaction (see TaskQueue).
        taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-ticket-email", Map.of("bookingId", bookingId),
                Duration.ZERO, "ticket-email:" + bookingId);

        LocalDateTime remindAt = booking.getEvent().getEventDate().minus(rules.reminderBefore());
        if (remindAt.isAfter(now)) {
            taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-reminder", Map.of("bookingId", bookingId),
                    Duration.between(now, remindAt), "reminder:" + bookingId);
        }

        // The hold can no longer expire, so its scheduled release isn't needed.
        taskQueue.cancel(TaskQueue.BOOKING_TASKS, "expire-hold:" + bookingId, "Booking was confirmed in time");
        return toResponse(booking, now);
    }

    // ------------------------------------------------------------------ giving seats back

    /** The user changed their mind before paying. */
    @Transactional
    public BookingResponse release(Long bookingId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        int changed = bookingRepository.cancel(bookingId, userId, BookingStatus.HELD, now);
        Booking booking = findOwned(bookingId, userId);
        if (changed == 0) {
            if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.EXPIRED) {
                return toResponse(booking, now); // already released
            }
            throw ApiException.conflict("This seat is already paid for. Use cancel instead.");
        }
        taskQueue.cancel(TaskQueue.BOOKING_TASKS, "expire-hold:" + bookingId, "Hold was released by the user");
        seatFreed(booking.getEvent());
        return toResponse(booking, now);
    }

    @Transactional
    public BookingResponse cancel(Long bookingId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Booking booking = findOwned(bookingId, userId);
        if (booking.getStatus() == BookingStatus.CANCELLED) return toResponse(booking, now);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("Only confirmed bookings can be cancelled");
        }
        if (booking.getCheckedInAt() != null) {
            throw ApiException.conflict("This ticket was already used at the entrance");
        }
        LocalDateTime cutoff = booking.getEvent().getEventDate().minus(rules.cancelCutoff());
        if (!now.isBefore(cutoff)) {
            throw ApiException.conflict("Bookings can't be cancelled less than "
                    + rules.cancelCutoff().toHours() + " hours before the show (cutoff was " + TIME.format(cutoff) + ")");
        }

        int changed = bookingRepository.cancel(bookingId, userId, BookingStatus.CONFIRMED, now);
        if (changed == 0) throw ApiException.conflict("This booking changed meanwhile. Refresh and try again.");

        taskQueue.cancel(TaskQueue.BOOKING_EMAILS, "reminder:" + bookingId, "Booking was cancelled");
        taskQueue.enqueue(TaskQueue.BOOKING_EMAILS, "send-cancellation-email", Map.of("bookingId", bookingId),
                Duration.ZERO, "cancel-email:" + bookingId);
        seatFreed(booking.getEvent());
        return toResponse(findOwned(bookingId, userId), now);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<BookingResponse> listForUser(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return bookingRepository.findAllForUser(userId).stream().map(b -> toResponse(b, now)).toList();
    }

    @Transactional(readOnly = true)
    public Booking getConfirmedForUser(Long bookingId, Long userId) {
        Booking booking = findOwned(bookingId, userId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("Only confirmed bookings have a ticket");
        }
        return booking;
    }

    // ------------------------------------------------------------------ helpers

    /** A seat became free: let the next person on the waitlist know (in the background). */
    void seatFreed(Event event) {
        if (event.getEventDate().isAfter(LocalDateTime.now())) {
            taskQueue.enqueue(TaskQueue.BOOKING_TASKS, "notify-waitlist", Map.of("eventId", event.getId()));
        }
    }

    private Booking findOwned(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            throw ApiException.notFound("Booking not found"); // don't reveal other people's bookings
        }
        return booking;
    }

    private BookingResponse toResponse(Booking b, LocalDateTime now) {
        boolean cancellable = b.getStatus() == BookingStatus.CONFIRMED && b.getCheckedInAt() == null
                && now.isBefore(b.getEvent().getEventDate().minus(rules.cancelCutoff()));
        return BookingResponse.of(b, cancellable);
    }

    private String newReference() {
        StringBuilder sb = new StringBuilder("TKT-");
        for (int i = 0; i < 8; i++) {
            sb.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
