package com.triplan.triplan.dto;

import java.util.List;

public record DashboardResponse(
        Long planId,
        String planUuid,
        String status,
        Long totalParticipants,
        Long votedParticipants,
        Boolean participantCountKnown,
        List<PlaceVoteStatResponse> placeStats,
        List<ParticipantVoteResponse> participants,
        List<VoteCommentResponse> comments,
        List<OrderChangeResponse> orderChanges,
        String myRole
) {}
