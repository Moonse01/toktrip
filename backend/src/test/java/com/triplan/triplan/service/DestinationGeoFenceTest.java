package com.triplan.triplan.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationGeoFenceTest {

    @Test
    void allowsCoordinateInsideKnownDestination() {
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("35.817462"),
                new BigDecimal("127.148039"),
                "전주"
        )).isTrue();
    }

    @Test
    void rejectsCoordinateOutsideKnownDestination() {
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("35.228110"),
                new BigDecimal("128.677102"),
                "전주"
        )).isFalse();
    }

    @Test
    void allowsUnknownDestinationAfterBasicCoordinateValidation() {
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("36.500000"),
                new BigDecimal("127.500000"),
                "처음보는지역"
        )).isTrue();
    }

    @Test
    void allowsChuncheonFoodStopInsideGapyeongTripWhenAreaHintIsSpecific() {
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("37.880300"),
                new BigDecimal("127.727800"),
                "가평",
                "춘천 닭갈비"
        )).isTrue();
    }

    @Test
    void doesNotAllowUnrelatedAreaHintOutsideDestination() {
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("35.179600"),
                new BigDecimal("129.075600"),
                "전주",
                "부산"
        )).isFalse();
    }

    @Test
    void rejectsYangyangSurfBeachInsideGangneungTrip() {
        // 죽도해변(양양)은 강릉 여행에 섞이면 안 된다 — 강릉 fence 밖이므로 거부되어야 한다.
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("38.013500"),
                new BigDecimal("128.660300"),
                "강릉",
                "양양 죽도해변"
        )).isFalse();
    }

    @Test
    void allowsJumunjinSeafoodInsideGangneungTrip() {
        // 주문진항은 강릉시라 강릉 fence 안에 직접 포함되어야 한다(인접 허용 제거의 부작용 없음 확인).
        assertThat(DestinationGeoFence.allows(
                new BigDecimal("37.892000"),
                new BigDecimal("128.831000"),
                "강릉",
                "주문진항 회"
        )).isTrue();
    }
}
