package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.dto.BookingRequest;
import com.nikhil.ticketbooking.dto.SeatResponse;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.repository.BookingRepository;
import com.nikhil.ticketbooking.repository.SeatRepository;
import com.nikhil.ticketbooking.service.BookingService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    public BookingController(BookingService bookingService, SeatRepository seatRepository,
                              BookingRepository bookingRepository) {
        this.bookingService = bookingService;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    @GetMapping("/events/{eventId}/seats")
    public List<SeatResponse> getSeatsForEvent(@PathVariable Long eventId) {
        List<Seat> seats = seatRepository.findByEventId(eventId);
        return seats.stream()
                .map(seat -> new SeatResponse(
                        seat.getId(),
                        seat.getSeatLabel(),
                        bookingRepository.existsBySeatId(seat.getId())))
                .collect(Collectors.toList());
    }

    @PostMapping("/bookings")
    public Booking bookSeat(@RequestBody BookingRequest request, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return bookingService.bookSeat(request.getSeatId(), userId);
    }
}