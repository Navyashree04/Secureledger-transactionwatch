package com.secureledger.service;

import com.secureledger.dto.TransactionResponse;
import com.secureledger.dto.TransferRequest;
import com.secureledger.dto.TransferResult;
import com.secureledger.entity.*;
import com.secureledger.event.TransactionEventPublisher;
import com.secureledger.exception.AccountNotFoundException;
import com.secureledger.exception.DuplicateIdempotencyKeyException;
import com.secureledger.exception.InsufficientFundsException;
import com.secureledger.repository.AccountRepository;
import com.secureledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Owns the one operation in this system where correctness under concurrency
 * actually matters: moving money between two accounts atomically.
 *
 * Two safety mechanisms, both explained in DESIGN.md:
 *  1. Pessimistic row locks (SELECT ... FOR UPDATE), acquired in a fixed
 *     order (lower account id first) so two transfers that touch the same
 *     pair of accounts can never deadlock each other.
 *  2. A DB-level unique constraint on transactions.idempotency_key, so a
 *     retried POST /transfers with the same key cannot double-move money
 *     even under a race — not just an in-memory / pre-check guard.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final AuditService auditService;
    private final TransactionEventPublisher eventPublisher;

    public TransferService(AccountRepository accountRepository,
                            TransactionRepository transactionRepository,
                            AccountService accountService,
                            AuditService auditService,
                            TransactionEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TransferResult transfer(TransferRequest request, String idempotencyKey) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }

        String debitKey = idempotencyKey == null ? null : idempotencyKey + ":OUT";
        String creditKey = idempotencyKey == null ? null : idempotencyKey + ":IN";

        if (idempotencyKey != null) {
            Optional<Transaction> existingDebit = firstMatching(debitKey);
            if (existingDebit.isPresent()) {
                Transaction debit = existingDebit.get();
                if (!debit.getAccountId().equals(request.fromAccountId())
                        || debit.getAmount().compareTo(request.amount()) != 0) {
                    throw new DuplicateIdempotencyKeyException(
                            "Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
                }
                Transaction credit = firstMatching(creditKey).orElseThrow(() ->
                        new IllegalStateException("Debit leg exists without matching credit leg for key " + idempotencyKey));
                return new TransferResult(TransactionResponse.from(debit), TransactionResponse.from(credit), true);
            }
        }

        // Lock accounts in a fixed order (ascending id) regardless of which
        // side is "from" vs "to" — this is what prevents deadlocks when two
        // concurrent transfers touch the same pair of accounts in opposite directions.
        Long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        Long secondId = Math.max(request.fromAccountId(), request.toAccountId());

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException("No account with id " + firstId));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException("No account with id " + secondId));

        Account from = request.fromAccountId().equals(firstId) ? first : second;
        Account to = request.fromAccountId().equals(firstId) ? second : first;

        accountService.assertActive(from);
        accountService.assertActive(to);

        if (from.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    "Account " + from.getAccountNumber() + " has insufficient balance for this transfer");
        }

        BigDecimal fromBefore = from.getBalance();
        BigDecimal toBefore = to.getBalance();

        from.debit(request.amount());
        to.credit(request.amount());
        accountRepository.save(from);
        accountRepository.save(to);

        Transaction debitTx = transactionRepository.save(Transaction.builder()
                .accountId(from.getId())
                .relatedAccountId(to.getId())
                .type(TransactionType.TRANSFER_OUT)
                .amount(request.amount())
                .balanceAfter(from.getBalance())
                .idempotencyKey(debitKey)
                .status(TransactionStatus.COMPLETED)
                .build());

        Transaction creditTx = transactionRepository.save(Transaction.builder()
                .accountId(to.getId())
                .relatedAccountId(from.getId())
                .type(TransactionType.TRANSFER_IN)
                .amount(request.amount())
                .balanceAfter(to.getBalance())
                .idempotencyKey(creditKey)
                .status(TransactionStatus.COMPLETED)
                .build());

        Long actorId = accountService.currentUser().getId();
        auditService.logBalanceChange(from, actorId, "TRANSFER_OUT", fromBefore);
        auditService.logBalanceChange(to, actorId, "TRANSFER_IN", toBefore);

        // Both legs are published — TransactionWatch's velocity rule counts
        // per-account activity, and both accounts genuinely had a transaction happen.
        eventPublisher.publishAfterCommit(debitTx);
        eventPublisher.publishAfterCommit(creditTx);

        return new TransferResult(TransactionResponse.from(debitTx), TransactionResponse.from(creditTx), false);
    }

    private Optional<Transaction> firstMatching(String key) {
        List<Transaction> matches = transactionRepository.findByIdempotencyKey(key);
        return matches.stream().findFirst();
    }
}
