package com.nikhil.ticketbooking.repository;

import com.nikhil.ticketbooking.model.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    boolean existsByEventIdAndUserIdAndNotifiedAtIsNull(Long eventId, Long userId);

    Optional<WaitlistEntry> findByEventIdAndUserId(Long eventId, Long userId);

    @Transactional
    @Modifying
    @Query("delete from WaitlistEntry w where w.event.id = :eventId and w.user.id = :userId")
    int deleteForUser(@Param("eventId") Long eventId, @Param("userId") Long userId);

    @Query("select count(w) from WaitlistEntry w where w.event.id = :eventId and w.notifiedAt is null")
    long countWaiting(@Param("eventId") Long eventId);

    /**
     * The person who has been waiting longest. SKIP LOCKED: if two seats free up at the
     * same moment, the two notification tasks pick two different people.
     */
    @Query(value = """
            select * from waitlist_entries
            where event_id = :eventId and notified_at is null
            order by created_at, id
            limit 1
            for update skip locked""", nativeQuery = true)
    Optional<WaitlistEntry> lockNextWaiting(@Param("eventId") Long eventId);
}
