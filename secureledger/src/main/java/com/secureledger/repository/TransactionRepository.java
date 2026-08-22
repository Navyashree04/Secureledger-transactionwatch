package com.secureledger.repository;

import com.secureledger.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    List<Transaction> findByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findFirstByIdempotencyKeyAndAccountId(String idempotencyKey, Long accountId);
}
