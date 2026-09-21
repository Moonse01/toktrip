package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerValidationServiceTest {

    private final PlannerValidationService service = new PlannerValidationService(new ObjectMapper());

    @Test
    void passesStructurallyValidOneNightPlan() {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["숙소는 광안리 근처"],
                  "host_requests": [],
                  "must_include": [],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "호텔 아쿠아펠리스", "숙소", 0, "15:30"),
                place(1, 2, "광안리해수욕장", "관광지", 0, "16:30"),
                place(1, 3, "송정3대국밥", "식당", 12000, "18:00"),
                place(1, 4, "모모스커피 해운대점", "카페", 7000, "20:00"),
                place(2, 1, "호텔 아쿠아펠리스", "숙소", 0, "10:30"),
                place(2, 2, "가야밀면", "식당", 9000, "12:00"),
                place(2, 3, "민락수변공원", "관광지", 0, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void rejectsTwoNightPlanWithoutRestaurantsWhenVeganConstraintExists() {
        String conditionsJson = """
                {
                  "destination": "경주",
                  "dates": { "duration_nights": 2 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 350000 },
                  "constraints": ["비건 식사 필요", "채식 메뉴 또는 사찰음식 가능한 식당"],
                  "host_requests": ["비건이 있어서 채식 메뉴나 사찰음식 가능한 식당이면 좋겠어"],
                  "must_include": ["동궁과월지 야경"],
                  "must_exclude": ["고기", "해산물", "유제품", "계란"]
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "롯데호텔 경주", "숙소", 0, "15:30"),
                place(1, 2, "불국사", "관광지", 4000, "17:00"),
                place(1, 3, "첨성대", "관광지", 2000, "18:30"),
                place(1, 4, "대릉원", "관광지", 3000, "20:00"),
                place(2, 1, "석굴암", "관광지", 6000, "09:00"),
                place(2, 2, "국립경주박물관", "관광지", 0, "12:00"),
                place(2, 3, "포석정", "관광지", 2000, "15:00"),
                place(3, 1, "롯데호텔 경주", "숙소", 0, "10:30"),
                place(3, 2, "동궁과월지", "관광지", 0, "18:30"),
                place(3, 3, "황남빵", "쇼핑", 8000, "19:30")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("식당"))
                .anyMatch(reason -> reason.contains("비건") || reason.contains("채식"));
    }

    @Test
    void rejectsNightViewMustIncludeScheduledBeforeEvening() {
        String conditionsJson = """
                {
                  "destination": "경주",
                  "dates": { "duration_nights": 0 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 100000 },
                  "constraints": ["동궁과월지 야경은 꼭 넣어줘"],
                  "host_requests": [],
                  "must_include": ["동궁과월지 야경"],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "연화바루", "식당", 18000, "12:00"),
                place(1, 2, "대릉원", "관광지", 3000, "13:30"),
                place(1, 3, "향미사", "카페", 7000, "15:00"),
                place(1, 4, "동궁과월지", "관광지", 3000, "16:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("18:00 이후") && reason.contains("동궁과월지 야경"));
    }

    @Test
    void rejectsForbiddenPlaceAndMissingTrainStationForBPlan() {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "대중교통",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["KTX랑 숙소비는 따로 계산", "숙소는 광안리 근처"],
                  "host_requests": ["KTX랑 숙소비는 따로 계산한다고 보면 돼"],
                  "must_include": [],
                  "must_exclude": ["매운 음식"]
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "B",
                place(1, 1, "호메르스호텔", "숙소", 0, "15:30"),
                place(1, 2, "광안리해수욕장", "관광지", 0, "16:30"),
                place(1, 3, "송정3대국밥", "식당", 12000, "18:00"),
                place(1, 4, "오션커피 광안리점", "카페", 7000, "20:00"),
                place(2, 1, "호메르스호텔", "숙소", 0, "10:30"),
                place(2, 2, "가야밀면", "식당", 9000, "12:00"),
                place(2, 3, "민락수변공원", "관광지", 0, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(
                plan,
                "B",
                conditionsJson,
                List.of("광안리해수욕장")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("A안 금지 장소"))
                .anyMatch(reason -> reason.contains("부산역"));
    }

    @Test
    void rejectsTransitCardWhenTransportWasNotSpecified() {
        String conditionsJson = """
                {
                  "destination": "부산",
                  "dates": { "duration_nights": 1 },
                  "transport": "미정",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": [],
                  "host_requests": ["부산, 1박2일, 맛있는거 먹기"],
                  "must_include": [],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "호텔 아쿠아펠리스", "숙소", 0, "15:30"),
                place(1, 2, "광안리해수욕장", "관광지", 0, "16:30"),
                place(1, 3, "송정3대국밥", "식당", 12000, "18:00"),
                place(1, 4, "모모스커피 해운대점", "카페", 7000, "20:00"),
                place(2, 1, "호텔 아쿠아펠리스", "숙소", 0, "10:30"),
                place(2, 2, "가야밀면", "식당", 9000, "12:00"),
                place(2, 3, "민락수변공원", "관광지", 0, "14:00"),
                place(2, 4, "부산역", "이동", 0, "16:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("이동수단/귀가 거점"));
    }

    @Test
    void allowsRestaurantNameContainingHoeGwanAndNegatedSeafoodDescription() {
        String conditionsJson = """
                {
                  "destination": "제주도",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["해산물 알레르기"],
                  "host_requests": [],
                  "must_include": [],
                  "must_exclude": ["해산물", "회", "조개류"]
                }
                """;
        AiPlannerResponse.PlaceItem safeRestaurant = new AiPlannerResponse.PlaceItem(
                1, 3, "삼대국수회관", "식당",
                "해산물 없이 고기국수를 즐기는 식당입니다.",
                12000, 60, "18:00",
                "제주시", "제주 삼대국수회관",
                null, null, null
        );
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "제주신라호텔", "숙소", 0, "15:30"),
                place(1, 2, "중문색달해변", "관광지", 0, "16:30"),
                safeRestaurant,
                place(1, 4, "더클리프", "카페", 7000, "20:00"),
                place(2, 1, "제주신라호텔", "숙소", 0, "10:30"),
                place(2, 2, "자매국수 본점", "식당", 12000, "12:00"),
                place(2, 3, "도두봉", "관광지", 0, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid())
                .as("해산물을 제외한다는 설명과 회관 상호명은 금지 음식 오탐이 아니어야 합니다: " + result.reasons())
                .isTrue();
    }

    @Test
    void rejectsFoodFocusedDayTripWithOnlyOneRestaurant() {
        String conditionsJson = """
                {
                  "destination": "전주",
                  "dates": { "duration_nights": 0 },
                  "transport": "미정",
                  "budget": { "per_person_limit": null },
                  "constraints": [],
                  "preferences": ["맛있는 거", "한옥마을"],
                  "host_requests": ["전주, 한옥마을이랑 맛있는 거 먹는 여행으로 짜줘"],
                  "must_include": [],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "B",
                place(1, 1, "전주덕진공원", "관광지", 0, "10:00"),
                place(1, 2, "전주동물원", "관광지", 3000, "11:30"),
                place(1, 3, "전주덕진예술회관", "관광지", 3000, "13:30"),
                place(1, 4, "삼백집 전주본점", "식당", 10000, "17:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "B", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("먹는 여행") && reason.contains("식당"));
    }

    @Test
    void rejectsUnrequestedSeafoodRestaurantForJeonjuFoodTrip() {
        String conditionsJson = """
                {
                  "destination": "전주",
                  "dates": { "duration_nights": 0 },
                  "transport": "미정",
                  "budget": { "per_person_limit": null },
                  "constraints": [],
                  "preferences": ["맛있는 거", "한옥마을"],
                  "host_requests": ["전주, 한옥마을이랑 맛있는 거 먹는 여행으로 짜줘"],
                  "must_include": [],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "예향", "식당", 12000, "11:30"),
                place(1, 2, "전주한옥마을", "관광지", 0, "12:40"),
                place(1, 3, "경기전", "관광지", 3000, "14:20"),
                place(1, 4, "차경", "카페", 7000, "15:30"),
                place(1, 5, "신성바다횟집", "식당", 18000, "16:20"),
                place(1, 6, "한국집", "식당", 21000, "18:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("전주 미식") && reason.contains("횟집"));
    }

    @Test
    void allowsMustIncludeWithAreaAndFoodIntentSplitMatching() {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": [],
                  "host_requests": ["주문진항 쪽 회는 꼭 넣어줘"],
                  "must_include": ["주문진항 쪽 회"],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.PlaceItem jumunjinRestaurant = new AiPlannerResponse.PlaceItem(
                1, 3, "주문진항 해물밥상", "식당",
                "주문진항 근처에서 식사하는 일정입니다.",
                18000, 60, "18:00",
                "강릉 주문진항", "주문진항 식당",
                null, null, null
        );
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "세인트존스호텔", "숙소", 0, "15:30"),
                place(1, 2, "주문진항", "관광지", 0, "16:30"),
                jumunjinRestaurant,
                place(1, 4, "테라로사 경포호수점", "카페", 7000, "20:00"),
                place(2, 1, "세인트존스호텔", "숙소", 0, "10:30"),
                place(2, 2, "초당할머니순두부", "식당", 12000, "12:00"),
                place(2, 3, "오죽헌", "관광지", 3000, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid())
                .as("위치 키워드와 음식 의도를 분리해서 매칭해야 합니다: " + result.reasons())
                .isTrue();
    }

    @Test
    void allowsMustIncludeWithKoreanParticlesAndActionWords() {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": [],
                  "host_requests": ["회는 주문진항 쪽이면 좋겠어"],
                  "must_include": ["회는 주문진항 쪽에서 먹기"],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "세인트존스호텔", "숙소", 0, "15:30"),
                place(1, 2, "주문진항", "관광지", 0, "16:30"),
                new AiPlannerResponse.PlaceItem(
                        1, 3, "주문진항 해물밥상", "식당",
                        "주문진항 근처에서 회와 해산물로 저녁 식사",
                        18000, 60, "18:00",
                        "강릉 주문진항", "주문진항 횟집",
                        null, null, null
                ),
                place(1, 4, "테라로사 경포호수점", "카페", 7000, "20:00"),
                place(2, 1, "세인트존스호텔", "숙소", 0, "10:30"),
                place(2, 2, "초당할머니순두부", "식당", 12000, "12:00"),
                place(2, 3, "오죽헌", "관광지", 3000, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid())
                .as("조사/행동어가 붙은 must_include도 위치+음식 의도로 분리 매칭해야 합니다: " + result.reasons())
                .isTrue();
    }

    @Test
    void allowsRegionalCafeStreetNameWhenItIsAnAttraction() {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": [],
                  "host_requests": ["안목해변 카페거리도 보고 싶어"],
                  "must_include": [],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "B",
                place(1, 1, "세인트존스호텔", "숙소", 0, "15:30"),
                place(1, 2, "안목해변 카페거리", "관광지", 0, "16:30"),
                place(1, 3, "엄지네포장마차", "식당", 15000, "18:00"),
                place(1, 4, "테라로사 경포호수점", "카페", 7000, "20:00"),
                place(2, 1, "세인트존스호텔", "숙소", 0, "10:30"),
                place(2, 2, "초당할머니순두부", "식당", 12000, "12:00"),
                place(2, 3, "오죽헌", "관광지", 3000, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "B", conditionsJson);

        assertThat(result.valid())
                .as("OO 카페거리는 관광지 category일 때 지역명 장소로 허용해야 합니다: " + result.reasons())
                .isTrue();
    }

    @Test
    void rejectsMtBarbecuePlanWithoutShoppingPreparationCard() {
        String conditionsJson = """
                {
                  "destination": "가평",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["저녁 바베큐 준비 및 장보기 일정 필요", "장보기 시 무알코올 음료를 함께 준비"],
                  "host_requests": ["펜션에서 바베큐하고 술 못 마시는 사람 음료도 챙겨줘"],
                  "must_include": ["남이섬", "춘천 닭갈비"],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                new AiPlannerResponse.PlaceItem(
                        1, 1, "명동우미닭갈비", "식당",
                        "춘천 대표 닭갈비 식당에서 첫날 점심을 먹습니다.",
                        15_000, 70, "12:00",
                        "춘천", "춘천 명동우미닭갈비",
                        null, null, null
                ),
                place(1, 2, "남이섬", "관광지", 16000, "13:30"),
                new AiPlannerResponse.PlaceItem(
                        1, 3, "캠프통아일랜드", "숙소",
                        "펜션에서 체크인합니다.",
                        0, 30, "16:00",
                        "가평", "가평 캠프통아일랜드",
                        null, null, null
                ),
                place(2, 1, "캠프통아일랜드", "숙소", 0, "10:30"),
                place(2, 2, "송원", "식당", 15000, "12:00"),
                place(2, 3, "자라섬", "관광지", 0, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("마트") || reason.contains("쇼핑"))
                .anyMatch(reason -> reason.contains("바베큐") && reason.contains("숙소"));
    }

    @Test
    void acceptsMtBarbecuePlanWithShoppingPreparationCard() {
        String conditionsJson = """
                {
                  "destination": "가평",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["저녁 바베큐 준비 및 장보기 일정 필요", "장보기 시 무알코올 음료를 함께 준비"],
                  "host_requests": ["펜션에서 바베큐하고 술 못 마시는 사람 음료도 챙겨줘"],
                  "must_include": ["남이섬", "춘천 닭갈비"],
                  "must_exclude": []
                }
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                new AiPlannerResponse.PlaceItem(
                        1, 1, "명동우미닭갈비", "식당",
                        "춘천 대표 닭갈비 식당에서 첫날 점심을 먹습니다.",
                        15_000, 70, "12:00",
                        "춘천", "춘천 명동우미닭갈비",
                        null, null, null
                ),
                place(1, 2, "남이섬", "관광지", 16000, "13:30"),
                new AiPlannerResponse.PlaceItem(
                        1, 3, "농협하나로마트 가평군농협자라섬점", "쇼핑",
                        "바베큐 재료와 무알코올 음료를 준비합니다.",
                        20_000, 40, "15:40",
                        "가평", "가평 하나로마트",
                        null, null, null
                ),
                new AiPlannerResponse.PlaceItem(
                        1, 4, "캠프통아일랜드", "숙소",
                        "체크인 후 바베큐와 휴식 준비를 합니다.",
                        0, 30, "16:30",
                        "가평", "가평 캠프통아일랜드",
                        null, null, null
                ),
                place(2, 1, "캠프통아일랜드", "숙소", 0, "10:30"),
                place(2, 2, "송원", "식당", 15000, "12:00"),
                place(2, 3, "자라섬", "관광지", 0, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid())
                .as("장보기 카드와 숙소 바베큐 준비 흐름이 있으면 통과해야 합니다: " + result.reasons())
                .isTrue();
    }

    @Test
    void rejectsConflictSunrisePlaceScheduledTooLate() {
        String conditionsJson = """
                {
                  "destination": "여수",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["기름값은 별도 계산"],
                  "host_requests": ["향일암 일출을 보고 싶다는 사람도 있고 케이블카 야경을 보고 싶다는 사람도 있어"],
                  "must_include": [],
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
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "로타리식당", "식당", 15000, "12:00"),
                place(1, 2, "향일암", "관광지", 2000, "13:30"),
                place(1, 3, "모이핀", "카페", 7000, "15:00"),
                place(1, 4, "소노캄 여수", "숙소", 0, "16:30"),
                place(2, 1, "소노캄 여수", "숙소", 0, "10:30"),
                place(2, 2, "이순신광장", "관광지", 0, "12:00"),
                place(2, 3, "수림회포차본점", "식당", 30000, "13:30")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("일출") && reason.contains("04:00~08:30"));
    }

    @Test
    void rejectsConflictCableCarNightPlanWithoutCableCarPlace() {
        String conditionsJson = """
                {
                  "destination": "여수",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 150000 },
                  "constraints": ["기름값은 별도 계산"],
                  "host_requests": ["향일암 일출을 보고 싶다는 사람도 있고 케이블카 야경을 보고 싶다는 사람도 있어"],
                  "must_include": [],
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
        AiPlannerResponse.CandidatePlan plan = plan(
                "B",
                place(1, 1, "로타리식당", "식당", 15000, "12:00"),
                place(1, 2, "이순신광장", "관광지", 0, "13:30"),
                place(1, 3, "모이핀", "카페", 7000, "15:00"),
                place(1, 4, "소노캄 여수", "숙소", 0, "16:30"),
                place(2, 1, "소노캄 여수", "숙소", 0, "10:30"),
                place(2, 2, "오동도", "관광지", 0, "12:00"),
                place(2, 3, "수림회포차본점", "식당", 30000, "13:30")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "B", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("케이블카") && reason.contains("반영되지 않았습니다"));
    }

    @Test
    void rejectsConflictSurfingPlanWithoutSurfingPlace() {
        String conditionsJson = """
                {
                  "destination": "강릉",
                  "dates": { "duration_nights": 1 },
                  "transport": "렌트카",
                  "budget": { "per_person_limit": 250000 },
                  "constraints": ["너무 빡빡하지 않게"],
                  "host_requests": ["서핑하고 싶다는 사람도 있고 카페에서 쉬고 싶다는 사람도 있어"],
                  "must_include": ["초당순두부", "회는 주문진항 쪽에서 먹기"],
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
                """;
        AiPlannerResponse.CandidatePlan plan = plan(
                "A",
                place(1, 1, "초당할머니순두부", "식당", 12000, "11:30"),
                place(1, 2, "경포해변 중앙광장", "관광지", 0, "13:00"),
                place(1, 3, "세인트존스호텔", "숙소", 0, "15:30"),
                place(1, 4, "테라로사 본점", "카페", 7000, "18:00"),
                place(2, 1, "세인트존스호텔", "숙소", 0, "10:30"),
                place(2, 2, "동화가든", "식당", 13000, "11:30"),
                place(2, 3, "주문진활어회센터", "식당", 25000, "14:00")
        );

        PlannerValidationService.ValidationResult result = service.validate(plan, "A", conditionsJson);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("서핑") && reason.contains("반영되지 않았습니다"));
    }

    private AiPlannerResponse.CandidatePlan plan(String label, AiPlannerResponse.PlaceItem... places) {
        return new AiPlannerResponse.CandidatePlan(
                label,
                label + " 테스트 일정",
                "테스트 컨셉",
                0,
                BigDecimal.ZERO,
                List.of(places)
        );
    }

    private AiPlannerResponse.PlaceItem place(
            int dayNumber,
            int orderIndex,
            String name,
            String category,
            int estimatedCost,
            String visitTime
    ) {
        return new AiPlannerResponse.PlaceItem(
                dayNumber,
                orderIndex,
                name,
                category,
                name + " 설명",
                estimatedCost,
                60,
                visitTime,
                "지역 힌트",
                "검색 키워드 " + name,
                null,
                null,
                null
        );
    }
}
