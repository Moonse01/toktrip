package com.triplan.triplan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record VoteRequest(
        @NotNull Long candidateId,
        @NotNull Long placeId,
        @NotNull @Min(1) @Max(4) Byte voteScore,
        Integer preferredDayNumber,
        Integer preferredOrderIndex
) {}
