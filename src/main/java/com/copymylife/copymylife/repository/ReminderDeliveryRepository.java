package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.ReminderDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ReminderDeliveryRepository extends JpaRepository<ReminderDelivery, Long> {

    // 1 when this call delivered the reminder, 0 when it was already delivered; two requests at
    // once never both get 1.
    @Modifying
    @Query(value = """
            INSERT INTO reminder_deliveries (user_id, reminder_key, occurrence_date, delivered_at)
            VALUES (:userId, :reminderKey, :occurrenceDate, :deliveredAt)
            ON CONFLICT (user_id, reminder_key) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("userId") Long userId,
            @Param("reminderKey") String reminderKey,
            @Param("occurrenceDate") LocalDate occurrenceDate,
            @Param("deliveredAt") Instant deliveredAt
    );

    @Query("""
            SELECT d.reminderKey FROM ReminderDelivery d
            WHERE d.user.id = :userId
              AND d.reminderKey IN :keys
            """)
    List<String> findDelivered(@Param("userId") Long userId, @Param("keys") Collection<String> keys);

    // Old days can never be due again.
    @Modifying
    @Query("DELETE FROM ReminderDelivery d WHERE d.user.id = :userId AND d.occurrenceDate < :before")
    int deleteOlder(@Param("userId") Long userId, @Param("before") LocalDate before);
}
