package com.aidecision.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record IngestRequest(
        String text,
        String metadata,
        @NotNull @Pattern(regexp = "passed|rejected|frozen") String reviewOutcome
) {}
