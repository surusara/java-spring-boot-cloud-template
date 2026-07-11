package com.example.consumer.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unsent rows for this relay instance. {@code FOR UPDATE SKIP LOCKED} lets
     * multiple pods run the relay concurrently: each pod locks a disjoint set of rows (skipping any
     * already locked by a peer), so rows are never double-published by racing pods. The locks are
     * held until the surrounding transaction commits (after the rows are marked SENT).
     *
     * <p>PostgreSQL-specific (SKIP LOCKED). Ordered by id to preserve per-key publish order.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' "
            + "ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("limit") int limit);
}
