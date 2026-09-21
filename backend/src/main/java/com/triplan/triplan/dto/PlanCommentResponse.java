package com.triplan.triplan.dto;

public record PlanCommentResponse(
        Long commentId,
        String content,
        String voterName,
        String updatedAt
) {}
