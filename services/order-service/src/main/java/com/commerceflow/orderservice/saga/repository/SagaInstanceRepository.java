package com.commerceflow.orderservice.saga.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    /**
     * Sagas whose current step is overdue.
     *
     * <p>Ordered oldest first and paged, so one slow scan cannot pull an unbounded result set
     * into memory during an outage — exactly when the overdue count is largest.
     */
    @Query("""
            SELECT s FROM SagaInstance s
            WHERE s.state IN :states
              AND s.stepDeadline IS NOT NULL
              AND s.stepDeadline < :now
            ORDER BY s.stepDeadline ASC
            """)
    List<SagaInstance> findOverdue(@Param("states") List<SagaState> states,
                                   @Param("now") Instant now,
                                   Pageable pageable);

    /** How many sagas are parked for an operator. Exposed as a gauge. */
    long countByState(SagaState state);

    /**
     * Removes finished sagas past their retention.
     *
     * <p>The step log follows by the cascade on the foreign key, so this is one statement rather
     * than a read, a loop and two deletes.
     *
     * <p>{@code states} is a parameter rather than hard-coded so the caller has to name which
     * states it considers finished — the one thing that must never be swept is a parked saga, and
     * a query that decided that for itself would be one edit away from doing so.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SagaInstance s WHERE s.state IN :states AND s.updatedAt < :before")
    int deleteFinishedBefore(@Param("states") List<SagaState> states,
                             @Param("before") java.time.Instant before);
}
