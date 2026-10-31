package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.model.Event;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.repository.EventRepository;
import com.nikhil.ticketbooking.repository.SeatRepository;
import org.springframework.web.bind.annotation.*;
import com.nikhil.ticketbooking.dto.CreateEventRequest;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    public EventController(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    @GetMapping
    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    @PostMapping
    public Event createEvent(@RequestBody CreateEventRequest request) {
        Event event = new Event();
        event.setName(request.getName());
        event.setVenue(request.getVenue());
        event.setEventDate(request.getEventDate());
        event.setCreatedAt(LocalDateTime.now());
        Event savedEvent = eventRepository.save(event);

        // Auto-generate seats from the row/column layout the organizer specified
        for (int row = 0; row < request.getRows(); row++) {
            char rowLetter = (char) ('A' + row);
            for (int col = 1; col <= request.getSeatsPerRow(); col++) {
                Seat seat = new Seat();
                seat.setEvent(savedEvent);
                seat.setSeatLabel(rowLetter + String.valueOf(col));
                seatRepository.save(seat);
            }
        }

        return savedEvent;
    }
}