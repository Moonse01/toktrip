package com.triplan.triplan.dto;

import com.triplan.triplan.entity.Place;

import java.math.BigDecimal;

public record PlaceResponse(
        Long id,
        Integer dayNumber,
        Integer orderIndex,
        String name,
        String category,
        String description,
        Integer estimatedCost,
        Integer durationMinutes,
        String visitTime,
        BigDecimal lat,
        BigDecimal lng,
        String kakaoPlaceId
) {
    public static PlaceResponse from(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getDayNumber(),
                place.getOrderIndex(),
                place.getName(),
                place.getCategory(),
                place.getDescription(),
                place.getEstimatedCost(),
                place.getDurationMinutes(),
                place.getVisitTime() != null ? place.getVisitTime().toString() : null,
                place.getLat(),
                place.getLng(),
                place.getKakaoPlaceId()
        );
    }
}