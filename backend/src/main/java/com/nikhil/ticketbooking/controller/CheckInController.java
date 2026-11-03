package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.service.CheckInService;
import com.nikhil.ticketbooking.service.CheckInService.TicketInfo;
import org.springframework.web.bind.annotation.*;

/** Door staff (admins) look up and check in tickets. */
@RestController
@RequestMapping("/api/checkin")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @GetMapping("/{reference}")
    public TicketInfo lookUp(@PathVariable String reference) {
        return checkInService.lookUp(reference);
    }

    @PostMapping("/{reference}")
    public TicketInfo checkIn(@PathVariable String reference) {
        return checkInService.checkIn(reference);
    }
}
