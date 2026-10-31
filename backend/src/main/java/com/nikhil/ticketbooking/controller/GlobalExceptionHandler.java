package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.service.SeatAlreadyBookedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatAlreadyBookedException.class)
    public ResponseEntity<Map<String, String>> handleSeatTaken(SeatAlreadyBookedException e) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT) // 409 — the standard "someone else already did this" status
            .body(Map.of("error", e.getMessage()));
    }
}