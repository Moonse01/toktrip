package com.triplan.triplan.dto;

public record VoteCommentResponse(
        Long commentId,
        String comment,
        String voterName,
        String updatedAt
) {}
