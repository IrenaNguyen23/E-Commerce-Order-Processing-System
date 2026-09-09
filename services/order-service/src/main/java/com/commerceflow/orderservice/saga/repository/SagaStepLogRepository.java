package com.commerceflow.orderservice.saga.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.commerceflow.orderservice.saga.entity.SagaStepLog;

@Repository
public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, UUID> {

    /** The full history of one saga, oldest first. */
    List<SagaStepLog> findBySagaIdOrderByCreatedAtAsc(UUID sagaId);

    /** The open row for a command, so a reply can close the exact attempt it answers. */
    Optional<SagaStepLog> findByCommandId(UUID commandId);
}
