package com.aidecision.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ActivityLogRequest(

        @NotBlank
        String userId,

        @NotBlank
        String transactionId,

        @NotBlank
        @Pattern(regexp = "pass|reject|freeze", message = "must be pass, reject, or freeze")
        String bizAction,

        @NotBlank
        @Pattern(regexp = "add|delete|restore", message = "must be add, delete, or restore")
        String recordAction
) {}
