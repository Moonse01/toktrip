package com.triplan.triplan.dto;

public record PlaceVoteStatResponse(
        Long placeId,
        String placeName,
        String candidateLabel,
        Integer dayNumber,
        Integer orderIndex,
        Long dislikeCount,
        Long likeCount,
        Long superLikeCount,
        Long pinCount,
        Long totalVotes
) {}
