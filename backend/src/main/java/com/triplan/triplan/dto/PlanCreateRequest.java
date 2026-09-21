package com.triplan.triplan.dto;

public record PlanCreateRequest(
        String chatLog,
        String mission
) {}