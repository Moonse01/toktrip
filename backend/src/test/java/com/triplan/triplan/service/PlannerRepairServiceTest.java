package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerRepairServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlannerRepairService repairService = new PlannerRepairService(objectMapper);
    private final PlannerValidationService validationService = new PlannerValidationService(objectMapper);

    private static final String BUSAN_1N2D_CONDITIONS = """
            {
              "destination": "부산",
              "dates": { "duration_nights": 1 },
              "transport": "대중교통",
              "budget": { "per_person_limit": 150000 },
              "constraints": ["KTX랑 숙소비는 따로 계산"],
              "host_requests": ["KTX랑 숙소비는 따로 계산한다고 보면 돼"],
              "must_include": [],
              "must_exclude": [],
              "conflicts": []
            }
            """;

    private static final String BUSAN_SPICY_EXCLUDED_CONDITIONS = """
            {
              "destination": "부산",
              "dates": { "duration_nights": 1 },
              "transport": "대중교통",
              "budget": { "per_person_limit": 150000 },
              "constraints": ["KTX랑 숙소비는 따로 계산", "매운 메뉴 제외"],
              "host_requests": ["매운 거 못 먹는 사람이 있어서 너무 매운 메뉴는 빼줘"],
              "must_include": [],
              "must_exclude": ["매운 음식"],
              "conflicts": []
            }
            """;

    private static final String GYEONGJU_VEGAN_CONDITIONS = """
            {
              "destination": "경주",
              "dates": { "duration_nights": 1 },
              "transport": "렌트카",
              "budget": { "per_person_limit": 350000 },
              "constraints": ["비건 식사 필요", "채식 메뉴 또는 사찰음식 가능한 식당"],
              "host_requests": ["비건이 있어서 채식 메뉴나 사찰음식 가능한 식당이면 좋겠어"],
              "must_include": [],
              "must_exclude": ["고기", "해산물", "유제품", "계란"],
              "conflicts": []
            }
            """;

    private static final String JEJU_2N3D_CONDITIONS = """
            {
              "destination": "제주",
              "dates": { "duration_nights": 2 },
              "transport": "렌트카",
              "budget": { "per_person_limit": 300000 },
              "constraints": ["첫날은 오후 도착이라 무리하지 않게", "숙소는 중문 근처"],
              "host_requests": [],
              "must_include": [],
              "must_exclude": [],
              "conflicts": []
            }
            """;

    private static final String GYEONGJU_DAY_NIGHT_CONDITIONS = """
            {
              "destination": "경주",
              "dates": { "duration_nights": 0 },
              "transport": "렌트카",
              "budget": { "per_person_limit": 100000 },
              "constraints": ["동궁과월지 야경은 꼭 넣어줘"],
              "host_requests": [],
              "must_include": ["동궁과월지 야경"],
              "must_exclude": [],
              "conflicts": []
            }
            """;

    private static final String GAPYEONG_BARBECUE_CONDITIONS = """
            {
              "destination": "가평",
              "dates": { "duration_nights": 1 },
              "transport": "렌트카",
              "budget": { "per_person_limit": 150000 },
              "constraints": ["저녁 바베큐 준비 및 장보기 일정 필요", "장보기 시 무알코올 음료를 함께 준비"],
              "host_requests": ["펜션에서 바베큐하고 술 못 마시는 사람 음료도 챙겨줘"],
              "must_include": ["펜션에서 바베큐", "저녁"],
              "must_exclude": [],
              "conflicts": [
                {
                  "topic": "펜션 vs 글램핑",
                  "opinions": [
                    { "who": "지민", "wants": "펜션에서 바베큐" },
                    { "who": "하늘", "wants": "글램핑장에서 쉬기" }
                  ]
                }
              ]
            }
            """;

    @Test
    void repairsDay2MissingRestaurant() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "해변 산책 코스", "광안리 중심 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "광안리해수욕장", "관광지", "13:00", 0),
                        place(1, 2, "송정3대국밥", "식당", "14:30", 12000),
                        place(1, 3, "호텔 아쿠아펠리스", "숙소", "15:30", 0),
                        place(1, 4, "스타벅스 광안리점", "카페", "20:00", 7000),
                        place(2, 1, "호텔 아쿠아펠리스", "숙소", "10:30", 0),
                        place(2, 2, "민락수변공원", "관광지", "12:00", 0),
                        place(2, 3, "해운대해수욕장", "관광지", "14:00", 0),
                        place(2, 4, "부산역", "이동", "16:00", 0)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", BUSAN_1N2D_CONDITIONS);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons()).anyMatch(r -> r.contains("2일차에 식당"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, List.of());
        assertThat(repairResult.appliedRepairs()).isNotEmpty();

        PlannerValidationService.ValidationResult revalidation =
                validationService.validate(repairResult.repairedPlan(), "A", BUSAN_1N2D_CONDITIONS);
        assertThat(revalidation.valid())
                .as("보정 후 검증 통과해야 합니다. 실패 사유: " + revalidation.reasons())
                .isTrue();

        assertThat(repairResult.repairedPlan().places().stream()
                .filter(p -> Integer.valueOf(2).equals(p.dayNumber()) && "식당".equals(p.category()))
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void repairsMissingStation() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "해변 산책 코스", "광안리 중심 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "광안리해수욕장", "관광지", "13:00", 0),
                        place(1, 2, "송정3대국밥", "식당", "14:30", 12000),
                        place(1, 3, "호텔 아쿠아펠리스", "숙소", "15:30", 0),
                        place(1, 4, "스타벅스 광안리점", "카페", "20:00", 7000),
                        place(2, 1, "호텔 아쿠아펠리스", "숙소", "10:30", 0),
                        place(2, 2, "국제밀면 본점", "식당", "12:00", 9000),
                        place(2, 3, "해운대해수욕장", "관광지", "14:00", 0)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", BUSAN_1N2D_CONDITIONS);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons()).anyMatch(r -> r.contains("부산역"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, List.of());
        assertThat(repairResult.appliedRepairs()).anyMatch(r -> r.contains("부산역"));

        AiPlannerResponse.CandidatePlan repaired = repairResult.repairedPlan();
        AiPlannerResponse.PlaceItem lastPlace = repaired.places().get(repaired.places().size() - 1);
        assertThat(lastPlace.name()).isEqualTo("부산역");
    }

    @Test
    void repairsCheckoutNameMismatch() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "해변 산책 코스", "광안리 중심 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "광안리해수욕장", "관광지", "13:00", 0),
                        place(1, 2, "송정3대국밥", "식당", "14:30", 12000),
                        place(1, 3, "호텔 아쿠아펠리스", "숙소", "15:30", 0),
                        place(1, 4, "스타벅스 광안리점", "카페", "20:00", 7000),
                        place(2, 1, "아쿠아펠리스호텔", "숙소", "10:30", 0),
                        place(2, 2, "국제밀면 본점", "식당", "12:00", 9000),
                        place(2, 3, "해운대해수욕장", "관광지", "14:00", 0),
                        place(2, 4, "부산역", "이동", "16:00", 0)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", BUSAN_1N2D_CONDITIONS);
        assertThat(validation.reasons()).anyMatch(r -> r.contains("체크아웃 숙소명은 체크인 숙소명과"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, List.of());
        assertThat(repairResult.appliedRepairs()).anyMatch(r -> r.contains("체크아웃 숙소명 보정"));

        AiPlannerResponse.PlaceItem checkout = repairResult.repairedPlan().places().stream()
                .filter(p -> Integer.valueOf(2).equals(p.dayNumber()) && "숙소".equals(p.category()))
                .findFirst().orElseThrow();
        assertThat(checkout.name()).isEqualTo("호텔 아쿠아펠리스");
    }

    @Test
    void skipsRepairWhenDayIsMissing() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "해변 산책 코스", "광안리 중심 일정입니다.",
                30000, BigDecimal.valueOf(10.0),
                List.of(
                        place(1, 1, "광안리해수욕장", "관광지", "13:00", 0),
                        place(1, 2, "송정3대국밥", "식당", "14:30", 12000),
                        place(1, 3, "호텔 아쿠아펠리스", "숙소", "15:30", 0),
                        place(1, 4, "스타벅스 광안리점", "카페", "20:00", 7000)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", BUSAN_1N2D_CONDITIONS);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons()).anyMatch(r -> r.contains("2일차 일정이 없습니다"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, List.of());
        assertThat(repairResult.appliedRepairs()).isEmpty();
    }

    @Test
    void respectsForbiddenPlaceNames() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "B", "시장 식도락 코스", "B안 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "자갈치시장", "관광지", "13:00", 0),
                        place(1, 2, "가야밀면", "식당", "14:30", 9000),
                        place(1, 3, "호메르스호텔", "숙소", "15:30", 0),
                        place(1, 4, "투썸플레이스 부산광안리점", "카페", "20:00", 7000),
                        place(2, 1, "호메르스호텔", "숙소", "10:30", 0),
                        place(2, 2, "해운대해수욕장", "관광지", "12:00", 0),
                        place(2, 3, "민락수변공원", "관광지", "14:00", 0),
                        place(2, 4, "부산역", "이동", "16:00", 0)
                )
        );

        List<String> forbidden = List.of("밀양순대돼지국밥", "합천일류돼지국밥");

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "B", BUSAN_1N2D_CONDITIONS, forbidden);
        assertThat(validation.reasons()).anyMatch(r -> r.contains("2일차에 식당"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, forbidden);
        assertThat(repairResult.appliedRepairs()).isNotEmpty();

        AiPlannerResponse.PlaceItem addedRestaurant = repairResult.repairedPlan().places().stream()
                .filter(p -> Integer.valueOf(2).equals(p.dayNumber()) && "식당".equals(p.category()))
                .findFirst().orElseThrow();
        assertThat(addedRestaurant.name()).isNotIn("밀양순대돼지국밥", "합천일류돼지국밥");
    }

    @Test
    void avoidsSpicyBackupRestaurantWhenSpicyFoodIsExcluded() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "B", "시장 식도락 코스", "B안 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "자갈치시장", "관광지", "13:00", 0),
                        place(1, 2, "가야밀면", "식당", "14:30", 9000),
                        place(1, 3, "호메르스호텔", "숙소", "15:30", 0),
                        place(1, 4, "투썸플레이스 부산광안리점", "카페", "20:00", 7000),
                        place(2, 1, "호메르스호텔", "숙소", "10:30", 0),
                        place(2, 2, "해운대해수욕장", "관광지", "12:00", 0),
                        place(2, 3, "민락수변공원", "관광지", "14:00", 0),
                        place(2, 4, "부산역", "이동", "16:00", 0)
                )
        );

        List<String> forbidden = List.of("밀양순대돼지국밥", "합천일류돼지국밥");
        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "B", BUSAN_SPICY_EXCLUDED_CONDITIONS, forbidden);

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_SPICY_EXCLUDED_CONDITIONS, validation, forbidden);

        AiPlannerResponse.PlaceItem addedRestaurant = repairResult.repairedPlan().places().stream()
                .filter(p -> Integer.valueOf(2).equals(p.dayNumber()) && "식당".equals(p.category()))
                .findFirst().orElseThrow();
        assertThat(addedRestaurant.name()).isEqualTo("해운대암소갈비집");
    }

    @Test
    void insertsOnlyVeganCompatibleBackupRestaurantWhenVeganMealIsRequired() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "경주 문화 산책", "비건 식사를 포함한 일정입니다.",
                34000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "대릉원", "관광지", "12:00", 3000),
                        place(1, 2, "향적원", "식당", "13:30", 20000),
                        place(1, 3, "힐튼 경주", "숙소", "15:30", 0),
                        place(1, 4, "첨성대", "관광지", "18:00", 0),
                        place(2, 1, "힐튼 경주", "숙소", "10:30", 0),
                        place(2, 2, "국립경주박물관", "관광지", "13:00", 0),
                        place(2, 3, "카페 아덴", "카페", "15:00", 7000)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", GYEONGJU_VEGAN_CONDITIONS);
        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, GYEONGJU_VEGAN_CONDITIONS, validation, List.of());

        AiPlannerResponse.PlaceItem addedRestaurant = repairResult.repairedPlan().places().stream()
                .filter(p -> Integer.valueOf(2).equals(p.dayNumber()) && "식당".equals(p.category()))
                .findFirst().orElseThrow();
        assertThat(addedRestaurant.name()).isEqualTo("마조르");
    }

    @Test
    void movesNightViewMustIncludeToEveningTime() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "경주 야경 산책", "동궁과월지 야경을 포함한 일정입니다.",
                31000, BigDecimal.valueOf(8.0),
                List.of(
                        place(1, 1, "연화바루", "식당", "12:00", 18000),
                        place(1, 2, "대릉원", "관광지", "13:30", 3000),
                        place(1, 3, "향미사", "카페", "15:00", 7000),
                        place(1, 4, "동궁과월지", "관광지", "16:00", 3000)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", GYEONGJU_DAY_NIGHT_CONDITIONS);
        assertThat(validation.reasons())
                .anyMatch(reason -> reason.contains("18:00 이후"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, GYEONGJU_DAY_NIGHT_CONDITIONS, validation, List.of());
        PlannerValidationService.ValidationResult revalidation =
                validationService.validate(repairResult.repairedPlan(), "A", GYEONGJU_DAY_NIGHT_CONDITIONS);

        assertThat(repairResult.appliedRepairs()).anyMatch(reason -> reason.contains("야경/일몰"));
        assertThat(revalidation.valid())
                .as("야경 필수 장소는 repair 후 18:00 이후로 이동해야 합니다: " + revalidation.reasons())
                .isTrue();
        assertThat(repairResult.repairedPlan().places()).anyMatch(place ->
                "동궁과월지".equals(place.name()) && "19:00".equals(place.visitTime()));
    }

    @Test
    void repairsGapyeongBarbecuePlanToShoppingAndLodgingPreparationFlow() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "가평 펜션 바베큐", "바베큐 요청이 있지만 장보기와 숙소 준비가 빠진 일정입니다.",
                42000, BigDecimal.valueOf(12.0),
                List.of(
                        place(1, 1, "남이섬", "관광지", "13:00", 16000),
                        place(1, 2, "켄싱턴리조트 가평", "숙소", "15:30", 0),
                        place(1, 3, "송원막국수", "식당", "18:00", 12000),
                        place(2, 1, "켄싱턴리조트 가평", "숙소", "10:30", 0),
                        place(2, 2, "동기간", "식당", "12:00", 15000),
                        place(2, 3, "자라섬", "관광지", "14:00", 0),
                        place(2, 4, "코미호미", "카페", "15:30", 7000)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", GAPYEONG_BARBECUE_CONDITIONS);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reasons())
                .anyMatch(reason -> reason.contains("바베큐") || reason.contains("쇼핑"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, GAPYEONG_BARBECUE_CONDITIONS, validation, List.of());
        PlannerValidationService.ValidationResult revalidation =
                validationService.validate(repairResult.repairedPlan(), "A", GAPYEONG_BARBECUE_CONDITIONS);

        assertThat(repairResult.appliedRepairs()).anyMatch(reason -> reason.contains("바베큐"));
        assertThat(revalidation.valid())
                .as("바베큐/장보기 후처리 후 검증 통과해야 합니다: " + revalidation.reasons())
                .isTrue();
        assertThat(repairResult.repairedPlan().places())
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "쇼핑".equals(place.category())
                        && place.description().contains("바베큐"));
        assertThat(repairResult.repairedPlan().places())
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "숙소".equals(place.category())
                        && place.description().contains("저녁 바베큐"));
        assertThat(repairResult.repairedPlan().places())
                .noneMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "식당".equals(place.category())
                        && place.visitTime() != null
                        && place.visitTime().compareTo("17:00") >= 0);
    }

    @Test
    void movesCheckoutToFirstSlotAndNormalizesVisitTimeOrder() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "해변 산책 코스", "광안리 중심 일정입니다.",
                56000, BigDecimal.valueOf(15.0),
                List.of(
                        place(1, 1, "광안리해수욕장", "관광지", "13:00", 0),
                        place(1, 2, "호텔 아쿠아펠리스", "숙소", "18:00", 0),
                        place(1, 3, "송정3대국밥", "식당", "17:00", 12000),
                        place(1, 4, "스타벅스 광안리점", "카페", "20:00", 7000),
                        place(2, 1, "국제밀면 본점", "식당", "12:00", 9000),
                        place(2, 2, "호텔 아쿠아펠리스", "숙소", "14:00", 0),
                        place(2, 3, "민락수변공원", "관광지", "15:00", 0),
                        place(2, 4, "부산역", "이동", "16:00", 0)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", BUSAN_1N2D_CONDITIONS);
        assertThat(validation.reasons())
                .anyMatch(r -> r.contains("숙소 체크인은"))
                .anyMatch(r -> r.contains("마지막 날 order_index=1"))
                .anyMatch(r -> r.contains("visit_time은"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, BUSAN_1N2D_CONDITIONS, validation, List.of());
        PlannerValidationService.ValidationResult revalidation =
                validationService.validate(repairResult.repairedPlan(), "A", BUSAN_1N2D_CONDITIONS);

        assertThat(revalidation.valid())
                .as("결정 가능한 체크인/체크아웃 순서는 기계 보정으로 통과해야 합니다: " + revalidation.reasons())
                .isTrue();
    }

    @Test
    void removesMiddleDayAccommodationFromDenseTwoNightPlan() {
        AiPlannerResponse.CandidatePlan plan = new AiPlannerResponse.CandidatePlan(
                "A", "한라산 트레킹", "한라산 중심 일정입니다.",
                86000, BigDecimal.valueOf(30.0),
                List.of(
                        place(1, 1, "중문색달해변", "관광지", "13:00", 0),
                        place(1, 2, "호텔더본 제주", "숙소", "15:30", 0),
                        place(1, 3, "흑돈가 중문점", "식당", "18:00", 25000),
                        place(1, 4, "더클리프", "카페", "20:00", 7000),
                        place(2, 1, "한라산국립공원 영실탐방로", "관광지", "09:00", 0),
                        place(2, 2, "제주 고기국수 만세국수", "식당", "13:00", 12000),
                        place(2, 3, "도두봉", "관광지", "15:00", 0),
                        place(2, 4, "제주봄날", "카페", "17:00", 7000),
                        place(2, 5, "동문시장", "쇼핑", "19:00", 20000),
                        place(2, 6, "호텔더본 제주", "숙소", "20:30", 0),
                        place(3, 1, "호텔더본 제주", "숙소", "10:30", 0),
                        place(3, 2, "제주김만복", "식당", "12:00", 12000),
                        place(3, 3, "이호테우해변", "관광지", "13:30", 0),
                        place(3, 4, "노티드 제주", "카페", "15:00", 6000),
                        place(3, 5, "제주국제공항", "이동", "16:30", 0)
                )
        );

        PlannerValidationService.ValidationResult validation =
                validationService.validate(plan, "A", JEJU_2N3D_CONDITIONS);
        assertThat(validation.reasons())
                .anyMatch(reason -> reason.contains("2박3일 places 개수"))
                .anyMatch(reason -> reason.contains("2일차 places 개수"))
                .anyMatch(reason -> reason.contains("숙소 category"));

        PlannerRepairService.RepairResult repairResult =
                repairService.tryRepair(plan, JEJU_2N3D_CONDITIONS, validation, List.of());
        PlannerValidationService.ValidationResult revalidation =
                validationService.validate(repairResult.repairedPlan(), "A", JEJU_2N3D_CONDITIONS);

        assertThat(revalidation.valid())
                .as("중간 날짜 숙소 복귀는 제거되어야 합니다: " + revalidation.reasons())
                .isTrue();
        assertThat(repairResult.appliedRepairs()).anyMatch(reason -> reason.contains("숙소 복귀"));
        assertThat(repairResult.repairedPlan().places())
                .noneMatch(place -> Integer.valueOf(2).equals(place.dayNumber())
                        && "숙소".equals(place.category()));
    }

    private static AiPlannerResponse.PlaceItem place(
            int day, int order, String name, String category, String time, int cost
    ) {
        return new AiPlannerResponse.PlaceItem(
                day, order, name, category,
                "테스트 장소입니다.", cost, 60, time,
                "부산", "부산 " + name,
                null, null, null
        );
    }
}
