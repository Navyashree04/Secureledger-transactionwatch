package com.secureledger.repository;

import com.secureledger.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Intentionally exposes only inherited save()/findX() methods from
 * JpaRepository — no delete or update method is ever added here.
 * The audit_log table is append-only by construction, not just convention.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
