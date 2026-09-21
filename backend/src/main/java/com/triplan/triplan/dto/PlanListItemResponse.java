package com.triplan.triplan.dto;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;

import java.util.List;

/**
 * 내 플랜 목록용 DTO — Plan 기본 정보 + A/B 후보안 요약.
 */
public record PlanListItemResponse(
        Long id,
        String uuid,
        String title,
        String summary,
        String destination,
        String status,
        String startDate,
        String endDate,
        Integer participantCount,
        String myRole,
        List<CandidateSummary> candidates
) {
    public record CandidateSummary(
            String label,
            String name,
            String concept,
            Integer estimatedCostPerPerson
    ) {
        public static CandidateSummary from(PlanCandidate c) {
            return new CandidateSummary(
                    c.getLabel(),
                    c.getName(),
                    c.getConcept(),
                    c.getEstimatedCostPerPerson()
            );
        }
    }

    public static PlanListItemResponse from(Plan plan, List<PlanCandidate> candidates, String myRole) {
        List<CandidateSummary> candidateSummaries = candidates == null
                ? List.of()
                : candidates.stream()
                    .filter(c -> !Boolean.TRUE.equals(c.getIsFinal()))
                    .map(CandidateSummary::from)
                    .toList();

        return new PlanListItemResponse(
                plan.getId(),
                plan.getUuid(),
                plan.getTitle(),
                plan.getSummary(),
                plan.getDestination(),
                plan.getStatus().name(),
                plan.getStartDate() != null ? plan.getStartDate().toString() : null,
                plan.getEndDate() != null ? plan.getEndDate().toString() : null,
                plan.getParticipantCount(),
                myRole,
                candidateSummaries
        );
    }
}
