package com.triplan.triplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlanCommentRequest(
        @NotBlank
        @Size(max = 1000)
        String content
) {}
