package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerFallbackServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlannerFallbackService fallbackService = new PlannerFallbackService(objectMapper);
    private final PlannerValidationService validationService = new PlannerValidationService(objectMapper);

    @Test
    void createsValidBusanFallbackEndingAtBusanStation() {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "대중교통",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["KTX랑 숙소비는 따로 계산", "숙소는 광안리 근처"],
                  "host_requests": ["KTX랑 숙소비는 따로 계산한다고 보면 돼"],
                  "must_include": [],
                  "must_exclude": ["매운 음식"],
                  "conflicts": []
                }
                """;

        AiPlannerResponse.CandidatePlan fallback = fallbackService.create("A", conditionsJson, List.of());

        PlannerValidationService.ValidationResult result =
                validationService.validate(fallback, "A", conditionsJson);

        assertThat(result.valid()).isTrue();
        assertThat(fallback.places()).hasSizeBetween(7, 9);
        assertThat(fallback.places().get(fallback.places().size() - 1).name()).isEqualTo("부산역");
    }

    @Test
    void createsDistinctBusanBPlanWhenAPlacesAreForbidden() {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "대중교통",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["KTX랑 숙소비는 따로 계산", "숙소는 광안리 근처"],
                  "host_requests": ["KTX랑 숙소비는 따로 계산한다고 보면 돼"],
                  "must_include": [],
                  "must_exclude": ["매운 음식"],
                  "conflicts": []
                }
                """;
        AiPlannerResponse.CandidatePlan planA = fallbackService.create("A", conditionsJson, List.of());
        List<String> forbidden = planA.places().stream()
                .filter(place -> !"숙소".equals(place.category()) && !"이동".equals(place.category()))
                .map(AiPlannerResponse.PlaceItem::name)
                .toList();

        AiPlannerResponse.CandidatePlan planB = fallbackService.create("B", conditionsJson, forbidden);
        PlannerValidationService.ValidationResult result =
                validationService.validate(planB, "B", conditionsJson, forbidden);

        assertThat(result.valid()).isTrue();
        assertThat(planB.places()).hasSizeBetween(7, 9);
    }

    @Test
    void createsValidGyeongjuVeganFallbackIncludingDonggungAndWolji() {
        String conditionsJson = """
                {
                  "destination": "경주",
                  "dates": { "duration_nights": 2 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 350000 },
                  "constraints": ["동궁과월지 야경은 마지막 날 꼭 넣어줘", "비건 식사 필요"],
                  "host_requests": [],
                  "must_include": ["동궁과월지 야경"],
                  "must_exclude": ["고기", "해산물", "유제품", "계란"],
                  "conflicts": []
                }
                """;

        AiPlannerResponse.CandidatePlan fallback = fallbackService.create("A", conditionsJson, List.of());
        PlannerValidationService.ValidationResult result =
                validationService.validate(fallback, "A", conditionsJson);

        assertThat(result.valid()).isTrue();
        assertThat(fallback.places()).hasSizeBetween(10, 12);
        assertThat(fallback.places()).anyMatch(place -> "동궁과월지".equals(place.name()));
    }

    @Test
    void createsGyeongjuBPlanWhenActualAPlanAlreadyUsedCulturePlaces() {
        String conditionsJson = """
                {
                  "destination": "경주",
                  "dates": { "duration_nights": 2 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 350000 },
                  "constraints": ["동궁과월지 야경은 마지막 날 꼭 넣어줘", "비건 식사 필요", "숙소는 보문관광단지 쪽 호텔"],
                  "host_requests": ["문화재를 많이 보고 싶다는 사람도 있고, 황리단길 카페랑 포토스팟 위주로 여유롭게 다니고 싶다는 사람도 있어", "황남빵은 돌아가기 전쯤 포장하고 싶어"],
                  "must_include": ["동궁과월지 야경", "황남빵"],
                  "must_exclude": ["고기", "해산물", "유제품", "계란"],
                  "conflicts": [
                    {
                      "topic": "문화재 관람 vs 황리단길 카페",
                      "opinions": [
                        { "who": "준영", "wants": "문화재를 많이 보고 싶다" },
                        { "who": "현우", "wants": "황리단길 카페랑 포토스팟 위주로 여유롭게 다니고 싶다" }
                      ]
                    }
                  ]
                }
                """;
        List<String> forbidden = List.of(
                "연화바루",
                "경주향교",
                "국립경주박물관",
                "불국사",
                "석굴암",
                "향적원",
                "대릉원",
                "동궁과월지",
                "황리단길",
                "쑥부쟁이",
                "연화",
                "황남빵"
        );

        AiPlannerResponse.CandidatePlan fallback = fallbackService.create("B", conditionsJson, forbidden);
        PlannerValidationService.ValidationResult result =
                validationService.validate(fallback, "B", conditionsJson, forbidden);

        assertThat(result.valid())
                .as("B fallback 검증 실패: " + result.reasons())
                .isTrue();
        assertThat(fallback.places()).hasSizeBetween(10, 12);
        assertThat(fallback.places())
                .filteredOn(place -> !"숙소".equals(place.category()) && !"이동".equals(place.category()))
                .extracting(AiPlannerResponse.PlaceItem::name)
                .doesNotContain("불국사", "석굴암", "대릉원", "국립경주박물관", "월정제과");
    }

    @Test
    void createsValidChuncheonFoodFocusedDayTripFallbacks() {
        String conditionsJson = """
                {
                  "destination": "춘천",
                  "dates": { "duration_nights": 0 },
                  "transport": "대중교통",
                  "budget": { "per_person_limit": 100000 },
                  "constraints": ["당일치기", "닭갈비 먹고 자연 풍경도 보고 싶어"],
                  "host_requests": ["춘천 당일치기 여행계획 짜줘. 닭갈비 먹고 자연 풍경도 보고 싶어."],
                  "must_include": [],
                  "must_exclude": [],
                  "conflicts": []
                }
                """;

        assertCreatesValidDistinctFallbacks(conditionsJson);
    }

    @Test
    void createsValidGangneungFallbackForDemoMissionWithJumunjinSeafood() {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 250000 },
                  "constraints": ["너무 빡빡하지 않게", "디카페인 옵션 필요"],
                  "host_requests": ["서핑하고 싶다는 사람도 있고, 카페에서 쉬고 싶다는 사람도 있어", "회는 주문진항 쪽이면 좋겠어"],
                  "must_include": ["초당순두부", "짬뽕순두부", "회는 주문진항 쪽에서 먹기"],
                  "must_exclude": [],
                  "conflicts": [
                    {
                      "topic": "서핑 vs 카페 휴식",
                      "opinions": [
                        { "who": "A", "wants": "서핑" },
                        { "who": "B", "wants": "카페에서 쉬기" }
                      ]
                    }
                  ]
                }
                """;

        assertCreatesValidDistinctFallbacks(conditionsJson);
    }

    @Test
    void createsGapyeongBarbecueFallbackWithShoppingAndNoExternalDinner() {
        String conditionsJson = """
                {
                  "destination": "가평",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["첫날 점심은 춘천 닭갈비", "렌트카랑 숙소비는 별도 계산"],
                  "host_requests": ["술 못 마시는 사람을 위한 음료 필요"],
                  "must_include": ["남이섬"],
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

        AiPlannerResponse.CandidatePlan fallback = fallbackService.create("A", conditionsJson, List.of());
        PlannerValidationService.ValidationResult result =
                validationService.validate(fallback, "A", conditionsJson);

        assertThat(result.valid())
                .as("가평 바베큐 fallback 검증 실패: " + result.reasons())
                .isTrue();
        assertThat(fallback.places())
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "쇼핑".equals(place.category())
                        && place.description().contains("바베큐"));
        assertThat(fallback.places())
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "숙소".equals(place.category())
                        && place.description().contains("저녁 바베큐"));
        assertThat(fallback.places())
                .noneMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "식당".equals(place.category())
                        && place.visitTime() != null
                        && place.visitTime().compareTo("17:00") >= 0);
    }

    @Test
    void createsDistinctPlansForSixDemoScenarios() {
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "제주",
                  "dates": { "duration_nights": 2 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 300000 },
                  "constraints": ["첫날은 오후 도착이라 무리하지 않게", "숙소는 중문 근처", "마지막 날은 제주시 쪽에서 조금 놀다가 공항으로 이동"],
                  "host_requests": ["항공권, 숙소, 렌트카 비용은 제외"],
                  "must_include": [],
                  "must_exclude": ["해산물", "회", "조개류"],
                  "conflicts": [
                    {
                      "topic": "한라산 vs 오름",
                      "opinions": [
                        { "who": "현승", "wants": "한라산 한번 가보고 싶음" },
                        { "who": "경모", "wants": "오름 정도가 좋을듯" }
                      ]
                    }
                  ]
                }
                """);
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "대중교통",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["KTX랑 숙소비는 따로 계산", "숙소는 광안리 근처"],
                  "host_requests": ["KTX랑 숙소비는 따로 계산한다고 보면 돼"],
                  "must_include": [],
                  "must_exclude": ["매운 음식"],
                  "conflicts": [
                    {
                      "topic": "광안리 야경 vs 로컬 맛집",
                      "opinions": [
                        { "who": "석용", "wants": "광안리 야경" },
                        { "who": "현승", "wants": "돼지국밥과 밀면" }
                      ]
                    }
                  ]
                }
                """);
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 250000 },
                  "constraints": ["숙소는 경포대 근처", "너무 빡빡하지 않게", "기름값이랑 톨비는 따로 계산"],
                  "host_requests": ["디카페인 가능한 카페면 좋아"],
                  "must_include": ["초당순두부", "짬뽕순두부"],
                  "must_exclude": [],
                  "conflicts": [
                    {
                      "topic": "서핑 vs 카페 휴식",
                      "opinions": [
                        { "who": "지훈", "wants": "서핑 해보고 싶음" },
                        { "who": "민호", "wants": "카페에서 쉬는 게 더 좋음" }
                      ]
                    }
                  ]
                }
                """);
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "경주",
                  "dates": { "duration_nights": 2 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 350000 },
                  "constraints": ["동궁과월지 야경은 마지막 날 꼭 넣어줘", "비건 식사 필요"],
                  "host_requests": [],
                  "must_include": ["동궁과월지 야경"],
                  "must_exclude": ["고기", "해산물", "유제품", "계란"],
                  "conflicts": []
                }
                """);
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "여수",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["기름값은 별도 계산"],
                  "host_requests": ["회는 꼭 먹고 싶어", "갓김치는 가능하면 먹어보고 싶어"],
                  "must_include": ["회"],
                  "must_exclude": [],
                  "conflicts": [
                    {
                      "topic": "일출 vs 케이블카 야경",
                      "opinions": [
                        { "who": "민준", "wants": "향일암 일출" },
                        { "who": "태우", "wants": "케이블카 타고 야경" }
                      ]
                    }
                  ]
                }
                """);
        assertCreatesValidDistinctFallbacks("""
                {
                  "destination": "가평",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["첫날 점심은 춘천 닭갈비", "짚라인은 선택 일정", "렌트카랑 숙소비는 별도 계산"],
                  "host_requests": ["술 못 마시는 사람을 위한 음료 필요"],
                  "must_include": ["남이섬"],
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
                """);
    }

    @Test
    void createsValidYeosuKtxFallbackEndingAtExpoStationFromTransportField() {
        // 미션은 "KTX 타고 여수엑스포역"인데 KTX 신호가 transport 필드에만 들어오는 경우.
        // 검증기는 transport를 보고 역 도착을 요구하므로, fallback도 같은 신호를 봐야 "생성 실패"로 터지지 않는다.
        String conditionsJson = """
                {
                  "destination": "여수",
                  "dates": { "duration_nights": 1 },
                  "transport": "KTX",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["기름값은 별도 계산"],
                  "host_requests": ["회는 꼭 먹고 싶어", "갓김치는 가능하면 먹어보고 싶어"],
                  "must_include": ["회"],
                  "must_exclude": [],
                  "conflicts": [
                    {
                      "topic": "일출 vs 케이블카 야경",
                      "opinions": [
                        { "who": "민준", "wants": "향일암 일출" },
                        { "who": "태우", "wants": "케이블카 타고 야경" }
                      ]
                    }
                  ]
                }
                """;

        AiPlannerResponse.CandidatePlan planA = fallbackService.create("A", conditionsJson, List.of());
        PlannerValidationService.ValidationResult resultA =
                validationService.validate(planA, "A", conditionsJson);
        assertThat(resultA.valid())
                .as("여수 KTX fallback(A) 검증 실패: " + resultA.reasons())
                .isTrue();
        assertThat(planA.places().get(planA.places().size() - 1).name()).isEqualTo("여수엑스포역");

        List<String> forbidden = planA.places().stream()
                .filter(place -> !"숙소".equals(place.category()) && !"이동".equals(place.category()))
                .map(AiPlannerResponse.PlaceItem::name)
                .toList();
        AiPlannerResponse.CandidatePlan planB = fallbackService.create("B", conditionsJson, forbidden);
        PlannerValidationService.ValidationResult resultB =
                validationService.validate(planB, "B", conditionsJson, forbidden);
        assertThat(resultB.valid())
                .as("여수 KTX fallback(B) 검증 실패: " + resultB.reasons())
                .isTrue();
        assertThat(planB.places().get(planB.places().size() - 1).name()).isEqualTo("여수엑스포역");
    }

    private void assertCreatesValidDistinctFallbacks(String conditionsJson) {
        AiPlannerResponse.CandidatePlan planA = fallbackService.create("A", conditionsJson, List.of());
        PlannerValidationService.ValidationResult validationA =
                validationService.validate(planA, "A", conditionsJson);
        assertThat(validationA.valid())
                .as("A fallback 검증 실패: " + validationA.reasons())
                .isTrue();

        List<String> forbidden = planA.places().stream()
                .filter(place -> !"숙소".equals(place.category()) && !"이동".equals(place.category()))
                .map(AiPlannerResponse.PlaceItem::name)
                .toList();
        AiPlannerResponse.CandidatePlan planB = fallbackService.create("B", conditionsJson, forbidden);
        PlannerValidationService.ValidationResult validationB =
                validationService.validate(planB, "B", conditionsJson, forbidden);
        assertThat(validationB.valid())
                .as("B fallback 검증 실패: " + validationB.reasons())
                .isTrue();
    }
}
