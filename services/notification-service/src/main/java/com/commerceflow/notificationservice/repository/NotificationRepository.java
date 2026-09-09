package com.commerceflow.notificationservice.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationStatus;

/** Persistence port for notifications. */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
             WHERE (:userId IS NULL OR n.userId = :userId)
               AND (:status IS NULL OR n.status = :status)
            """)
    Page<Notification> search(@Param("userId") UUID userId,
                              @Param("status") NotificationStatus status,
                              Pageable pageable);

    List<Notification> findByReferenceIdOrderByCreatedAtAsc(UUID referenceId);

    long countByStatus(NotificationStatus status);

    /**
     * Removes notifications in the given states that are older than a cut-off.
     *
     * <p>States are a parameter rather than hard-coded, so the caller has to say which ones it
     * considers disposable. The set that must never be swept — failed and pending — is then a
     * visible decision at the call site rather than something buried in a query.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.status IN :states AND n.createdAt < :before")
    int deleteByStatusInAndCreatedAtBefore(
            @Param("states") java.util.List<com.commerceflow.notificationservice.entity.NotificationStatus> states,
            @Param("before") java.time.Instant before);
}
