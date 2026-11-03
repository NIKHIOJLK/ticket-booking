package com.nikhil.ticketbooking.tasks;

import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.mail.EmailSender;
import com.nikhil.ticketbooking.mail.EmailTemplates;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;
import com.nikhil.ticketbooking.model.Event;
import com.nikhil.ticketbooking.model.WaitlistEntry;
import com.nikhil.ticketbooking.repository.BookingRepository;
import com.nikhil.ticketbooking.repository.EventRepository;
import com.nikhil.ticketbooking.repository.WaitlistRepository;
import com.nikhil.ticketbooking.tickets.TicketPdf;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * The background tasks. Conveyor's HTTP worker calls these through TaskController.
 *
 * Every task may run more than once (Conveyor delivers at least once: a retry after a
 * timeout can repeat work that actually succeeded). So each one first checks whether
 * there is still anything to do, and does nothing if not. That makes them safe to repeat.
 */
@Service
public class TaskService {

    /** Thrown to ask Conveyor to try again later (HTTP 503). */
    public static class RetryLaterException extends RuntimeException {
        public RetryLaterException(String message) {
            super(message);
        }
    }

    public record Result(String outcome, String message) {
        static Result done(String message) { return new Result("done", message); }
        static Result skipped(String message) { return new Result("skipped", message); }
    }

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final WaitlistRepository waitlistRepository;
    private final TaskQueue taskQueue;
    private final EmailSender emailSender;
    private final TicketPdf ticketPdf;
    private final String frontendUrl;

    public TaskService(BookingRepository bookingRepository, EventRepository eventRepository,
                       WaitlistRepository waitlistRepository, TaskQueue taskQueue, EmailSender emailSender,
                       TicketPdf ticketPdf, AppProperties props) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.waitlistRepository = waitlistRepository;
        this.taskQueue = taskQueue;
        this.emailSender = emailSender;
        this.ticketPdf = ticketPdf;
        this.frontendUrl = props.frontendUrl().replaceAll("/+$", "");
    }

    public static class UnknownTaskException extends RuntimeException {
        public UnknownTaskException(String task) {
            super("Unknown task '" + task + "'");
        }
    }

    // ------------------------------------------------------------------ the tasks

    /** Scheduled for when a hold runs out: free the seat if it still isn't paid for. */
    @Transactional
    public Result expireHold(Long bookingId) {
        LocalDateTime now = LocalDateTime.now();
        Optional<Booking> found = bookingRepository.findById(bookingId);
        if (found.isEmpty()) return Result.skipped("Booking " + bookingId + " no longer exists");
        Booking booking = found.get();
        if (booking.getStatus() != BookingStatus.HELD) {
            return Result.skipped("Booking is " + booking.getStatus() + ", nothing to release");
        }
        if (booking.getHoldExpiresAt().isAfter(now)) {
            throw new RetryLaterException("Hold only ends at " + booking.getHoldExpiresAt());
        }
        if (bookingRepository.expireHold(bookingId, now) == 0) {
            return Result.skipped("Booking changed meanwhile");
        }
        Event event = booking.getEvent();
        if (event.getEventDate().isAfter(now)) {
            taskQueue.enqueue(TaskQueue.BOOKING_TASKS, "notify-waitlist", Map.of("eventId", event.getId()));
        }
        return Result.done("Released seat " + booking.getSeat().getSeatLabel() + " of " + event.getName());
    }

    public Result sendTicketEmail(Long bookingId) throws Exception {
        Optional<Booking> found = bookingRepository.findById(bookingId);
        if (found.isEmpty()) return Result.skipped("Booking " + bookingId + " no longer exists");
        Booking booking = found.get();
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            return Result.skipped("Booking is " + booking.getStatus() + ", no ticket to send");
        }
        if (booking.getTicketEmailedAt() != null) {
            return Result.skipped("Ticket was already emailed at " + booking.getTicketEmailedAt());
        }
        byte[] pdf = ticketPdf.render(booking);
        String where = emailSender.send(EmailTemplates.ticket(booking, pdf));
        bookingRepository.markTicketEmailed(bookingId, LocalDateTime.now());
        return Result.done("Ticket " + booking.getReference() + " " + where);
    }

    public Result sendReminder(Long bookingId) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Optional<Booking> found = bookingRepository.findById(bookingId);
        if (found.isEmpty()) return Result.skipped("Booking " + bookingId + " no longer exists");
        Booking booking = found.get();
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            return Result.skipped("Booking is " + booking.getStatus() + ", no reminder needed");
        }
        if (!booking.getEvent().getEventDate().isAfter(now)) return Result.skipped("The event has already started");
        String where = emailSender.send(EmailTemplates.reminder(booking, now));
        return Result.done("Reminder " + where);
    }

    public Result sendCancellationEmail(Long bookingId) throws Exception {
        Optional<Booking> found = bookingRepository.findById(bookingId);
        if (found.isEmpty()) return Result.skipped("Booking " + bookingId + " no longer exists");
        Booking booking = found.get();
        if (booking.getStatus() != BookingStatus.CANCELLED) {
            return Result.skipped("Booking is " + booking.getStatus() + ", not cancelled");
        }
        String where = emailSender.send(EmailTemplates.cancellation(booking));
        return Result.done("Cancellation email " + where);
    }

    /**
     * A seat freed up: email the person who has waited longest. If the email fails, the
     * transaction rolls back, so they stay first in line and the retry tries them again.
     */
    @Transactional
    public Result notifyWaitlist(Long eventId) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Optional<Event> event = eventRepository.findById(eventId);
        if (event.isEmpty()) return Result.skipped("Event " + eventId + " no longer exists");
        if (!event.get().getEventDate().isAfter(now)) return Result.skipped("The event has already started");

        long free = bookingRepository.eventStats(now).stream()
                .filter(s -> s.eventId().equals(eventId)).findFirst()
                .map(s -> s.availableSeats()).orElse(0L);
        if (free == 0) return Result.skipped("No free seats any more (someone booked it first)");

        Optional<WaitlistEntry> next = waitlistRepository.lockNextWaiting(eventId);
        if (next.isEmpty()) return Result.skipped("Nobody is on the waitlist");
        WaitlistEntry entry = next.get();
        entry.setNotifiedAt(now);
        waitlistRepository.save(entry);

        String url = frontendUrl + "/events/" + eventId;
        emailSender.send(EmailTemplates.waitlistSeatFree(entry.getUser().getEmail(), event.get(), url));
        return Result.done("Told " + entry.getUser().getEmail() + " that a seat is free");
    }

}
