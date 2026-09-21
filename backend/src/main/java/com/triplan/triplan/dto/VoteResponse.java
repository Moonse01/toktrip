package com.triplan.triplan.dto;

public record VoteResponse(
        Long id,
        Long placeId,
        Byte voteScore,
        String comment,
        String message
) {}