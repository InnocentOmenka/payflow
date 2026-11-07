package com.innocodes.payflow.processor.repository;

import com.innocodes.payflow.processor.entity.OutboxEvent;
import com.innocodes.payflow.processor.enums.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(
            OutboxEventStatus status
    );
}