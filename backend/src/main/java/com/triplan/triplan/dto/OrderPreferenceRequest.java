package com.triplan.triplan.dto;

import jakarta.validation.constraints.NotNull;

public record OrderPreferenceRequest(
        @NotNull Long candidateId,
        @NotNull Long placeId,
        @NotNull Integer preferredDayNumber,
        @NotNull Integer preferredOrderIndex
) {}