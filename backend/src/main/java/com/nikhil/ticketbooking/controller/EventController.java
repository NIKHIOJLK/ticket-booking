package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.dto.CreateEventRequest;
import com.nikhil.ticketbooking.dto.EventResponse;
import com.nikhil.ticketbooking.dto.SeatResponse;
import com.nikhil.ticketbooking.security.CurrentUser;
import com.nikhil.ticketbooking.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public List<EventResponse> getAllEvents() {
        return eventService.list();
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable Long eventId, Authentication authentication) {
        return eventService.get(eventId, CurrentUser.idOrNull(authentication));
    }

    /** Public, but if you're logged in your own held/booked seats are marked. */
    @GetMapping("/{eventId}/seats")
    public List<SeatResponse> getSeatsForEvent(@PathVariable Long eventId, Authentication authentication) {
        return eventService.seats(eventId, CurrentUser.idOrNull(authentication));
    }

    /** Admins only (see SecurityConfig). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse createEvent(@Valid @RequestBody CreateEventRequest request) {
        return eventService.create(request);
    }

    @PostMapping("/{eventId}/waitlist")
    public EventResponse joinWaitlist(@PathVariable Long eventId, Authentication authentication) {
        return eventService.joinWaitlist(eventId, CurrentUser.id(authentication));
    }

    @DeleteMapping("/{eventId}/waitlist")
    public EventResponse leaveWaitlist(@PathVariable Long eventId, Authentication authentication) {
        return eventService.leaveWaitlist(eventId, CurrentUser.id(authentication));
    }
}
