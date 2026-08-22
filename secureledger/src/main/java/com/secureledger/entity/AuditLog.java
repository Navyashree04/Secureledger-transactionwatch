package com.secureledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only ledger of every balance-affecting action.
 * No service, repository, or controller in this codebase issues an
 * UPDATE or DELETE against this table — enforced by convention here,
 * and should additionally be enforced with a DB-level REVOKE UPDATE,
 * DELETE ON audit_log in a real deployment (see DESIGN.md).
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, name = "entity_type", length = 32)
    private String entityType;

    @Column(nullable = false, name = "entity_id")
    private Long entityId;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    // Stored as JSON text. In a real Postgres deployment this column should
    // be JSONB (see DESIGN.md); left as @Lob text here so the H2 test
    // profile and Postgres profile both work without a dialect-specific type.
    @Lob
    @Column(name = "before_state")
    private String beforeState;

    @Lob
    @Column(name = "after_state")
    private String afterState;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
