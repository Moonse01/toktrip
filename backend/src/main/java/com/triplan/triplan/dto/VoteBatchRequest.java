package com.triplan.triplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record VoteBatchRequest(
        @NotNull List<@Valid VoteRequest> votes,
        @NotNull List<@Valid OrderPreferenceRequest> orderPreferences
) {}
