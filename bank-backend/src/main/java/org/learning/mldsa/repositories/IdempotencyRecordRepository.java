package org.learning.mldsa.repositories;

import org.learning.mldsa.models.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByCallerIdAndIdempotencyKey(Long callerId, String idempotencyKey);
}
