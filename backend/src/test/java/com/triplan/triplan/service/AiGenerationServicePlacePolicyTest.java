package com.triplan.triplan.service;

import com.triplan.triplan.dto.AiPlannerResponse;
import com.triplan.triplan.entity.Place;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiGenerationServicePlacePolicyTest {

    @Test
    void excludesUnverifiedGeneralPlace() {
        assertThat(AiGenerationService.shouldExcludeUnverifiedPlace("쇼핑", null)).isTrue();
        assertThat(AiGenerationService.shouldExcludeUnverifiedPlace("식당", fallback())).isTrue();
    }

    @Test
    void keepsKakaoVerifiedGeneralPlace() {
        assertThat(AiGenerationService.shouldExcludeUnverifiedPlace("카페", verified())).isFalse();
    }

    @Test
    void excludesUnverifiedAccommodationButKeepsStructuralTransitCard() {
        assertThat(AiGenerationService.shouldExcludeUnverifiedPlace("숙소", null)).isTrue();
        assertThat(AiGenerationService.shouldExcludeUnverifiedPlace("이동", null)).isFalse();
    }

    @Test
    void rejectsOvernightCandidateWhenVerifiedRestaurantWasRemoved() {
        assertThat(AiGenerationService.hasRequiredDiningCoverage(
                List.of(place(1, "관광지"), place(1, "숙소"), place(2, "숙소"), place(2, "식당")),
                2
        )).isFalse();
    }

    @Test
    void keepsOvernightCandidateWithVerifiedRestaurantForEveryDay() {
        assertThat(AiGenerationService.hasRequiredDiningCoverage(
                List.of(place(1, "식당"), place(1, "숙소"), place(2, "숙소"), place(2, "식당")),
                2
        )).isTrue();
    }

    @Test
    void searchesUnverifiedRestaurantByAreaAndCuisineInsteadOfInventedStoreName() {
        assertThat(AiGenerationService.replacementQuery(
                item("가평 잣향기닭갈비", "식당", "가평군", "가평의 닭갈비 식당입니다."),
                "가평"
        )).isEqualTo("가평군 닭갈비");
    }

    @Test
    void preservesAccommodationTypeWhenReplacingUnverifiedLodging() {
        assertThat(AiGenerationService.replacementQuery(
                item("가평 더힐링글램핑펜션", "숙소", "가평군", "글램핑장에서 체크인합니다."),
                "가평"
        )).isEqualTo("가평군 글램핑");
    }

    @Test
    void searchesUnverifiedTofuRestaurantByAreaAndCuisine() {
        assertThat(AiGenerationService.replacementQuery(
                item("가평 잣두부마을", "식당", "가평군", "잣두부를 맛봅니다."),
                "가평"
        )).isEqualTo("가평군 두부");
    }

    @Test
    void doesNotReplaceRestaurantWhenCuisineCannotBePreserved() {
        assertThat(AiGenerationService.replacementQuery(
                item("가평 가상식당", "식당", "가평군", "지역 식당입니다."),
                "가평"
        )).isNull();
    }

    @Test
    void allowsGenericRestaurantFallbackOnlyWhenExplicitlyEnabled() {
        assertThat(AiGenerationService.replacementQuery(
                item("가평 가상식당", "식당", "가평군", "지역 식당입니다."),
                "가평",
                true
        )).isEqualTo("가평군 식당");
    }

    @Test
    void usesSafeBusanCuisineFallbackWhenRestaurantNameCannotBePreserved() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "광안리",
                item("오복미역 광안리본점", "식당", "광안리", "부산 지역 식당입니다."),
                "부산",
                false
        )).containsExactly(
                "광안리 밀면",
                "광안리 돼지국밥",
                "부산 밀면",
                "부산 돼지국밥"
        );
    }

    @Test
    void triesGyeongjuVegetarianRestaurantsWhenTempleFoodReplacementFails() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "불국사",
                item("향적원", "식당", "불국사", "사찰음식 가능한 식당입니다."),
                "경주",
                false
        )).contains(
                "불국사 사찰음식",
                "경주 마조르",
                "경주 여기당",
                "경주 다유",
                "경주 쑥부쟁이"
        );
    }

    @Test
    void searchesPackagedHwangnamBreadAsLocalSnackInsteadOfGenericShopping() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "경주 황남동",
                item("경주 황남빵 본점", "쇼핑", "경주 황남동", "돌아가기 전에 황남빵을 포장합니다."),
                "경주",
                false
        )).containsExactly(
                "경주 황남동 황남빵",
                "황남빵"
        );
    }

    @Test
    void removesDurationFromCandidateThemeName() {
        assertThat(AiGenerationService.sanitizeCandidateName("문화재·사찰음식 중심 여유로운 경주 2박 3일"))
                .isEqualTo("문화재·사찰음식 중심 여유로운 경주");
        assertThat(AiGenerationService.sanitizeCandidateName("펜션 바베큐 1박2일 코스"))
                .isEqualTo("펜션 바베큐 코스");
    }

    @Test
    void rejectsBakeryAsGyeongjuVegetarianDiningReplacement() {
        assertThat(AiGenerationService.isAcceptableReplacement(
                item("초록마을채식뷔페", "식당", "황리단길", "채식 메뉴를 중심으로 식사합니다."),
                "경주",
                "황리단길",
                validated("월정제과", "음식점 > 간식 > 제과,베이커리")
        )).isFalse();
    }

    @Test
    void acceptsKnownGyeongjuVegetarianDiningReplacement() {
        assertThat(AiGenerationService.isAcceptableReplacement(
                item("발우공양 경주점", "식당", "경주", "사찰음식 가능한 식당입니다."),
                "경주",
                "경주",
                validated("향적원", "음식점 > 한식")
        )).isTrue();
    }

    @Test
    void searchesShoppingPreparationByMartWhenBarbecueOrBeverageIsRequested() {
        assertThat(AiGenerationService.replacementQuery(
                item("MT 장보기", "쇼핑", "가평군", "바베큐 재료와 무알코올 음료를 준비합니다."),
                "가평"
        )).isEqualTo("가평군 마트");
    }

    @Test
    void searchesUnverifiedSightByKeywordInsteadOfExcluding() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "경포대",
                item("경포 산책로", "관광지", "경포대", "산책로를 천천히 걷습니다."),
                "강릉",
                false
        )).containsExactly(
                "경포대 산책로",
                "강릉 산책로",
                "경포대 명소",
                "경포대 가볼만한곳"
        );
    }

    @Test
    void fallsBackToAreaSightsWhenSightKeywordCannotBeExtracted() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "안목",
                item("안목 가상명소", "관광지", "안목", "지역 명소입니다."),
                "강릉",
                false
        )).containsExactly(
                "안목 명소",
                "안목 가볼만한곳"
        );
    }

    @Test
    void searchesSurfingActivityByAreaAndDestination() {
        assertThat(AiGenerationService.replacementQueriesForArea(
                "경포대",
                item("강릉서프클럽", "액티비티", "경포대", "강릉 해변에서 서핑을 체험합니다."),
                "강릉",
                false
        )).containsExactly(
                "경포대 서핑",
                "강릉 서핑",
                "경포해변 서핑",
                "사천진 서핑"
        );
    }

    @Test
    void rejectsNonSurfActivityReplacementWhenSurfingWasRequested() {
        assertThat(AiGenerationService.isAcceptableReplacement(
                item("강릉서프클럽", "액티비티", "경포대", "강릉 해변에서 서핑을 체험합니다."),
                "강릉",
                "경포대",
                validated("경포호수광장", "여행 > 관광,명소")
        )).isFalse();
        assertThat(AiGenerationService.isAcceptableReplacement(
                item("강릉서프클럽", "액티비티", "경포대", "강릉 해변에서 서핑을 체험합니다."),
                "강릉",
                "경포대",
                validated("강릉서핑스쿨", "스포츠,레저 > 수상스포츠")
        )).isTrue();
    }

    @Test
    void retriesReplacementWithBroaderAreaBeforeDestination() {
        assertThat(AiGenerationService.candidateAreas("춘천 명동", "가평"))
                .containsExactly("춘천 명동", "춘천", "가평");
    }

    private AiPlannerResponse.PlaceItem item(String name, String category, String area, String description) {
        return new AiPlannerResponse.PlaceItem(
                1, 1, name, category, description,
                15_000, 60, "12:00", area, name,
                null, null, null
        );
    }

    private Place place(int dayNumber, String category) {
        return Place.builder()
                .dayNumber(dayNumber)
                .orderIndex(1)
                .name(category)
                .category(category)
                .build();
    }

    private PlaceValidationService.ValidatedPlace fallback() {
        return new PlaceValidationService.ValidatedPlace(
                "manual:area-fallback:test",
                "가상 장소",
                new BigDecimal("34.760000"),
                new BigDecimal("127.660000"),
                "지역 fallback",
                "지역 fallback",
                "지역 fallback",
                false
        );
    }

    private PlaceValidationService.ValidatedPlace validated(String name, String categoryName) {
        return new PlaceValidationService.ValidatedPlace(
                "1",
                name,
                new BigDecimal("35.840000"),
                new BigDecimal("129.220000"),
                "경북 경주시",
                "경북 경주시",
                categoryName,
                true
        );
    }

    private PlaceValidationService.ValidatedPlace verified() {
        return new PlaceValidationService.ValidatedPlace(
                "123",
                "실제 장소",
                new BigDecimal("34.760000"),
                new BigDecimal("127.660000"),
                "전남 여수시",
                "전남 여수시",
                "카페",
                true
        );
    }
}
