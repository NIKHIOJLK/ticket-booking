package com.nikhil.ticketbooking.service;

import com.nikhil.ticketbooking.dto.CreateEventRequest;
import com.nikhil.ticketbooking.dto.EventResponse;
import com.nikhil.ticketbooking.dto.EventStats;
import com.nikhil.ticketbooking.dto.SeatResponse;
import com.nikhil.ticketbooking.dto.SeatRow;
import com.nikhil.ticketbooking.model.BookingStatus;
import com.nikhil.ticketbooking.model.Event;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.model.User;
import com.nikhil.ticketbooking.model.WaitlistEntry;
import com.nikhil.ticketbooking.repository.BookingRepository;
import com.nikhil.ticketbooking.repository.EventRepository;
import com.nikhil.ticketbooking.repository.SeatRepository;
import com.nikhil.ticketbooking.repository.UserRepository;
import com.nikhil.ticketbooking.repository.WaitlistRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final WaitlistRepository waitlistRepository;
    private final UserRepository userRepository;

    public EventService(EventRepository eventRepository, SeatRepository seatRepository,
                        BookingRepository bookingRepository, WaitlistRepository waitlistRepository,
                        UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.waitlistRepository = waitlistRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<EventResponse> list() {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, EventStats> stats = stats(now);
        return eventRepository.findAllByOrderByEventDateAsc().stream()
                .map(e -> EventResponse.of(e, stats.get(e.getId()), now, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long eventId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Event event = find(eventId);
        Boolean onWaitlist = userId == null ? null
                : waitlistRepository.existsByEventIdAndUserIdAndNotifiedAtIsNull(eventId, userId);
        return EventResponse.of(event, stats(now).get(eventId), now, onWaitlist);
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> seats(Long eventId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<SeatResponse> result = new ArrayList<>();
        // One query for the whole map (the previous version ran one query per seat).
        for (SeatRow row : bookingRepository.seatMap(eventId, now)) {
            boolean mine = userId != null && userId.equals(row.userId());
            String status = row.status() == null ? "AVAILABLE"
                    : row.status() == BookingStatus.HELD ? "HELD" : "BOOKED";
            result.add(new SeatResponse(row.seatId(), row.seatLabel(), status, row.status() != null, mine,
                    mine ? row.bookingId() : null,
                    mine && row.status() == BookingStatus.HELD ? row.holdExpiresAt() : null));
        }
        return result;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Event event = new Event();
        event.setName(request.getName().trim());
        event.setVenue(request.getVenue().trim());
        event.setEventDate(request.getEventDate());
        event.setPrice(request.getPrice() != null ? request.getPrice() : new BigDecimal("250.00"));
        event.setCreatedAt(LocalDateTime.now());
        Event savedEvent = eventRepository.save(event);

        // Auto-generate seats from the row/column layout the organizer specified
        List<Seat> seats = new ArrayList<>();
        for (int row = 0; row < request.getRows(); row++) {
            char rowLetter = (char) ('A' + row);
            for (int col = 1; col <= request.getSeatsPerRow(); col++) {
                Seat seat = new Seat();
                seat.setEvent(savedEvent);
                seat.setSeatLabel(rowLetter + String.valueOf(col));
                seats.add(seat);
            }
        }
        seatRepository.saveAll(seats);
        long total = seats.size();
        return EventResponse.of(savedEvent, new EventStats(savedEvent.getId(), total, 0), LocalDateTime.now(), null);
    }

    // ------------------------------------------------------------------ waitlist

    @Transactional
    public EventResponse joinWaitlist(Long eventId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Event event = find(eventId);
        if (!event.getEventDate().isAfter(now)) throw ApiException.conflict("This event has already started");
        EventStats stats = stats(now).get(eventId);
        if (stats != null && stats.availableSeats() > 0) {
            throw ApiException.conflict("Seats are still available, so you can book one right away");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in again"));

        WaitlistEntry entry = waitlistRepository.findByEventIdAndUserId(eventId, userId).orElseGet(WaitlistEntry::new);
        entry.setEvent(event);
        entry.setUser(user);
        entry.setCreatedAt(now);     // (re)joining puts you at the back of the line
        entry.setNotifiedAt(null);
        waitlistRepository.save(entry);
        return EventResponse.of(event, stats, now, true);
    }

    @Transactional
    public EventResponse leaveWaitlist(Long eventId, Long userId) {
        waitlistRepository.deleteForUser(eventId, userId);
        return get(eventId, userId);
    }

    // ------------------------------------------------------------------ helpers

    private Event find(Long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
    }

    private Map<Long, EventStats> stats(LocalDateTime now) {
        return bookingRepository.eventStats(now).stream()
                .collect(Collectors.toMap(EventStats::eventId, Function.identity()));
    }
}
