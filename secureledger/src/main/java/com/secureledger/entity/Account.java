package com.secureledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false, unique = true, name = "account_number", length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "account_type", length = 16)
    private AccountType accountType;

    // Money is NEVER float/double — see DESIGN.md. NUMERIC(19,4) avoids
    // binary floating-point rounding error on currency arithmetic.
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    // Optimistic-locking column. Not used on the money-movement path itself
    // (that uses pessimistic row locks — see TransferService / DESIGN.md),
    // but guards status-only writes like freeze/unfreeze against lost updates.
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = AccountStatus.ACTIVE;
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
    }

    /** Guarded debit — the only path by which balance may decrease. */
    public void debit(BigDecimal amount) {
        BigDecimal result = this.balance.subtract(amount);
        if (result.signum() < 0) {
            throw new IllegalStateException("Debit would drive balance negative for account " + accountNumber);
        }
        this.balance = result;
    }

    /** Guarded credit — the only path by which balance may increase. */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
