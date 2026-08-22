package com.secureledger.controller;

import com.secureledger.dto.*;
import com.secureledger.entity.AccountStatus;
import com.secureledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Request/response mapping and validation only — all business rules
 * (ownership checks, frozen-account checks, balance checks) live in
 * AccountService / TransferService.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return accountService.getAccount(id);
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.listAccounts();
    }

    @PostMapping("/{id}/deposit")
    public TransactionResponse deposit(@PathVariable Long id, @Valid @RequestBody AmountRequest request) {
        return accountService.deposit(id, request.amount());
    }

    @PostMapping("/{id}/withdraw")
    public TransactionResponse withdraw(@PathVariable Long id, @Valid @RequestBody AmountRequest request) {
        return accountService.withdraw(id, request.amount());
    }

    @GetMapping("/{id}/transactions")
    public Page<TransactionResponse> transactions(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return accountService.listTransactions(id, pageable);
    }

    // Admin-only — enforced declaratively, not with scattered manual if-checks.
    @PatchMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse freeze(@PathVariable Long id) {
        return accountService.setStatus(id, AccountStatus.FROZEN);
    }

    @PatchMapping("/{id}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse unfreeze(@PathVariable Long id) {
        return accountService.setStatus(id, AccountStatus.ACTIVE);
    }
}
