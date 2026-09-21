package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerTransitConsistencyServiceTest {

    private final PlannerTransitConsistencyService service =
            new PlannerTransitConsistencyService(new ObjectMapper());

    private static final String KTX_CONDITIONS = """
            {
              "destination": "부산",
              "transport": "대중교통",
              "constraints": ["KTX 이용"],
              "host_requests": []
            }
            """;

    private static final String SELF_DRIVE_CONDITIONS = """
            {
              "destination": "강릉",
              "transport": "렌트카",
              "constraints": [],
              "host_requests": []
            }
            """;

    private static final String RENTAL_WITH_AIRPORT_CONDITIONS = """
            {
              "destination": "제주",
              "transport": "렌트카",
              "constraints": ["비행기 이동"],
              "host_requests": []
            }
            """;

    @Test
    void copiesExistingStartTransitToOtherCandidateWithoutInventingAnotherHub() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "광안리해수욕장", "관광지", "13:00"),
                place(1, 2, "호텔 아쿠아펠리스", "숙소", "15:30"),
                place(2, 1, "호텔 아쿠아펠리스", "숙소", "10:30"),
                place(2, 2, "부산역", "이동", "16:00")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "부산역", "이동", "13:00"),
                place(1, 2, "호메르스호텔", "숙소", "15:30"),
                place(2, 1, "호메르스호텔", "숙소", "10:30"),
                place(2, 2, "부산역", "이동", "16:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, KTX_CONDITIONS);

        assertThat(aligned.planA().places().get(0).name()).isEqualTo("부산역");
        assertThat(aligned.planB().places().get(0).name()).isEqualTo("부산역");
        assertThat(aligned.planA().places()).hasSize(planA.places().size() + 1);
        assertThat(aligned.planA().places().get(1).name()).isEqualTo("광안리해수욕장");
        assertThat(aligned.planA().places().get(1).orderIndex()).isEqualTo(2);
    }

    @Test
    void appendsEndTransitWithoutRemovingLastDiningPlace() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "남이섬", "관광지", "13:00"),
                place(2, 1, "더휴펜션", "숙소", "10:30"),
                place(2, 2, "가평 잣두부마을", "식당", "14:30")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "자라섬", "관광지", "13:00"),
                place(2, 1, "클럽레스피아", "숙소", "10:30"),
                place(2, 2, "가평역", "이동", "16:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, KTX_CONDITIONS);

        assertThat(aligned.planA().places()).hasSize(planA.places().size() + 1);
        assertThat(aligned.planA().places().get(2).name()).isEqualTo("가평 잣두부마을");
        assertThat(aligned.planA().places().get(2).category()).isEqualTo("식당");
        assertThat(aligned.planA().places().get(3).name()).isEqualTo("가평역");
        assertThat(aligned.planA().places().get(3).category()).isEqualTo("이동");
    }

    @Test
    void removesDuplicateEndTransitOnSameDay() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(2, 1, "더힐펜션", "숙소", "10:30"),
                place(2, 2, "송원막국수", "식당", "18:00"),
                place(2, 3, "청평역 경춘선", "이동", "15:20")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(2, 1, "더힐펜션", "숙소", "10:30"),
                place(2, 2, "청평역 경춘선", "이동", "14:00"),
                place(2, 3, "송원막국수", "식당", "18:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, KTX_CONDITIONS);

        List<AiPlannerResponse.PlaceItem> day2Places = aligned.planB().places();
        assertThat(day2Places)
                .filteredOn(place -> "이동".equals(place.category()) && "청평역 경춘선".equals(place.name()))
                .hasSize(1);
        assertThat(day2Places.get(day2Places.size() - 1).name()).isEqualTo("청평역 경춘선");
        assertThat(day2Places.get(day2Places.size() - 1).visitTime()).isEqualTo("18:30");
        assertThat(day2Places)
                .extracting(AiPlannerResponse.PlaceItem::orderIndex)
                .containsExactly(1, 2, 3);
    }

    @Test
    void keepsCandidatesUntouchedWhenNeitherCandidateSuggestsBoundaryTransit() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "광안리해수욕장", "관광지", "13:00"),
                place(2, 1, "민락수변공원", "관광지", "14:00")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "해운대해수욕장", "관광지", "13:00"),
                place(2, 1, "동백섬", "관광지", "14:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, KTX_CONDITIONS);

        assertThat(aligned.planA()).isSameAs(planA);
        assertThat(aligned.planB()).isSameAs(planB);
    }

    @Test
    void doesNotPropagateTransitWhenConversationHasNoTransportIntent() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "광안리해수욕장", "관광지", "13:00")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "부산역", "이동", "13:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, """
                        {
                          "destination": "부산",
                          "transport": "미정",
                          "constraints": [],
                          "host_requests": []
                        }
                        """);

        assertThat(aligned.planA()).isSameAs(planA);
        assertThat(aligned.planB()).isSameAs(planB);
    }

    @Test
    void removesEndStationForSelfDriveTripFromBothCandidates() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "초당순두부", "식당", "11:30"),
                place(2, 1, "주문진수산시장", "관광지", "11:00"),
                place(2, 2, "강릉카페 체크이스트", "카페", "14:00"),
                place(2, 3, "강릉역", "이동", "16:00")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "동화가든", "식당", "11:30"),
                place(2, 1, "테라로사 임당점", "카페", "13:00"),
                place(2, 2, "강릉역", "이동", "16:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, SELF_DRIVE_CONDITIONS);

        assertThat(aligned.planA().places()).noneMatch(p -> "강릉역".equals(p.name()));
        assertThat(aligned.planB().places()).noneMatch(p -> "강릉역".equals(p.name()));
        assertThat(aligned.planA().places().get(aligned.planA().places().size() - 1).name())
                .isEqualTo("강릉카페 체크이스트");
        assertThat(aligned.planB().places().get(aligned.planB().places().size() - 1).name())
                .isEqualTo("테라로사 임당점");
    }

    @Test
    void keepsAirportTransitForRentalCarWhenFlightIsExplicit() {
        AiPlannerResponse.CandidatePlan planA = plan(
                "A",
                place(1, 1, "제주국제공항", "이동", "10:00"),
                place(1, 2, "성산일출봉", "관광지", "13:00"),
                place(2, 1, "제주국제공항", "이동", "18:00")
        );
        AiPlannerResponse.CandidatePlan planB = plan(
                "B",
                place(1, 1, "제주국제공항", "이동", "10:00"),
                place(1, 2, "협재해변", "관광지", "13:00"),
                place(2, 1, "제주국제공항", "이동", "18:00")
        );

        PlannerTransitConsistencyService.AlignedPlans aligned =
                service.align(planA, planB, RENTAL_WITH_AIRPORT_CONDITIONS);

        assertThat(aligned.planA().places()).anyMatch(p -> "제주국제공항".equals(p.name()));
        assertThat(aligned.planB().places()).anyMatch(p -> "제주국제공항".equals(p.name()));
    }

    private AiPlannerResponse.CandidatePlan plan(
            String label,
            AiPlannerResponse.PlaceItem... places
    ) {
        return new AiPlannerResponse.CandidatePlan(
                label,
                label + "안",
                label + "안 컨셉",
                0,
                BigDecimal.ZERO,
                List.of(places)
        );
    }

    private AiPlannerResponse.PlaceItem place(
            int day,
            int order,
            String name,
            String category,
            String visitTime
    ) {
        return new AiPlannerResponse.PlaceItem(
                day,
                order,
                name,
                category,
                name + " 설명",
                0,
                60,
                visitTime,
                "부산",
                "부산 " + name,
                null,
                null,
                null
        );
    }
}
