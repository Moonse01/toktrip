package com.triplan.triplan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 2차 플래너봇 AI 응답 JSON을 매핑하는 DTO.
 *
 * <p>AI 출력 예시:
 * <pre>
 * {
 *   "split_axis": "1순위",
 *   "split_reason": "한라산 vs 오름 충돌 기반 분리",
 *   "plan_a": { "label": "A", "name": "...", ... },
 *   "plan_b": { "label": "B", "name": "...", ... }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlannerResponse(
        @JsonProperty("split_axis") String splitAxis,
        @JsonProperty("split_reason") String splitReason,
        @JsonProperty("plan_a") CandidatePlan planA,
        @JsonProperty("plan_b") CandidatePlan planB
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CandidatePlan(
            String label,
            String name,
            String concept,
            @JsonProperty("estimated_cost_per_person")
            Integer estimatedCostPerPerson,
            @JsonProperty("total_distance_km") BigDecimal totalDistanceKm,
            List<PlaceItem> places
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaceItem(
            @JsonProperty("day_number") Integer dayNumber,
            @JsonProperty("order_index") Integer orderIndex,
            String name,
            String category,
            String description,
            @JsonProperty("estimated_cost") Integer estimatedCost,
            @JsonProperty("duration_minutes") Integer durationMinutes,
            @JsonProperty("visit_time") String visitTime,
            @JsonProperty("area_hint") String areaHint,
            @JsonProperty("search_keyword") String searchKeyword,
            @JsonProperty("kakao_place_id") String kakaoPlaceId,
            BigDecimal lat,
            BigDecimal lng
    ) {}
}
