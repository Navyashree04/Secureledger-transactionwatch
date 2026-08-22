package com.secureledger.service;

import com.secureledger.dto.AccountResponse;
import com.secureledger.dto.CreateAccountRequest;
import com.secureledger.dto.TransactionResponse;
import com.secureledger.entity.*;
import com.secureledger.event.TransactionEventPublisher;
import com.secureledger.exception.AccountFrozenException;
import com.secureledger.exception.AccountNotFoundException;
import com.secureledger.exception.InsufficientFundsException;
import com.secureledger.exception.UnauthorizedAccessException;
import com.secureledger.repository.AccountRepository;
import com.secureledger.repository.TransactionRepository;
import com.secureledger.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TransactionEventPublisher eventPublisher;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AccountService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           TransactionEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        User owner = currentUser();
        Account account = Account.builder()
                .ownerUserId(owner.getId())
                .accountNumber(generateAccountNumber())
                .accountType(request.accountType())
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .build();
        return AccountResponse.from(accountRepository.save(account));
    }

    public AccountResponse getAccount(Long accountId) {
        Account account = findOrThrow(accountId);
        assertOwnerOrAdmin(account);
        return AccountResponse.from(account);
    }

    public List<AccountResponse> listAccounts() {
        User user = currentUser();
        List<Account> accounts = user.getRole() == Role.ADMIN
                ? accountRepository.findAll()
                : accountRepository.findByOwnerUserId(user.getId());
        return accounts.stream().map(AccountResponse::from).toList();
    }

    @Transactional
    public TransactionResponse deposit(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("No account with id " + accountId));
        assertOwnerOrAdmin(account);
        assertActive(account);

        BigDecimal before = account.getBalance();
        account.credit(amount);
        accountRepository.save(account);

        Transaction tx = transactionRepository.save(Transaction.builder()
                .accountId(account.getId())
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .balanceAfter(account.getBalance())
                .status(TransactionStatus.COMPLETED)
                .build());

        auditService.logBalanceChange(account, currentUser().getId(), "DEPOSIT", before);
        eventPublisher.publishAfterCommit(tx);
        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransactionResponse withdraw(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("No account with id " + accountId));
        assertOwnerOrAdmin(account);
        assertActive(account);

        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Account " + account.getAccountNumber() + " has insufficient balance for this withdrawal");
        }

        BigDecimal before = account.getBalance();
        account.debit(amount);
        accountRepository.save(account);

        Transaction tx = transactionRepository.save(Transaction.builder()
                .accountId(account.getId())
                .type(TransactionType.WITHDRAWAL)
                .amount(amount)
                .balanceAfter(account.getBalance())
                .status(TransactionStatus.COMPLETED)
                .build());

        auditService.logBalanceChange(account, currentUser().getId(), "WITHDRAWAL", before);
        eventPublisher.publishAfterCommit(tx);
        return TransactionResponse.from(tx);
    }

    public Page<TransactionResponse> listTransactions(Long accountId, Pageable pageable) {
        Account account = findOrThrow(accountId);
        assertOwnerOrAdmin(account);
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
                .map(TransactionResponse::from);
    }

    @Transactional
    public AccountResponse setStatus(Long accountId, AccountStatus newStatus) {
        User user = currentUser();
        if (user.getRole() != Role.ADMIN) {
            throw new UnauthorizedAccessException("Only an admin may change account status");
        }
        Account account = findOrThrow(accountId);
        String before = account.getStatus().name();
        account.setStatus(newStatus);
        accountRepository.save(account);
        auditService.logAccountStatusChange(account, user.getId(), before);
        return AccountResponse.from(account);
    }

    // ---- internal helpers ----

    Account findOrThrow(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("No account with id " + accountId));
    }

    void assertActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException(
                    "Account " + account.getAccountNumber() + " is " + account.getStatus() + " and cannot transact");
        }
    }

    void assertOwnerOrAdmin(Account account) {
        User user = currentUser();
        if (user.getRole() == Role.ADMIN) return;
        if (!account.getOwnerUserId().equals(user.getId())) {
            throw new UnauthorizedAccessException("You do not have access to account " + account.getId());
        }
    }

    User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AccountNotFoundException("Authenticated user not found: " + username));
    }

    private String generateAccountNumber() {
        // 12-digit numeric account number, collision-checked.
        String candidate;
        do {
            candidate = String.format("%012d", (long) (RANDOM.nextDouble() * 1_000_000_000_000L));
        } while (accountRepository.findByAccountNumber(candidate).isPresent());
        return candidate;
    }
}
