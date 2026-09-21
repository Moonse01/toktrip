package com.triplan.triplan.dto;

import com.triplan.triplan.entity.Plan;

public record PlanResponse(
        Long id,
        String uuid,
        Long ownerId,
        String title,
        String summary,
        String destination,
        String mission,
        String status,
        String startDate,
        String endDate,
        Integer participantCount
) {
    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.getId(),
                plan.getUuid(),
                plan.getOwner() != null ? plan.getOwner().getId() : null,
                plan.getTitle(),
                plan.getSummary(),
                plan.getDestination(),
                plan.getMission(),
                plan.getStatus().name(),
                plan.getStartDate() != null ? plan.getStartDate().toString() : null,
                plan.getEndDate() != null ? plan.getEndDate().toString() : null,
                plan.getParticipantCount()
        );
    }
}
