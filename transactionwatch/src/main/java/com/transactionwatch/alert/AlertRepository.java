package com.transactionwatch.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    List<Alert> findByAccountIdOrderByCreatedAtDesc(String accountId);

    Page<Alert> findByStatus(AlertStatus status, Pageable pageable);

    Page<Alert> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);

    @Query("""
        select a from Alert a
        where (:accountId is null or a.accountId = :accountId)
          and (:status is null or a.status = :status)
          and (:severity is null or a.severity = :severity)
          and (:from is null or a.createdAt >= :from)
          and (:to is null or a.createdAt <= :to)
        """)
    Page<Alert> search(@Param("accountId") String accountId,
                        @Param("status") AlertStatus status,
                        @Param("severity") com.transactionwatch.rules.Severity severity,
                        @Param("from") Instant from,
                        @Param("to") Instant to,
                        Pageable pageable);
}
