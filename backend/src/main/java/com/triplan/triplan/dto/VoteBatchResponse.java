package com.triplan.triplan.dto;

public record VoteBatchResponse(
        Integer savedVoteCount,
        Integer savedOrderPreferenceCount
) {}
