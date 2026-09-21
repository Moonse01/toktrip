package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import com.triplan.triplan.exception.AiGenerationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 플래너봇 retry가 모두 실패했을 때 수정 가능한 최소 품질 후보를 조립한다.
 *
 * <p>fallback도 일반 생성 결과와 동일한 validator를 통과한 경우에만 저장된다.
 * 장소 카탈로그는 프롬프트 정답을 고정하기 위한 것이 아니라, AI 장애 시 빈 화면을
 * 피하기 위한 제한적인 안전망이다.
 */
@Service
@RequiredArgsConstructor
public class PlannerFallbackService {

    private static final String RESTAURANT = "식당";
    private static final String CAFE = "카페";
    private static final String ATTRACTION = "관광지";
    private static final String ACTIVITY = "액티비티";
    private static final String ACCOMMODATION = "숙소";
    private static final String SHOPPING = "쇼핑";
    private static final String TRANSIT = "이동";

    private final ObjectMapper objectMapper;

    public AiPlannerResponse.CandidatePlan create(
            String expectedLabel,
            String conditionsJson,
            List<String> forbiddenPlaceNames
    ) {
        Conditions conditions = Conditions.from(conditionsJson, objectMapper);
        Catalog catalog = catalogFor(conditions.destination());
        if (catalog == null) {
            throw new AiGenerationException(
                    "fallback 장소 카탈로그가 없는 여행지입니다: " + conditions.destination()
            );
        }

        Set<String> forbidden = normalizeSet(forbiddenPlaceNames);
        Set<String> used = new HashSet<>();
        int offset = "B".equals(expectedLabel) ? 1 : 0;
        List<AiPlannerResponse.PlaceItem> places = new ArrayList<>();

        Spec hotel = pick(catalog.hotels(), used, Set.of(), offset, false);
        int lastDay = Math.max(1, conditions.durationNights() + 1);
        Spec conflictSpec = findConflictSpec(conditions, expectedLabel, catalog);

        if (conditions.durationNights() == 0) {
            add(places, 1, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset, false), "12:00");
            add(places, 1, pick(catalog.attractions(), used, forbidden, offset, false), "14:00");
            if (conditions.hasFoodFocusedIntent()) {
                add(places, 1, pick(catalog.cafes(), used, forbidden, offset, false), "15:30");
                add(places, 1, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset + 1, false), "17:00");
                add(places, 1, pick(catalog.attractions(), used, forbidden, offset + 1, false), "18:30");
            } else {
                add(places, 1, pick(catalog.cafes(), used, forbidden, offset, false), "16:00");
                add(places, 1, pick(catalog.attractions(), used, forbidden, offset + 1, false), "18:00");
            }
            addOptionalRequiredPlaces(places, conditions, catalog, used, forbidden, 1, "19:30");
            return plan(expectedLabel, conditions, places);
        }

        boolean conflictAdded = false;
        if (conditions.hasBarbecueIntent()) {
            if (conflictSpec != null && conditions.hasSunriseIntent(expectedLabel)) {
                add(places, 1, pick(List.of(conflictSpec), used, forbidden, 0, false), "06:00");
                conflictAdded = true;
            }
            add(places, 1, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset, false), "12:00");
            if (conflictSpec != null && !conflictAdded && !conditions.hasEveningIntent(expectedLabel)) {
                add(places, 1, pick(List.of(conflictSpec), used, forbidden, 0, false), "13:30");
                conflictAdded = true;
            } else {
                List<Spec> dayOneAttractions = conflictSpec != null && conditions.hasEveningIntent(expectedLabel)
                        ? without(catalog.attractions(), conflictSpec)
                        : catalog.attractions();
                if (dayOneAttractions.isEmpty()) {
                    dayOneAttractions = catalog.attractions();
                }
                add(places, 1, pick(dayOneAttractions, used, forbidden, offset, false), "13:30");
            }
            if (!catalog.shopping().isEmpty()) {
                add(places, 1, pick(catalog.shopping(), used, forbidden, offset, false), "15:00");
            }
            add(places, 1, hotel, "16:00");
        } else {
            if (conflictSpec != null && conditions.hasSunriseIntent(expectedLabel)) {
                add(places, 1, pick(List.of(conflictSpec), used, forbidden, 0, false), "06:00");
                conflictAdded = true;
            } else if (conflictSpec != null && !conditions.hasEveningIntent(expectedLabel)) {
                add(places, 1, pick(List.of(conflictSpec), used, forbidden, 0, false), "13:00");
                conflictAdded = true;
            } else {
                List<Spec> dayOneAttractions = conflictSpec != null && conditions.hasEveningIntent(expectedLabel)
                        ? without(catalog.attractions(), conflictSpec)
                        : catalog.attractions();
                if (dayOneAttractions.isEmpty()) {
                    dayOneAttractions = catalog.attractions();
                }
                add(places, 1, pick(dayOneAttractions, used, forbidden, offset, false), "13:00");
            }
            add(places, 1, hotel, "15:30");
            boolean groupShoppingAdded = conditions.hasGroupShoppingIntent() && !catalog.shopping().isEmpty();
            if (groupShoppingAdded) {
                add(places, 1, pick(catalog.shopping(), used, forbidden, offset, false), "16:20");
            }
            add(places, 1, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset, false), "18:00");
            if (!groupShoppingAdded) {
                if (conflictSpec != null && conditions.hasEveningIntent(expectedLabel) && !conflictAdded) {
                    add(places, 1, pick(List.of(conflictSpec), used, forbidden, 0, false), "20:00");
                    conflictAdded = true;
                } else {
                    add(places, 1, pick(catalog.cafes(), used, forbidden, offset, false), "20:00");
                }
            }
        }

        for (int day = 2; day < lastDay; day++) {
            add(places, day, pick(catalog.attractions(), used, forbidden, offset + day, false), "09:00");
            add(places, day, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset + day, false), "12:00");
            add(places, day, pick(catalog.attractions(), used, forbidden, offset + day + 1, false), "15:00");
            add(places, day, pick(catalog.cafes(), used, forbidden, offset + day, false), "17:00");
        }

        add(places, lastDay, hotel, "10:30");
        add(places, lastDay, pick(catalog.restaurants(conditions.hasVeganConstraint()), used, forbidden, offset + lastDay, false), "12:00");

        boolean requiredAdded = addOptionalRequiredPlaces(
                places,
                conditions,
                catalog,
                used,
                forbidden,
                lastDay,
                "19:00"
        );
        if (!requiredAdded) {
            add(places, lastDay, pick(catalog.attractions(), used, forbidden, offset + lastDay + 2, false), "14:00");
        }

        Spec endpoint = endpoint(catalog, conditions);
        if (endpoint != null) {
            add(places, lastDay, endpoint, requiredAdded ? "20:30" : "16:00");
        }

        return plan(expectedLabel, conditions, places);
    }

    private Spec findConflictSpec(Conditions conditions, String expectedLabel, Catalog catalog) {
        String wants = conditions.opinionWants(expectedLabel);
        String normalized = normalize(wants);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.contains("케이블카")) {
            return findSpecContaining(catalog, "케이블카");
        }
        if (normalized.contains("서핑") || normalized.contains("서프")) {
            return findSpecContaining(catalog, "서핑");
        }
        if (normalized.contains("향일암")) {
            return findSpecContaining(catalog, "향일암");
        }
        if (normalized.contains("광안리")) {
            return findSpecContaining(catalog, "광안리");
        }
        return null;
    }

    private Spec findSpecContaining(Catalog catalog, String keyword) {
        String normalizedKeyword = normalize(keyword);
        return catalog.allSpecs().stream()
                .filter(spec -> !ACCOMMODATION.equals(spec.category()) && !TRANSIT.equals(spec.category()))
                .filter(spec -> normalize(spec.name() + " " + spec.description() + " "
                        + spec.areaHint() + " " + spec.searchKeyword() + " "
                        + String.join(" ", spec.tags())).contains(normalizedKeyword))
                .findFirst()
                .orElse(null);
    }

    private List<Spec> without(List<Spec> specs, Spec excluded) {
        if (excluded == null) {
            return specs;
        }
        String excludedName = normalize(excluded.name());
        return specs.stream()
                .filter(spec -> !normalize(spec.name()).equals(excludedName))
                .toList();
    }

    private AiPlannerResponse.CandidatePlan plan(
            String expectedLabel,
            Conditions conditions,
            List<AiPlannerResponse.PlaceItem> places
    ) {
        int totalCost = places.stream()
                .mapToInt(place -> place.estimatedCost() == null ? 0 : place.estimatedCost())
                .sum();
        String preference = conditions.preference(expectedLabel);
        String name = conditions.destination() + " " + preference + " 후보 일정";
        String concept = conditions.concept(expectedLabel);
        return new AiPlannerResponse.CandidatePlan(
                expectedLabel,
                name,
                concept,
                totalCost,
                BigDecimal.ZERO,
                List.copyOf(places)
        );
    }

    private boolean addOptionalRequiredPlaces(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            Catalog catalog,
            Set<String> used,
            Set<String> forbidden,
            int day,
            String time
    ) {
        int addedCount = 0;
        for (String required : conditions.mustInclude()) {
            String keyword = normalizeRequired(required);
            if (keyword.isBlank()) {
                continue;
            }
            boolean alreadyAdded = places.stream().anyMatch(place ->
                    placeMatchesRequired(keyword, place)
            );
            if (alreadyAdded) {
                continue;
            }
            Spec spec = findRequired(catalog, keyword, used);
            if (spec == null) {
                continue;
            }
            add(places, day, pick(List.of(spec), used, forbidden, 0, true), plusMinutes(time, addedCount * 30));
            addedCount++;
        }
        return addedCount > 0;
    }

    private Spec findRequired(Catalog catalog, String keyword, Set<String> used) {
        return catalog.allSpecs().stream()
                .filter(spec -> !used.contains(normalize(spec.name())))
                .filter(spec -> specMatchesRequired(keyword, spec))
                .findFirst()
                .orElse(null);
    }

    private boolean placeMatchesRequired(String keyword, AiPlannerResponse.PlaceItem place) {
        String haystack = normalize(place.name() + " " + place.description() + " "
                + place.category() + " " + place.areaHint() + " " + place.searchKeyword());
        return haystack.contains(keyword) || matchesAreaFoodIntent(keyword, haystack);
    }

    private boolean specMatchesRequired(String keyword, Spec spec) {
        String haystack = normalize(spec.name() + " " + spec.description() + " "
                + spec.category() + " " + spec.areaHint() + " " + spec.searchKeyword()
                + " " + String.join(" ", spec.tags()));
        return haystack.contains(keyword) || matchesAreaFoodIntent(keyword, haystack);
    }

    private boolean matchesAreaFoodIntent(String keyword, String haystack) {
        if (keyword.contains("주문진항") && containsSeafoodIntent(keyword)) {
            return haystack.contains("주문진항")
                    && (containsSeafoodIntent(haystack) || haystack.contains(RESTAURANT));
        }
        return false;
    }

    private boolean containsSeafoodIntent(String value) {
        return value.contains("회")
                || value.contains("횟집")
                || value.contains("회센터")
                || value.contains("활어")
                || value.contains("생선회")
                || value.contains("해산물")
                || value.contains("수산");
    }

    private String plusMinutes(String time, int minutes) {
        String[] parts = time.split(":");
        int totalMinutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]) + minutes;
        return "%02d:%02d".formatted(totalMinutes / 60, totalMinutes % 60);
    }

    private Spec endpoint(Catalog catalog, Conditions conditions) {
        if (conditions.requiresAirport() && catalog.airport() != null) {
            return catalog.airport();
        }
        if (conditions.requiresTrainStation() && catalog.station() != null) {
            return catalog.station();
        }
        return null;
    }

    private Spec pick(
            List<Spec> specs,
            Set<String> used,
            Set<String> forbidden,
            int offset,
            boolean allowForbidden
    ) {
        if (specs == null || specs.isEmpty()) {
            throw new AiGenerationException("fallback 장소 후보가 부족합니다.");
        }
        for (int index = 0; index < specs.size(); index++) {
            Spec spec = specs.get(Math.floorMod(index + offset, specs.size()));
            String normalizedName = normalize(spec.name());
            if (used.contains(normalizedName)) {
                continue;
            }
            if (!allowForbidden && forbidden.contains(normalizedName)) {
                continue;
            }
            if (!ACCOMMODATION.equals(spec.category()) && !TRANSIT.equals(spec.category())) {
                used.add(normalizedName);
            }
            return spec;
        }
        throw new AiGenerationException("fallback에서 중복되지 않는 장소를 선택할 수 없습니다.");
    }

    private void add(List<AiPlannerResponse.PlaceItem> places, int day, Spec spec, String time) {
        long order = places.stream().filter(place -> Integer.valueOf(day).equals(place.dayNumber())).count() + 1;
        places.add(new AiPlannerResponse.PlaceItem(
                day,
                (int) order,
                spec.name(),
                spec.category(),
                spec.description(),
                spec.cost(),
                spec.durationMinutes(),
                time,
                spec.areaHint(),
                spec.searchKeyword(),
                null,
                null,
                null
        ));
    }

    private Catalog catalogFor(String destination) {
        String normalized = normalize(destination);
        if (normalized.contains("부산")) {
            return busan();
        }
        if (normalized.contains("제주")) {
            return jeju();
        }
        if (normalized.contains("강릉")) {
            return gangneung();
        }
        if (normalized.contains("경주")) {
            return gyeongju();
        }
        if (normalized.contains("서울")) {
            return seoul();
        }
        if (normalized.contains("가평")) {
            return gapyeong();
        }
        if (normalized.contains("여수")) {
            return yeosu();
        }
        if (normalized.contains("춘천")) {
            return chuncheon();
        }
        return null;
    }

    private Catalog busan() {
        return catalog(
                List.of(
                        p("호텔 아쿠아펠리스", ACCOMMODATION, 0, "광안리", "광안리 인근 숙소에서 체크인 또는 체크아웃합니다."),
                        p("호메르스호텔", ACCOMMODATION, 0, "광안리", "광안리 인근 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("송정3대국밥", RESTAURANT, 12000, "부산", "부산 대표 메뉴를 즐기는 식사입니다.", "돼지국밥"),
                        p("국제밀면 본점", RESTAURANT, 9000, "부산", "부담 없이 즐기는 지역 식사입니다.", "밀면"),
                        p("가야밀면", RESTAURANT, 9000, "부산", "부담 없이 즐기는 지역 식사입니다.", "밀면"),
                        p("합천일류돼지국밥", RESTAURANT, 12000, "부산", "부산 대표 메뉴를 즐기는 식사입니다.", "돼지국밥"),
                        p("초량밀면", RESTAURANT, 9000, "초량", "부담 없이 즐기는 지역 식사입니다.", "밀면"),
                        p("할매재첩국", RESTAURANT, 12000, "광안리", "부산의 지역 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("스타벅스 광안리점", CAFE, 7000, "광안리", "해변 인근에서 쉬어 가는 카페입니다."),
                        p("투썸플레이스 부산광안리점", CAFE, 7000, "광안리", "해변 인근에서 쉬어 가는 카페입니다."),
                        p("모모스커피 온천장 본점", CAFE, 7000, "온천장", "부산의 로컬 카페에서 쉬어 갑니다."),
                        p("블랙업커피 해운대", CAFE, 7000, "해운대", "해운대 인근에서 쉬어 갑니다.")
                ),
                List.of(
                        p("광안리해수욕장", ATTRACTION, 0, "광안리", "광안리 해변을 따라 가볍게 산책합니다."),
                        p("민락수변공원", ATTRACTION, 0, "광안리", "바다를 보며 여유롭게 산책합니다."),
                        p("해운대해수욕장", ATTRACTION, 0, "해운대", "해변 풍경을 감상하며 산책합니다."),
                        p("자갈치시장", ATTRACTION, 0, "남포동", "부산의 시장 분위기를 둘러봅니다."),
                        p("용두산공원", ATTRACTION, 0, "남포동", "부산 도심의 공원을 가볍게 둘러봅니다."),
                        p("흰여울문화마을", ATTRACTION, 0, "영도", "바다와 골목 풍경을 감상합니다."),
                        p("동백섬", ATTRACTION, 0, "해운대", "해안 산책로를 따라 걷습니다."),
                        p("송도해수욕장", ATTRACTION, 0, "송도", "해변 풍경을 감상합니다.")
                ),
                List.of(),
                p("부산역", TRANSIT, 0, "부산역", "귀가를 위해 부산역으로 이동합니다."),
                null
        );
    }

    private Catalog jeju() {
        return catalog(
                List.of(
                        p("롯데호텔 제주", ACCOMMODATION, 0, "중문", "중문 권역 숙소에서 체크인 또는 체크아웃합니다."),
                        p("제주신라호텔", ACCOMMODATION, 0, "중문", "중문 권역 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("흑돈가 중문점", RESTAURANT, 25000, "중문", "제주 지역 식사를 즐깁니다.", "흑돼지"),
                        p("숙성도 중문점", RESTAURANT, 25000, "중문", "제주 지역 식사를 즐깁니다.", "흑돼지"),
                        p("올래국수 본점", RESTAURANT, 12000, "제주시", "제주의 간단한 식사를 즐깁니다."),
                        p("자매국수 본점", RESTAURANT, 12000, "제주시", "제주의 간단한 식사를 즐깁니다."),
                        p("돈사돈 본관", RESTAURANT, 25000, "제주시", "제주 지역 식사를 즐깁니다.", "흑돼지"),
                        p("우진해장국", RESTAURANT, 10000, "제주시", "제주의 지역 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("더클리프", CAFE, 7000, "중문", "바다를 보며 쉬어 가는 카페입니다."),
                        p("앤트러사이트 제주 한림점", CAFE, 7000, "한림", "제주 분위기를 느끼며 쉬어 갑니다."),
                        p("우무", CAFE, 6000, "제주시", "가볍게 쉬어 가는 디저트 공간입니다."),
                        p("랜디스도넛 제주애월점", CAFE, 7000, "애월", "바다 인근에서 쉬어 가는 디저트 공간입니다.")
                ),
                List.of(
                        p("주상절리대", ATTRACTION, 2000, "중문", "제주의 해안 지형을 감상합니다."),
                        p("새별오름", ATTRACTION, 0, "애월", "제주의 자연을 가볍게 산책합니다.", "오름"),
                        p("도두봉", ATTRACTION, 0, "제주시", "바다와 제주시 풍경을 감상합니다."),
                        p("용두암", ATTRACTION, 0, "제주시", "공항 인근 대표 명소를 둘러봅니다."),
                        p("이호테우해변", ATTRACTION, 0, "제주시", "해변을 따라 여유롭게 산책합니다."),
                        p("제주목관아", ATTRACTION, 0, "제주시", "제주 원도심의 문화 명소를 둘러봅니다."),
                        p("한라수목원", ATTRACTION, 0, "제주시", "수목원을 따라 가볍게 산책합니다."),
                        p("사려니숲길", ATTRACTION, 0, "제주시", "숲길을 따라 여유롭게 산책합니다.")
                ),
                List.of(),
                null,
                p("제주국제공항", TRANSIT, 0, "제주시", "귀가를 위해 제주국제공항으로 이동합니다.")
        );
    }

    private Catalog gangneung() {
        return catalog(
                List.of(
                        p("스카이베이호텔 경포", ACCOMMODATION, 0, "경포", "경포 권역 숙소에서 체크인 또는 체크아웃합니다."),
                        p("세인트존스호텔", ACCOMMODATION, 0, "강문", "해변 인근 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("초당할머니순두부", RESTAURANT, 13000, "초당동", "강릉의 대표 음식인 초당순두부를 즐깁니다.", "초당순두부"),
                        p("동화가든 본점", RESTAURANT, 15000, "초당동", "강릉의 대표 음식인 짬뽕순두부를 즐깁니다.", "짬뽕순두부"),
                        p("차현희순두부청국장", RESTAURANT, 13000, "초당동", "강릉의 대표 음식인 초당순두부를 즐깁니다.", "초당순두부"),
                        p("주문진항 해물밥상", RESTAURANT, 25000, "주문진항", "주문진항 근처에서 회와 해산물 식사를 즐깁니다.", "주문진항", "회", "횟집", "해산물", "수산"),
                        p("엄지네포장마차 본점", RESTAURANT, 15000, "강릉", "강릉의 지역 식사를 즐깁니다."),
                        p("현대장칼국수", RESTAURANT, 10000, "강릉", "강릉의 지역 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("보사노바 커피로스터스 강릉점", CAFE, 7000, "안목", "해변 인근에서 쉬어 가는 카페입니다."),
                        p("테라로사 커피공장 강릉본점", CAFE, 7000, "강릉", "여유롭게 쉬어 가는 카페입니다."),
                        p("툇마루", CAFE, 7000, "강릉", "여유롭게 쉬어 가는 카페입니다.")
                ),
                List.of(
                        p("경포해변 서핑 체험", ACTIVITY, 40000, "경포", "강릉 해변에서 서핑을 체험합니다.", "서핑", "서프"),
                        p("경포해변", ATTRACTION, 0, "경포", "경포 해변을 따라 산책합니다."),
                        p("강문해변", ATTRACTION, 0, "강문", "해변 풍경을 감상하며 산책합니다."),
                        p("안목해변", ATTRACTION, 0, "안목", "바다를 보며 가볍게 산책합니다."),
                        p("오죽헌", ATTRACTION, 3000, "강릉", "강릉의 문화 명소를 둘러봅니다.")
                ),
                List.of(),
                p("강릉역", TRANSIT, 0, "강릉역", "귀가를 위해 강릉역으로 이동합니다."),
                null
        );
    }

    private Catalog gyeongju() {
        return catalog(
                List.of(
                        p("힐튼 경주", ACCOMMODATION, 0, "보문관광단지", "보문관광단지 숙소에서 체크인 또는 체크아웃합니다."),
                        p("라한셀렉트 경주", ACCOMMODATION, 0, "보문관광단지", "보문관광단지 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("향적원", RESTAURANT, 20000, "불국사", "정갈한 지역 식사를 즐깁니다.", "사찰음식", "채식"),
                        p("마조르", RESTAURANT, 15000, "황리단길", "여유롭게 즐기는 지역 식사입니다.", "비건", "채식"),
                        p("진수성찬", RESTAURANT, 18000, "교촌마을", "경주의 한식 식사를 즐깁니다.")
                ),
                List.of(
                        p("향적원", RESTAURANT, 20000, "불국사", "채식 메뉴를 중심으로 식사합니다.", "사찰음식", "채식"),
                        p("마조르", RESTAURANT, 15000, "황리단길", "채식 메뉴를 중심으로 식사합니다.", "비건", "채식"),
                        p("여기당", RESTAURANT, 15000, "경주", "채식 메뉴를 중심으로 식사합니다.", "비건", "채식"),
                        p("다유", RESTAURANT, 15000, "천북면", "채식 메뉴를 중심으로 식사합니다.", "비건", "채식"),
                        p("옐라", RESTAURANT, 15000, "황오동", "채식 메뉴를 중심으로 식사합니다.", "비건", "채식"),
                        p("쑥부쟁이", RESTAURANT, 15000, "경주", "채식 메뉴를 중심으로 식사합니다.", "비건", "채식")
                ),
                List.of(
                        p("카페 아덴", CAFE, 7000, "보문관광단지", "보문호 인근에서 쉬어 가는 카페입니다."),
                        p("아덴 보문호수점", CAFE, 7000, "보문관광단지", "보문호 인근에서 쉬어 가는 카페입니다."),
                        p("1894사랑채", CAFE, 7000, "황리단길", "한옥 분위기에서 쉬어 가는 카페입니다."),
                        p("노워즈", CAFE, 7000, "황리단길", "황리단길 인근에서 쉬어 가는 카페입니다."),
                        p("카페 솔", CAFE, 7000, "황남동", "한옥 분위기에서 쉬어 가는 카페입니다."),
                        p("테라로사 경주점", CAFE, 7000, "경주", "여유롭게 쉬어 가는 카페입니다."),
                        p("오스카스카페", CAFE, 7000, "황리단길", "사진을 남기기 좋은 카페입니다.")
                ),
                List.of(
                        p("불국사", ATTRACTION, 0, "불국사", "경주의 대표 문화유산을 둘러봅니다."),
                        p("석굴암", ATTRACTION, 0, "불국사", "경주의 대표 문화유산을 둘러봅니다."),
                        p("대릉원", ATTRACTION, 3000, "경주 도심", "신라 시대 유적을 둘러봅니다."),
                        p("첨성대", ATTRACTION, 0, "경주 도심", "경주의 대표 문화재를 둘러봅니다."),
                        p("국립경주박물관", ATTRACTION, 0, "경주 도심", "신라 문화 자료를 관람합니다."),
                        p("동궁과월지", ATTRACTION, 3000, "경주 도심", "저녁에 야경을 감상합니다.", "동궁과월지", "야경"),
                        p("월정교", ATTRACTION, 0, "경주 도심", "경주의 다리와 주변 풍경을 감상합니다."),
                        p("교촌마을", ATTRACTION, 0, "경주 도심", "한옥 골목을 따라 여유롭게 산책합니다."),
                        p("계림", ATTRACTION, 0, "경주 도심", "신라 유적지 주변을 조용히 산책합니다."),
                        p("월성", ATTRACTION, 0, "경주 도심", "신라 왕궁터를 따라 걸으며 풍경을 감상합니다."),
                        p("분황사", ATTRACTION, 0, "경주 도심", "고즈넉한 사찰 유적을 둘러봅니다."),
                        p("보문호반길", ATTRACTION, 0, "보문관광단지", "보문호 주변을 여유롭게 산책합니다."),
                        p("경주엑스포대공원", ATTRACTION, 0, "보문관광단지", "넓은 공원과 전시 공간을 둘러봅니다."),
                        p("경주예술의전당", ATTRACTION, 0, "경주", "실내 전시와 문화 공간을 둘러봅니다.")
                ),
                List.of(
                        p("황남빵", SHOPPING, 10000, "경주 도심", "돌아가기 전에 간단히 포장합니다.", "황남빵")
                ),
                p("경주역", TRANSIT, 0, "경주역", "귀가를 위해 경주역으로 이동합니다."),
                null
        );
    }

    private Catalog seoul() {
        return catalog(
                List.of(
                        p("나인트리 프리미어 호텔 인사동", ACCOMMODATION, 0, "종로", "도심 숙소에서 체크인 또는 체크아웃합니다."),
                        p("호텔28 명동", ACCOMMODATION, 0, "명동", "도심 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("광화문미진", RESTAURANT, 12000, "종로", "서울 도심에서 식사를 즐깁니다."),
                        p("소문난성수감자탕", RESTAURANT, 12000, "성수", "서울 도심에서 식사를 즐깁니다."),
                        p("다운타우너 안국", RESTAURANT, 15000, "안국", "서울 도심에서 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("블루보틀 성수 카페", CAFE, 7000, "성수", "도심에서 쉬어 가는 카페입니다."),
                        p("어니언 안국", CAFE, 7000, "안국", "도심에서 쉬어 가는 카페입니다.")
                ),
                List.of(
                        p("서울숲", ATTRACTION, 0, "성수", "도심 속 산책을 즐깁니다."),
                        p("경복궁", ATTRACTION, 3000, "종로", "서울의 대표 문화 명소를 둘러봅니다."),
                        p("북촌한옥마을", ATTRACTION, 0, "종로", "한옥 골목을 가볍게 둘러봅니다."),
                        p("청계천", ATTRACTION, 0, "종로", "도심 산책로를 따라 걷습니다.")
                ),
                List.of(),
                p("서울역", TRANSIT, 0, "서울역", "귀가를 위해 서울역으로 이동합니다."),
                null
        );
    }

    private Catalog gapyeong() {
        return catalog(
                List.of(
                        p("켄싱턴리조트 가평", ACCOMMODATION, 0, "가평", "가평 펜션/글램핑 숙소에서 체크인 또는 체크아웃하고 저녁 바베큐와 휴식 준비를 합니다."),
                        p("캠프통아일랜드", ACCOMMODATION, 0, "가평", "가평 펜션/글램핑 숙소에서 체크인 또는 체크아웃하고 저녁 바베큐와 휴식 준비를 합니다.")
                ),
                List.of(
                        p("송원막국수", RESTAURANT, 12000, "가평", "가평에서 가볍게 식사합니다."),
                        p("동기간", RESTAURANT, 15000, "가평", "가평의 지역 식사를 즐깁니다."),
                        p("남이섬꼬꼬춘천닭갈비", RESTAURANT, 15000, "가평", "가평의 지역 식사를 즐깁니다."),
                        p("조무락닭갈비", RESTAURANT, 15000, "가평", "가평의 지역 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("카페플랫", CAFE, 7000, "자라섬", "자라섬 인근에서 쉬어 갑니다."),
                        p("코미호미", CAFE, 7000, "가평", "여유롭게 쉬어 가는 카페입니다.")
                ),
                List.of(
                        p("남이섬", ATTRACTION, 16000, "가평", "섬 안의 산책길을 둘러봅니다."),
                        p("자라섬", ATTRACTION, 0, "가평", "강변 풍경을 따라 산책합니다."),
                        p("쁘띠프랑스", ATTRACTION, 12000, "가평", "가평의 대표 관광지를 둘러봅니다."),
                        p("아침고요수목원", ATTRACTION, 11000, "가평", "정원을 따라 여유롭게 산책합니다.")
                ),
                List.of(
                        p("농협하나로마트 가평군농협자라섬점", SHOPPING, 20000, "가평", "바베큐 재료와 무알코올 음료를 준비합니다.", "마트", "음료", "바베큐"),
                        p("CU 가평자라섬점", SHOPPING, 12000, "가평", "간단한 음료와 간식을 준비합니다.", "편의점", "음료")
                ),
                p("가평역", TRANSIT, 0, "가평역", "귀가를 위해 가평역으로 이동합니다."),
                null
        );
    }

    private Catalog yeosu() {
        return catalog(
                List.of(
                        p("소노캄 여수", ACCOMMODATION, 0, "여수", "여수 숙소에서 체크인 또는 체크아웃합니다."),
                        p("여수 베네치아 호텔앤리조트", ACCOMMODATION, 0, "여수", "여수 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("수림회포차본점", RESTAURANT, 30000, "여수", "자연산 활어회와 선어를 즐길 수 있는 식당입니다.", "회", "해산물"),
                        p("꽃돌게장1번가", RESTAURANT, 25000, "여수", "여수의 지역 식사를 즐깁니다."),
                        p("로타리식당", RESTAURANT, 15000, "여수", "여수의 지역 식사를 즐깁니다."),
                        p("순이네밥상", RESTAURANT, 15000, "여수", "여수의 지역 식사를 즐깁니다.")
                ),
                List.of(),
                List.of(
                        p("모이핀", CAFE, 7000, "여수", "바다를 보며 쉬어 가는 카페입니다."),
                        p("NCNP", CAFE, 7000, "여수", "여유롭게 쉬어 가는 카페입니다.")
                ),
                List.of(
                        p("향일암", ATTRACTION, 2000, "여수", "남해 바다를 바라보며 일출을 감상할 수 있는 명소입니다.", "일출"),
                        p("여수해상케이블카", ATTRACTION, 17000, "여수", "여수의 바다 풍경을 감상합니다."),
                        p("오동도", ATTRACTION, 0, "여수", "바다와 숲길을 따라 산책합니다."),
                        p("이순신광장", ATTRACTION, 0, "여수", "여수 도심의 대표 장소를 둘러봅니다."),
                        p("아쿠아플라넷 여수", ATTRACTION, 30000, "여수", "실내 전시 공간을 관람합니다.")
                ),
                List.of(),
                p("여수엑스포역", TRANSIT, 0, "여수엑스포역", "귀가를 위해 여수엑스포역으로 이동합니다."),
                null
        );
    }

    private Catalog chuncheon() {
        return catalog(
                List.of(
                        p("더잭슨나인스호텔", ACCOMMODATION, 0, "춘천", "춘천 도심권 숙소에서 체크인 또는 체크아웃합니다."),
                        p("KT&G 상상마당 춘천 스테이", ACCOMMODATION, 0, "춘천", "의암호 인근 숙소에서 체크인 또는 체크아웃합니다.")
                ),
                List.of(
                        p("명동우미닭갈비", RESTAURANT, 15000, "춘천 명동", "춘천 대표 음식인 닭갈비를 즐깁니다.", "닭갈비"),
                        p("통나무집닭갈비", RESTAURANT, 15000, "신북읍", "춘천 대표 음식인 닭갈비를 즐깁니다.", "닭갈비"),
                        p("우성닭갈비 본점", RESTAURANT, 15000, "춘천", "춘천 대표 음식인 닭갈비를 즐깁니다.", "닭갈비"),
                        p("춘천막국수체험박물관 식당", RESTAURANT, 12000, "신북읍", "막국수 중심의 가벼운 지역 식사를 즐깁니다.", "막국수")
                ),
                List.of(),
                List.of(
                        p("감자밭", CAFE, 7000, "춘천", "춘천의 감자빵과 함께 쉬어 가는 카페입니다.", "감자빵"),
                        p("산토리니", CAFE, 7000, "구봉산", "춘천 전망을 보며 쉬어 가는 카페입니다."),
                        p("어스17", CAFE, 7000, "춘천", "의암호 인근에서 쉬어 가는 카페입니다.")
                ),
                List.of(
                        p("소양강스카이워크", ATTRACTION, 2000, "춘천", "소양강 풍경을 보며 가볍게 걷습니다."),
                        p("공지천유원지", ATTRACTION, 0, "춘천", "물가 산책로를 따라 여유롭게 걷습니다."),
                        p("KT&G 상상마당 춘천", ATTRACTION, 0, "춘천", "전시와 호수 분위기를 함께 둘러봅니다."),
                        p("김유정문학촌", ATTRACTION, 3000, "신동면", "춘천의 문학 명소를 둘러봅니다."),
                        p("강촌레일파크 김유정레일바이크", ATTRACTION, 35000, "신동면", "가볍게 즐길 수 있는 춘천 대표 체험 코스입니다.")
                ),
                List.of(),
                p("춘천역", TRANSIT, 0, "춘천역", "귀가를 위해 춘천역으로 이동합니다."),
                null
        );
    }

    private Catalog catalog(
            List<Spec> hotels,
            List<Spec> restaurants,
            List<Spec> veganRestaurants,
            List<Spec> cafes,
            List<Spec> attractions,
            List<Spec> shopping,
            Spec station,
            Spec airport
    ) {
        return new Catalog(hotels, restaurants, veganRestaurants, cafes, attractions, shopping, station, airport);
    }

    private Spec p(String name, String category, int cost, String area, String description, String... tags) {
        return new Spec(
                name,
                category,
                cost,
                60,
                area,
                area + " " + name,
                description,
                List.of(tags)
        );
    }

    private Set<String> normalizeSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        values.stream().map(this::normalize).filter(value -> !value.isBlank()).forEach(result::add);
        return result;
    }

    private String normalizeRequired(String value) {
        return normalize(value)
                .replace("야경", "")
                .replace("일몰", "")
                .replace("일출", "")
                .replace("마지막날", "")
                .replace("마지막", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record Spec(
            String name,
            String category,
            int cost,
            int durationMinutes,
            String areaHint,
            String searchKeyword,
            String description,
            List<String> tags
    ) {}

    private record Catalog(
            List<Spec> hotels,
            List<Spec> restaurants,
            List<Spec> veganRestaurants,
            List<Spec> cafes,
            List<Spec> attractions,
            List<Spec> shopping,
            Spec station,
            Spec airport
    ) {
        private List<Spec> restaurants(boolean vegan) {
            return vegan && !veganRestaurants.isEmpty() ? veganRestaurants : restaurants;
        }

        private List<Spec> allSpecs() {
            Map<String, Spec> unique = new LinkedHashMap<>();
            List.of(hotels, restaurants, veganRestaurants, cafes, attractions, shopping)
                    .forEach(items -> items.forEach(spec -> unique.putIfAbsent(spec.name(), spec)));
            if (station != null) {
                unique.putIfAbsent(station.name(), station);
            }
            if (airport != null) {
                unique.putIfAbsent(airport.name(), airport);
            }
            return List.copyOf(unique.values());
        }
    }

    private record Conditions(
            String destination,
            String transport,
            int durationNights,
            List<String> constraints,
            List<String> hostRequests,
            List<String> mustInclude,
            List<String> mustExclude,
            JsonNode conflicts
    ) {
        private static Conditions from(String conditionsJson, ObjectMapper objectMapper) {
            try {
                JsonNode root = objectMapper.readTree(conditionsJson);
                return new Conditions(
                        text(root.path("destination")),
                        text(root.path("transport")),
                        durationNights(root),
                        textArray(root.path("constraints")),
                        textArray(root.path("host_requests")),
                        textArray(root.path("must_include")),
                        textArray(root.path("must_exclude")),
                        root.path("conflicts")
                );
            } catch (Exception e) {
                throw new AiGenerationException("fallback 여행 조건 파싱 실패", e);
            }
        }

        private String preference(String expectedLabel) {
            JsonNode opinion = opinion(expectedLabel);
            String wants = text(opinion.path("wants"));
            if (!wants.isBlank()) {
                return shortText(wants);
            }
            return "B".equals(expectedLabel) ? "여유로운 대표 코스" : "가성비 로컬 코스";
        }

        private String concept(String expectedLabel) {
            JsonNode opinion = opinion(expectedLabel);
            String who = text(opinion.path("who"));
            String wants = text(opinion.path("wants"));
            if (!who.isBlank() && !wants.isBlank()) {
                return expectedLabel + "안은 " + who + "의 '" + wants
                        + "' 의견을 중심으로 구성했습니다. 검증 가능한 장소를 기준으로 수정 가능한 후보 일정을 준비했습니다.";
            }
            return expectedLabel + "안은 대화에서 확인된 여행 조건을 중심으로 구성했습니다. "
                    + "검증 가능한 장소를 기준으로 수정 가능한 후보 일정을 준비했습니다.";
        }

        private String opinionWants(String expectedLabel) {
            return text(opinion(expectedLabel).path("wants"));
        }

        private boolean hasSunriseIntent(String expectedLabel) {
            return normalizeText(opinionWants(expectedLabel)).contains("일출");
        }

        private boolean hasEveningIntent(String expectedLabel) {
            String wants = normalizeText(opinionWants(expectedLabel));
            return wants.contains("야경") || wants.contains("일몰");
        }

        private JsonNode opinion(String expectedLabel) {
            if (!conflicts.isArray() || conflicts.isEmpty()) {
                return missing();
            }
            JsonNode opinions = conflicts.path(0).path("opinions");
            int index = "B".equals(expectedLabel) ? 1 : 0;
            return opinions.isArray() && opinions.size() > index ? opinions.path(index) : missing();
        }

        private boolean hasVeganConstraint() {
            String joined = normalizeText(String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustExclude));
            return joined.contains("비건")
                    || joined.contains("채식")
                    || joined.contains("사찰음식")
                    || (joined.contains("고기") && joined.contains("유제품") && joined.contains("계란"));
        }

        private boolean hasFoodFocusedIntent() {
            String joined = normalizeText(String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustInclude));
            return joined.contains("먹")
                    || joined.contains("맛집")
                    || joined.contains("미식")
                    || joined.contains("식도락")
                    || joined.contains("음식")
                    || joined.contains("식사");
        }

        private boolean hasGroupShoppingIntent() {
            String joined = conditionText();
            return hasBarbecueIntent()
                    || joined.contains("장보기")
                    || joined.contains("마트")
                    || (joined.contains("음료") && (joined.contains("술못마심")
                    || joined.contains("술못마시") || joined.contains("술약함") || joined.contains("무알코올")));
        }

        private boolean hasBarbecueIntent() {
            String joined = conditionText();
            return joined.contains("바베큐")
                    || joined.contains("바비큐")
                    || joined.contains("bbq");
        }

        private boolean requiresTrainStation() {
            String joined = normalizeText(transport + " "
                    + String.join(" ", constraints) + " " + String.join(" ", hostRequests));
            return joined.contains("ktx") || joined.contains("기차") || joined.contains("역");
        }

        private boolean requiresAirport() {
            String joined = normalizeText(transport + " "
                    + String.join(" ", constraints) + " " + String.join(" ", hostRequests));
            return joined.contains("공항") || joined.contains("항공");
        }

        private String conditionText() {
            return normalizeText(String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustInclude) + " "
                    + String.join(" ", mustExclude) + " "
                    + conflicts.toString());
        }

        private static JsonNode missing() {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }

        private static int durationNights(JsonNode root) {
            JsonNode direct = root.path("duration_nights");
            if (direct.isNumber()) {
                return direct.asInt();
            }
            return root.path("dates").path("duration_nights").asInt(0);
        }

        private static List<String> textArray(JsonNode node) {
            List<String> values = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> {
                    String value = text(item);
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                });
            }
            return values;
        }

        private static String shortText(String value) {
            String trimmed = value.trim();
            return trimmed.length() <= 18 ? trimmed : trimmed.substring(0, 18);
        }

        private static String normalizeText(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }

        private static String text(JsonNode node) {
            return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
        }
    }
}
