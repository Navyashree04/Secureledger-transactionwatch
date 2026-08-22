package com.secureledger.service;

import com.secureledger.entity.Account;
import com.secureledger.entity.AuditLog;
import com.secureledger.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Every balance-changing operation records an audit_log row. Callers invoke
 * this from WITHIN the same @Transactional method that changes the balance
 * (see AccountService / TransferService) so the two either both commit or
 * both roll back — never one without the other.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void logBalanceChange(Account account, Long actorUserId, String action, BigDecimal beforeBalance) {
        AuditLog log = AuditLog.builder()
                .entityType("ACCOUNT")
                .entityId(account.getId())
                .action(action)
                .actorUserId(actorUserId)
                .beforeState("{\"balance\":\"" + beforeBalance + "\"}")
                .afterState("{\"balance\":\"" + account.getBalance() + "\"}")
                .build();
        auditLogRepository.save(log);
    }

    public void logAccountStatusChange(Account account, Long actorUserId, String beforeStatus) {
        AuditLog log = AuditLog.builder()
                .entityType("ACCOUNT")
                .entityId(account.getId())
                .action("STATUS_CHANGE")
                .actorUserId(actorUserId)
                .beforeState("{\"status\":\"" + beforeStatus + "\"}")
                .afterState("{\"status\":\"" + account.getStatus() + "\"}")
                .build();
        auditLogRepository.save(log);
    }
}
