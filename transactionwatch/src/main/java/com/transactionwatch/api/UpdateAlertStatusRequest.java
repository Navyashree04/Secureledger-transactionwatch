package com.transactionwatch.api;

import com.transactionwatch.alert.AlertStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAlertStatusRequest(@NotNull AlertStatus status) {}
