package com.triplan.triplan.dto;

import java.util.List;

public record ParticipantVoteResponse(
        Long userId,
        String displayName,
        Long voteCount,
        List<ParticipantVoteItemResponse> votes
) {}
