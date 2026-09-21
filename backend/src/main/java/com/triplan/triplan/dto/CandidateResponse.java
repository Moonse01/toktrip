package com.triplan.triplan.dto;

import com.triplan.triplan.entity.PlanCandidate;

import java.math.BigDecimal;
import java.util.List;

public record CandidateResponse(
        Long id,
        String label,
        String name,
        String concept,
        String splitReason,
        Integer estimatedCostPerPerson,
        BigDecimal totalDistanceKm,
        Integer version,
        Boolean isFinal,
        List<PlaceResponse> places,
        List<VoteHighlightResponse> highlights
) {
    public static CandidateResponse of(PlanCandidate candidate, List<PlaceResponse> places) {
        return of(candidate, places, null);
    }

    public static CandidateResponse of(
            PlanCandidate candidate,
            List<PlaceResponse> places,
            List<VoteHighlightResponse> highlights
    ) {
        return new CandidateResponse(
                candidate.getId(),
                candidate.getLabel(),
                candidate.getName(),
                candidate.getConcept(),
                candidate.getSplitReason(),
                candidate.getEstimatedCostPerPerson(),
                candidate.getTotalDistanceKm(),
                candidate.getVersion(),
                candidate.getIsFinal(),
                places,
                highlights
        );
    }
}
