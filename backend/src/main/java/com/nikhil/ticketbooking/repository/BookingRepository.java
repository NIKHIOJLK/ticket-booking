package com.nikhil.ticketbooking.repository;

import com.nikhil.ticketbooking.dto.EventStats;
import com.nikhil.ticketbooking.dto.SeatRow;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * State changes are written as conditional UPDATEs ("change it only if it is still in
 * the state I expect"). The database applies each one atomically, so two requests
 * racing each other (double-click, two tabs, the expiry task firing at the same moment
 * as a payment) can never both win. The return value says whether this one did.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    // A booking "occupies" its seat while it is CONFIRMED, or HELD and not yet expired.
    String OCCUPYING = "(b.status = com.nikhil.ticketbooking.model.BookingStatus.CONFIRMED"
            + " or (b.status = com.nikhil.ticketbooking.model.BookingStatus.HELD and b.holdExpiresAt > :now))";

    Optional<Booking> findByReference(String reference);

    @Query("""
            select b from Booking b join fetch b.seat s join fetch s.event join fetch b.user
            where b.user.id = :userId order by s.event.eventDate desc, b.bookedAt desc""")
    List<Booking> findAllForUser(@Param("userId") Long userId);

    @Query("select count(b) from Booking b where b.user.id = :userId and b.seat.event.id = :eventId and " + OCCUPYING)
    long countOccupyingForUserAndEvent(@Param("userId") Long userId, @Param("eventId") Long eventId,
                                       @Param("now") LocalDateTime now);

    /** One row per seat of the event, with the booking currently occupying it (if any). */
    @Query("select new com.nikhil.ticketbooking.dto.SeatRow(s.id, s.seatLabel, b.id, b.status, b.user.id, b.holdExpiresAt)"
            + " from Seat s left join Booking b on b.seat = s and " + OCCUPYING
            + " where s.event.id = :eventId order by s.id")
    List<SeatRow> seatMap(@Param("eventId") Long eventId, @Param("now") LocalDateTime now);

    /** Total and taken seats for every event, in one query. */
    @Query("select new com.nikhil.ticketbooking.dto.EventStats(e.id, count(s.id), count(b.id))"
            + " from Event e left join Seat s on s.event = e left join Booking b on b.seat = s and " + OCCUPYING
            + " group by e.id")
    List<EventStats> eventStats(@Param("now") LocalDateTime now);

    // ------------------------------------------------------------ state transitions

    /** A hold that ran out but wasn't cleaned up yet must not block the seat. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.status = com.nikhil.ticketbooking.model.BookingStatus.EXPIRED
            where b.seat.id = :seatId and b.status = com.nikhil.ticketbooking.model.BookingStatus.HELD
              and b.holdExpiresAt <= :now""")
    int expireStaleHoldOnSeat(@Param("seatId") Long seatId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.status = com.nikhil.ticketbooking.model.BookingStatus.EXPIRED
            where b.id = :id and b.status = com.nikhil.ticketbooking.model.BookingStatus.HELD
              and b.holdExpiresAt <= :now""")
    int expireHold(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.status = com.nikhil.ticketbooking.model.BookingStatus.CONFIRMED, b.confirmedAt = :now
            where b.id = :id and b.user.id = :userId and b.status = com.nikhil.ticketbooking.model.BookingStatus.HELD
              and b.holdExpiresAt > :now""")
    int confirmHold(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** Cancels a HELD (releasing it) or CONFIRMED booking that hasn't been used at the door. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.status = com.nikhil.ticketbooking.model.BookingStatus.CANCELLED, b.cancelledAt = :now
            where b.id = :id and b.user.id = :userId and b.status = :from and b.checkedInAt is null""")
    int cancel(@Param("id") Long id, @Param("userId") Long userId, @Param("from") BookingStatus from,
               @Param("now") LocalDateTime now);

    /** Single-use tickets: succeeds once, for a confirmed booking only. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b set b.checkedInAt = :now
            where b.reference = :reference and b.status = com.nikhil.ticketbooking.model.BookingStatus.CONFIRMED
              and b.checkedInAt is null""")
    int checkIn(@Param("reference") String reference, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Booking b set b.ticketEmailedAt = :now where b.id = :id and b.ticketEmailedAt is null")
    int markTicketEmailed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
