package com.nikhil.ticketbooking.model;

/**
 * HELD ──confirm (pay)──> CONFIRMED ──cancel──> CANCELLED
 *   │
 *   ├──release────────────────────────────────> CANCELLED
 *   └──hold time runs out─────────────────────> EXPIRED
 *
 * Only HELD and CONFIRMED bookings occupy a seat (enforced by a partial unique index).
 */
public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
