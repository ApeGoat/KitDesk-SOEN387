package com.kitdesk.repository;

import com.kitdesk.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    Optional<IdempotencyRecord> findByUserIdAndKey(Long userId, String key);

    /** Forget keys older than the retention window (the contract promises at least 24 hours). */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
