package com.triplan.triplan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiFinalPlanResponse(
        String name,
        String concept,
        @JsonProperty("split_reason")
        String splitReason,
        List<PlaceSelection> places
) {
    public record PlaceSelection(
            @JsonProperty("source_place_id")
            Long sourcePlaceId,
            @JsonProperty("day_number")
            Integer dayNumber,
            @JsonProperty("order_index")
            Integer orderIndex,
            @JsonProperty("visit_time")
            String visitTime,
            @JsonProperty("duration_minutes")
            Integer durationMinutes
    ) {}
}
