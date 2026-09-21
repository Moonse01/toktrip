package com.triplan.triplan.dto;

public record PlanCreateResponse(
        Long id,
        String uuid,
        String status
) {}