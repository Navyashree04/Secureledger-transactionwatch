package com.transactionwatch.api;

import com.transactionwatch.alert.Alert;
import com.transactionwatch.alert.AlertService;
import com.transactionwatch.alert.AlertStatus;
import com.transactionwatch.rules.Severity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Alerts", description = "Query and triage fraud alerts raised by the rule engine")
public class AdminAlertController {

    private final AlertService alertService;

    public AdminAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "List alerts, filterable by accountId, status, severity, and created-date range")
    @GetMapping("/alerts")
    public Page<AlertResponse> list(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return alertService.search(accountId, status, severity, from, to, pageable).map(AlertResponse::from);
    }

    @Operation(summary = "Get a single alert by id")
    @GetMapping("/alerts/{id}")
    public AlertResponse get(@PathVariable UUID id) {
        return AlertResponse.from(alertService.getById(id));
    }

    @Operation(summary = "All alerts raised for one account")
    @GetMapping("/accounts/{accountId}/alerts")
    public List<AlertResponse> byAccount(@PathVariable String accountId) {
        return alertService.getByAccountId(accountId).stream().map(AlertResponse::from).toList();
    }

    @Operation(summary = "Update an alert's triage status (OPEN/REVIEWED/DISMISSED)")
    @PatchMapping("/alerts/{id}/status")
    public AlertResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateAlertStatusRequest request) {
        Alert alert = alertService.updateStatus(id, request.status());
        return AlertResponse.from(alert);
    }
}
