package com.triplan.triplan.dto;

public record ParticipantVoteItemResponse(
        Long placeId,
        String placeName,
        String candidateLabel,
        Integer dayNumber,
        Integer orderIndex,
        Byte voteScore,
        Integer preferredDayNumber,
        Integer preferredOrderIndex
) {}
