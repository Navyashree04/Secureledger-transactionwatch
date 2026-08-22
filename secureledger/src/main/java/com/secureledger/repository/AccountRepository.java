package com.secureledger.repository;

import com.secureledger.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByOwnerUserId(Long ownerUserId);

    /**
     * Row-locking read for the money-movement path. SELECT ... FOR UPDATE
     * via PESSIMISTIC_WRITE — see DESIGN.md for why pessimistic locking was
     * chosen over optimistic locking specifically for balance transfers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
