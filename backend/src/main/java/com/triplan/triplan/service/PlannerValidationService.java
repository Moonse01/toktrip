package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlannerValidationService {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "식당", "카페", "관광지", "숙소", "액티비티", "쇼핑", "이동"
    );
    private static final DateTimeFormatter VISIT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> BANNED_UNCERTAIN_NAMES = List.of(
            "카페 모나코 강릉점",
            "카페 바다향",
            "카페 델문도 강릉점",
            "짬뽕순두부 강릉점",
            "경포대 전망대",
            "광안대교 전망대",
            "광안리 파크뷰 호텔"
    );
    private static final Set<String> INCLUDE_FILLER_TOKENS = Set.of(
            "쪽", "근처", "부근", "주변", "인근", "앞", "근방",
            "쪽에", "근처에", "부근에", "주변에", "인근에",
            "쪽에서", "근처에서", "부근에서", "주변에서", "인근에서",
            "먹기", "먹는", "먹고", "먹자", "먹으러", "먹고싶어", "먹고싶다", "식사",
            "가기", "가고", "가자", "가려고", "가고싶어", "가고싶다",
            "보고", "보러", "보고싶어", "보고싶다", "하고", "하기",
            "즐기고", "즐기기", "들르고", "들러", "체험하고", "체험",
            "쉬기", "쉬고", "쉬는", "쉬어", "쉬자",
            "꼭", "넣어줘", "넣기"
    );
    private static final List<String> INCLUDE_TOKEN_SUFFIXES = List.of(
            "에서", "으로", "로", "이면", "라면", "은", "는", "이", "가", "을", "를"
    );
    private static final Set<String> INCLUDE_INTENT_KEYWORDS = Set.of(
            "회", "횟집", "회센터", "활어", "활어회", "생선회", "해산물", "수산",
            "닭갈비", "막국수", "흑돼지", "밀면", "국밥", "비빔밥", "한정식",
            "카페", "커피", "디저트", "빵", "일몰", "야경",
            "바베큐", "바비큐", "bbq", "저녁", "장보기", "마트", "음료"
    );

    private final ObjectMapper objectMapper;

    public ValidationResult validate(
            AiPlannerResponse.CandidatePlan candidatePlan,
            String expectedLabel,
            String conditionsJson
    ) {
        return validate(candidatePlan, expectedLabel, conditionsJson, List.of());
    }

    public ValidationResult validate(
            AiPlannerResponse.CandidatePlan candidatePlan,
            String expectedLabel,
            String conditionsJson,
            List<String> forbiddenPlaceNames
    ) {
        List<String> reasons = new ArrayList<>();
        Conditions conditions = Conditions.from(conditionsJson, objectMapper);

        if (candidatePlan == null) {
            reasons.add(expectedLabel + " 응답이 비어 있습니다.");
            return ValidationResult.failure(reasons);
        }
        if (candidatePlan.label() != null && !expectedLabel.equals(candidatePlan.label())) {
            reasons.add("label은 반드시 \"" + expectedLabel + "\"이어야 합니다.");
        }
        if (candidatePlan.places() == null || candidatePlan.places().isEmpty()) {
            reasons.add("places 배열이 비어 있습니다.");
            return ValidationResult.failure(reasons);
        }

        validateDays(candidatePlan.places(), conditions.durationNights(), reasons);
        validatePlaceCount(candidatePlan.places().size(), conditions.durationNights(), reasons);
        validateDailyDensity(candidatePlan.places(), conditions.durationNights(), reasons);
        validateCategories(candidatePlan.places(), reasons);
        validateAccommodation(candidatePlan.places(), conditions.durationNights(), reasons);
        validateOrderAndTime(candidatePlan.places(), reasons);
        validateDuplicatePlaces(candidatePlan.places(), reasons);
        validateMealRules(candidatePlan.places(), conditions, reasons);
        validateGroupMealPreparation(candidatePlan.places(), conditions, reasons);
        validateFoodFocusedDayTrip(candidatePlan.places(), conditions, reasons);
        validateRegionalFoodFit(candidatePlan.places(), conditions, reasons);
        validateMustInclude(candidatePlan.places(), conditions.mustInclude(), reasons);
        validateConflictOpinionIntent(candidatePlan.places(), expectedLabel, conditions, reasons);
        validateMustExclude(candidatePlan.places(), conditions.mustExclude(), reasons);
        validateForbiddenPlaces(candidatePlan.places(), forbiddenPlaceNames, conditions.mustInclude(), reasons);
        validateUnrequestedTransit(candidatePlan.places(), conditions, reasons);
        validateTransportEnding(candidatePlan.places(), conditions, reasons);
        validateBudget(candidatePlan.places(), conditions.perPersonLimit(), reasons);
        validateNames(candidatePlan.places(), reasons);

        return reasons.isEmpty() ? ValidationResult.success() : ValidationResult.failure(reasons);
    }

    private void validateDays(
            List<AiPlannerResponse.PlaceItem> places,
            int durationNights,
            List<String> reasons
    ) {
        if (durationNights < 0) {
            return;
        }
        int expectedLastDay = durationNights + 1;
        Set<Integer> actualDays = places.stream()
                .map(AiPlannerResponse.PlaceItem::dayNumber)
                .filter(day -> day != null)
                .collect(Collectors.toCollection(HashSet::new));

        for (int day = 1; day <= expectedLastDay; day++) {
            if (!actualDays.contains(day)) {
                reasons.add(day + "일차 일정이 없습니다.");
            }
        }
        for (Integer day : actualDays) {
            if (day < 1 || day > expectedLastDay) {
                reasons.add("day_number " + day + "는 여행 기간 범위를 벗어납니다.");
            }
        }
    }

    private void validatePlaceCount(int placeCount, int durationNights, List<String> reasons) {
        if (durationNights < 0) {
            return;
        }
        if (durationNights == 0 && (placeCount < 4 || placeCount > 6)) {
            reasons.add("당일치기 places 개수는 4~6개여야 합니다. 현재: " + placeCount);
        } else if (durationNights == 1 && (placeCount < 7 || placeCount > 9)) {
            reasons.add("1박2일 places 개수는 7~9개여야 합니다. 현재: " + placeCount);
        } else if (durationNights == 2 && (placeCount < 10 || placeCount > 14)) {
            reasons.add("2박3일 places 개수는 10~14개여야 합니다. 현재: " + placeCount);
        } else if (durationNights == 3 && (placeCount < 13 || placeCount > 19)) {
            reasons.add("3박4일 places 개수는 13~19개여야 합니다. 현재: " + placeCount);
        }
    }

    private void validateDailyDensity(
            List<AiPlannerResponse.PlaceItem> places,
            int durationNights,
            List<String> reasons
    ) {
        if (durationNights < 1) {
            return;
        }

        int lastDay = durationNights + 1;
        for (int day = 1; day <= lastDay; day++) {
            int currentDay = day;
            int maxPlaces = day == 1 ? 4 : 5;
            long placeCount = places.stream()
                    .filter(place -> Integer.valueOf(currentDay).equals(place.dayNumber()))
                    .count();
            if (placeCount > maxPlaces) {
                reasons.add(day + "일차 places 개수는 최대 " + maxPlaces + "개여야 합니다. 현재: " + placeCount);
            }
        }

        for (int day = 2; day < lastDay; day++) {
            int currentDay = day;
            boolean hasAccommodation = places.stream()
                    .anyMatch(place -> Integer.valueOf(currentDay).equals(place.dayNumber())
                            && "숙소".equals(place.category()));
            if (hasAccommodation) {
                reasons.add(day + "일차에는 숙소 category를 넣지 마세요. 숙소 복귀는 일정 카드에서 생략합니다.");
            }
        }
    }

    private void validateCategories(List<AiPlannerResponse.PlaceItem> places, List<String> reasons) {
        for (AiPlannerResponse.PlaceItem place : places) {
            if (place.category() == null || !ALLOWED_CATEGORIES.contains(place.category())) {
                reasons.add("허용되지 않은 category가 있습니다: " + displayName(place));
            }
        }
    }

    private void validateAccommodation(
            List<AiPlannerResponse.PlaceItem> places,
            int durationNights,
            List<String> reasons
    ) {
        if (durationNights < 1) {
            return;
        }
        int lastDay = durationNights + 1;
        AiPlannerResponse.PlaceItem checkIn = places.stream()
                .filter(place -> Integer.valueOf(1).equals(place.dayNumber()))
                .filter(place -> "숙소".equals(place.category()))
                .findFirst()
                .orElse(null);
        AiPlannerResponse.PlaceItem checkOut = places.stream()
                .filter(place -> Integer.valueOf(lastDay).equals(place.dayNumber()))
                .filter(place -> Integer.valueOf(1).equals(place.orderIndex()))
                .findFirst()
                .orElse(null);

        if (checkIn == null) {
            reasons.add("첫날 숙소 체크인이 없습니다.");
        } else if (!isBetween(checkIn.visitTime(), LocalTime.of(15, 0), LocalTime.of(16, 59))) {
            reasons.add("숙소 체크인은 15:00~16:00 사이여야 합니다: " + displayName(checkIn));
        }

        if (checkOut == null || !"숙소".equals(checkOut.category())) {
            reasons.add("마지막 날 order_index=1은 숙소 체크아웃이어야 합니다.");
        } else {
            if (checkIn != null && !safe(checkIn.name()).equals(safe(checkOut.name()))) {
                reasons.add("체크아웃 숙소명은 체크인 숙소명과 글자 단위로 같아야 합니다.");
            }
            if (!isBetween(checkOut.visitTime(), LocalTime.of(10, 0), LocalTime.of(11, 59))) {
                reasons.add("숙소 체크아웃은 10:00~11:00 사이여야 합니다: " + displayName(checkOut));
            }
        }

        long lastDayCount = places.stream()
                .filter(place -> Integer.valueOf(lastDay).equals(place.dayNumber()))
                .count();
        if (lastDayCount < 3) {
            reasons.add("마지막 날은 체크아웃 이후 최소 2개 일정을 더 포함해야 합니다.");
        }
    }

    private void validateOrderAndTime(List<AiPlannerResponse.PlaceItem> places, List<String> reasons) {
        Map<Integer, List<AiPlannerResponse.PlaceItem>> byDay = places.stream()
                .filter(place -> place.dayNumber() != null)
                .collect(Collectors.groupingBy(AiPlannerResponse.PlaceItem::dayNumber));

        for (Map.Entry<Integer, List<AiPlannerResponse.PlaceItem>> entry : byDay.entrySet()) {
            List<AiPlannerResponse.PlaceItem> dayPlaces = entry.getValue().stream()
                    .sorted(Comparator.comparing(place -> place.orderIndex() == null ? Integer.MAX_VALUE : place.orderIndex()))
                    .toList();
            LocalTime previousTime = null;
            for (int i = 0; i < dayPlaces.size(); i++) {
                AiPlannerResponse.PlaceItem place = dayPlaces.get(i);
                int expectedOrder = i + 1;
                if (!Integer.valueOf(expectedOrder).equals(place.orderIndex())) {
                    reasons.add(entry.getKey() + "일차 order_index는 1부터 순서대로 증가해야 합니다.");
                    break;
                }
                LocalTime currentTime = parseTime(place.visitTime());
                if (previousTime != null && currentTime != null && !currentTime.isAfter(previousTime)) {
                    reasons.add(entry.getKey() + "일차 visit_time은 order_index 순서대로 늦어져야 합니다.");
                    break;
                }
                previousTime = currentTime != null ? currentTime : previousTime;

                if (currentTime != null
                        && currentTime.isAfter(LocalTime.of(21, 0))
                        && isGeneralLateNightCategory(place.category())) {
                    reasons.add("일반 관광지/카페/식당/쇼핑은 21:00 이후 배치하지 마세요: " + displayName(place));
                }
            }
        }
    }

    private void validateDuplicatePlaces(List<AiPlannerResponse.PlaceItem> places, List<String> reasons) {
        Set<String> seen = new HashSet<>();
        for (AiPlannerResponse.PlaceItem place : places) {
            if (isSharedAllowedCategory(place.category())) {
                continue;
            }
            String normalizedName = normalize(place.name());
            if (!normalizedName.isBlank() && !seen.add(normalizedName)) {
                reasons.add("같은 식당/카페/관광지/액티비티가 중복 배치되었습니다: " + place.name());
            }
        }

        boolean hasDonggung = places.stream().anyMatch(place -> normalize(place.name()).contains("동궁과월지"));
        boolean hasAnapji = places.stream().anyMatch(place -> normalize(place.name()).contains("안압지"));
        if (hasDonggung && hasAnapji) {
            reasons.add("\"안압지\"와 \"동궁과월지\"는 같은 장소로 보고 둘 다 넣지 마세요.");
        }
    }

    private void validateMealRules(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (conditions.durationNights() >= 1) {
            Set<Integer> daysWithRestaurant = places.stream()
                    .filter(place -> "식당".equals(place.category()))
                    .map(AiPlannerResponse.PlaceItem::dayNumber)
                    .collect(Collectors.toSet());
            for (int day = 1; day <= conditions.durationNights() + 1; day++) {
                if (!daysWithRestaurant.contains(day)) {
                    reasons.add(day + "일차에 식당(category=\"식당\")이 없습니다.");
                }
            }
        }

        long restaurantCount = places.stream()
                .filter(place -> "식당".equals(place.category()))
                .count();
        if (conditions.durationNights() == 1 && restaurantCount < 2) {
            reasons.add("1박2일은 식당을 최소 2개 포함해야 합니다.");
        }
        if (conditions.durationNights() >= 2 && restaurantCount < 3) {
            reasons.add("2박3일 이상은 식당을 최소 3개 포함해야 합니다.");
        }
        if (conditions.hasVeganConstraint() && restaurantCount < 2) {
            reasons.add("비건/채식/사찰음식 조건이 있으면 실제 식당을 최소 2개 포함해야 합니다.");
        }
    }

    private void validateFoodFocusedDayTrip(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (conditions.durationNights() != 0 || !conditions.hasFoodFocusedIntent()) {
            return;
        }

        long restaurantCount = places.stream()
                .filter(place -> "식당".equals(place.category()))
                .count();
        if (restaurantCount < 2) {
            reasons.add("먹는 여행/맛집 중심 당일치기는 A/B안 각각 식당을 최소 2개 포함해야 합니다.");
        }
    }

    private void validateRegionalFoodFit(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (conditions.hasSeafoodIntent() || !normalize(conditions.destination()).contains("전주")) {
            return;
        }

        places.stream()
                .filter(place -> "식당".equals(place.category()))
                .filter(place -> containsSeafoodRestaurantSignal(place.name(), place.description()))
                .forEach(place -> reasons.add(
                        "전주 미식 요청에는 요청 없는 횟집/해산물 식당보다 비빔밥, 콩나물국밥, 한정식 등 지역 대표 음식을 우선하세요: "
                                + displayName(place)
                ));
    }

    private void validateGroupMealPreparation(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (!conditions.hasGroupShoppingIntent()) {
            return;
        }

        boolean hasShoppingCard = places.stream()
                .anyMatch(place -> "쇼핑".equals(place.category())
                        && containsAnyNormalized(placeText(place), "마트", "슈퍼", "편의점", "장보기", "음료", "바베큐", "바비큐", "bbq"));
        if (!hasShoppingCard) {
            reasons.add("바베큐/음료 준비 요청이 있으면 실제 마트·슈퍼·편의점 쇼핑 카드를 포함하세요.");
        }

        if (conditions.hasBarbecueIntent()) {
            boolean hasLodgingPreparation = places.stream()
                    .anyMatch(place -> "숙소".equals(place.category())
                            && containsAnyNormalized(placeText(place), "바베큐", "바비큐", "bbq", "휴식", "준비"));
            if (!hasLodgingPreparation) {
                reasons.add("바베큐 요청이 있으면 숙소 체크인 설명에 바베큐 또는 휴식 준비 흐름을 드러내세요.");
            }
        }
    }

    private void validateMustInclude(
            List<AiPlannerResponse.PlaceItem> places,
            List<String> mustInclude,
            List<String> reasons
    ) {
        for (String include : mustInclude) {
            IncludeRequirement requirement = IncludeRequirement.from(include, this::normalize);
            if (requirement.isBlank()) {
                continue;
            }
            boolean found = places.stream().anyMatch(requirement::matches);
            if (!found) {
                reasons.add("must_include 항목이 일정에 반영되지 않았습니다: " + include);
                continue;
            }
            if (hasEveningIntent(include)) {
                boolean eveningMatched = places.stream()
                        .filter(requirement::matches)
                        .anyMatch(place -> isAfterOrEqual(place.visitTime(), LocalTime.of(18, 0)));
                if (!eveningMatched) {
                    reasons.add("must_include 야경/일몰 항목은 18:00 이후 일정이어야 합니다: " + include);
                }
            }
        }
    }

    private void validateConflictOpinionIntent(
            List<AiPlannerResponse.PlaceItem> places,
            String expectedLabel,
            Conditions conditions,
            List<String> reasons
    ) {
        String wants = conditions.conflictWants(expectedLabel);
        if (wants.isBlank()) {
            return;
        }

        String normalizedWants = normalize(wants);
        if (normalizedWants.contains("서핑") || normalizedWants.contains("서프")) {
            boolean hasSurfingPlace = places.stream()
                    .anyMatch(place -> containsAnyNormalized(
                            placeText(place),
                            "서핑", "서프", "해양스포츠", "수상스포츠"
                    ));
            if (!hasSurfingPlace) {
                reasons.add(expectedLabel + "안 conflict 의견의 서핑 일정이 반영되지 않았습니다: " + wants);
            }
        }

        if (normalizedWants.contains("케이블카")) {
            List<AiPlannerResponse.PlaceItem> cableCarPlaces = places.stream()
                    .filter(place -> containsAnyNormalized(placeText(place), "케이블카"))
                    .toList();
            if (cableCarPlaces.isEmpty()) {
                reasons.add(expectedLabel + "안 conflict 의견의 케이블카 일정이 반영되지 않았습니다: " + wants);
            } else if (hasEveningIntent(wants)
                    && cableCarPlaces.stream().noneMatch(place -> isAfterOrEqual(place.visitTime(), LocalTime.of(18, 0)))) {
                reasons.add(expectedLabel + "안 conflict 의견의 케이블카 야경은 18:00 이후 일정이어야 합니다: " + wants);
            }
        }

        if (hasSunriseIntent(wants)) {
            IncludeRequirement requirement = IncludeRequirement.from(wants, this::normalize);
            if (requirement.isBlank()) {
                return;
            }
            List<AiPlannerResponse.PlaceItem> matchedPlaces = places.stream()
                    .filter(requirement::matches)
                    .toList();
            if (matchedPlaces.isEmpty()) {
                reasons.add(expectedLabel + "안 conflict 의견의 일출 장소가 반영되지 않았습니다: " + wants);
            } else if (matchedPlaces.stream()
                    .noneMatch(place -> isBetween(place.visitTime(), LocalTime.of(4, 0), LocalTime.of(8, 30)))) {
                reasons.add(expectedLabel + "안 conflict 의견의 일출 일정은 04:00~08:30 사이여야 합니다: " + wants);
            }
        }

        if (hasEveningIntent(wants) && !normalizedWants.contains("케이블카")) {
            IncludeRequirement requirement = IncludeRequirement.from(wants, this::normalize);
            if (requirement.isBlank()) {
                return;
            }
            List<AiPlannerResponse.PlaceItem> matchedPlaces = places.stream()
                    .filter(requirement::matches)
                    .toList();
            if (matchedPlaces.isEmpty()) {
                reasons.add(expectedLabel + "안 conflict 의견의 야경/일몰 장소가 반영되지 않았습니다: " + wants);
            } else if (matchedPlaces.stream()
                    .noneMatch(place -> isAfterOrEqual(place.visitTime(), LocalTime.of(18, 0)))) {
                reasons.add(expectedLabel + "안 conflict 의견의 야경/일몰 일정은 18:00 이후여야 합니다: " + wants);
            }
        }
    }

    private void validateMustExclude(
            List<AiPlannerResponse.PlaceItem> places,
            List<String> mustExclude,
            List<String> reasons
    ) {
        for (String exclude : mustExclude) {
            String keyword = normalize(exclude);
            if (keyword.isBlank()) {
                continue;
            }
            for (AiPlannerResponse.PlaceItem place : places) {
                String haystack = normalize(place.name() + " " + place.description() + " " + place.category());
                if (containsExcludedKeyword(haystack, keyword)) {
                    reasons.add("must_exclude 항목이 places에 포함되었습니다: " + exclude + " / " + displayName(place));
                }
            }
        }
    }

    private boolean containsExcludedKeyword(String haystack, String keyword) {
        String withoutNegatedMentions = haystack
                .replaceAll(Pattern.quote(keyword) + "(을|를|이|가|은|는)?(제외|빼고|빼서|없는|없이|사용하지않|피하)", "");

        if ("회".equals(keyword)) {
            return withoutNegatedMentions
                    .replace("회관", "")
                    .matches(".*(횟집|회센터|회정식|활어회|모둠회|생선회|회포차|회코스).*");
        }
        return withoutNegatedMentions.contains(keyword);
    }

    private boolean containsSeafoodRestaurantSignal(String name, String description) {
        String value = normalize(name + " " + description);
        return value.contains("횟집")
                || value.contains("회센터")
                || value.contains("활어")
                || value.contains("생선회")
                || value.contains("해산물")
                || value.contains("수산");
    }

    private boolean containsAnyNormalized(String source, String... keywords) {
        String normalized = normalize(source);
        return Arrays.stream(keywords)
                .map(this::normalize)
                .anyMatch(normalized::contains);
    }

    private String placeText(AiPlannerResponse.PlaceItem place) {
        return place.name() + " " + place.description() + " " + place.areaHint() + " " + place.searchKeyword();
    }

    private void validateForbiddenPlaces(
            List<AiPlannerResponse.PlaceItem> places,
            List<String> forbiddenPlaceNames,
            List<String> mustInclude,
            List<String> reasons
    ) {
        if (forbiddenPlaceNames == null || forbiddenPlaceNames.isEmpty()) {
            return;
        }
        Set<String> forbidden = forbiddenPlaceNames.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        for (AiPlannerResponse.PlaceItem place : places) {
            if (isSharedAllowedCategory(place.category())) {
                continue;
            }
            boolean requiredPlace = mustInclude.stream()
                    .map(include -> IncludeRequirement.from(include, this::normalize))
                    .filter(requirement -> !requirement.isBlank())
                    .anyMatch(requirement -> requirement.matches(place));
            if (requiredPlace) {
                continue;
            }
            if (forbidden.contains(normalize(place.name()))) {
                reasons.add("A안 금지 장소가 B안에 포함되었습니다: " + place.name());
            }
        }
    }

    private void validateTransportEnding(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (!conditions.requiresTrainStation()) {
            return;
        }
        AiPlannerResponse.PlaceItem last = places.stream()
                .filter(place -> place.dayNumber() != null && place.orderIndex() != null)
                .max(Comparator
                        .comparing(AiPlannerResponse.PlaceItem::dayNumber)
                        .thenComparing(AiPlannerResponse.PlaceItem::orderIndex))
                .orElse(null);
        String expectedStation = representativeStation(conditions.destination());
        if (last == null || !normalize(last.name()).contains(normalize(expectedStation))) {
            reasons.add("KTX/기차/역 언급이 있으므로 마지막 장소는 " + expectedStation + "이어야 합니다.");
        }
    }

    private void validateUnrequestedTransit(
            List<AiPlannerResponse.PlaceItem> places,
            Conditions conditions,
            List<String> reasons
    ) {
        if (conditions.hasBoundaryTransitIntent()) {
            return;
        }
        places.stream()
                .filter(place -> "이동".equals(place.category()))
                .forEach(place -> reasons.add(
                        "이동수단/귀가 거점이 명시되지 않았으므로 이동 카드를 넣지 마세요: " + displayName(place)
                ));
    }

    private void validateBudget(
            List<AiPlannerResponse.PlaceItem> places,
            Integer perPersonLimit,
            List<String> reasons
    ) {
        if (perPersonLimit == null || perPersonLimit <= 0) {
            return;
        }
        int recalculated = places.stream().mapToInt(this::normalizedCost).sum();
        if (recalculated > perPersonLimit) {
            reasons.add("estimated_cost 합계가 budget.per_person_limit을 초과합니다.");
        }
    }

    private void validateNames(List<AiPlannerResponse.PlaceItem> places, List<String> reasons) {
        for (AiPlannerResponse.PlaceItem place : places) {
            String name = safe(place.name());
            if (name.isBlank()) {
                reasons.add("places[].name이 비어 있습니다.");
                continue;
            }
            if (BANNED_UNCERTAIN_NAMES.stream().anyMatch(name::equals)) {
                reasons.add("실제 상호명으로 확실하지 않은 이름을 사용했습니다: " + name);
            }
            if (containsGenericNamePattern(place)) {
                reasons.add("일반명사형 장소명이 포함되었습니다: " + name);
            }
        }
    }

    private boolean containsGenericNamePattern(AiPlannerResponse.PlaceItem place) {
        String normalized = normalize(place.name());
        return normalized.contains("맛집")
                || containsGenericCafeStreetPattern(normalized, place.category())
                || normalized.equals("숙소")
                || normalized.equals("호텔")
                || normalized.equals("식당")
                || normalized.equals("관광지")
                || normalized.equals("체크인")
                || normalized.equals("체크아웃");
    }

    private boolean containsGenericCafeStreetPattern(String normalizedName, String category) {
        if (!normalizedName.contains("카페거리")) {
            return false;
        }
        return !"관광지".equals(category) || normalizedName.equals("카페거리");
    }

    private boolean isGeneralLateNightCategory(String category) {
        return "관광지".equals(category)
                || "카페".equals(category)
                || "식당".equals(category)
                || "쇼핑".equals(category);
    }

    private boolean isSharedAllowedCategory(String category) {
        return "숙소".equals(category) || "이동".equals(category);
    }

    private int normalizedCost(AiPlannerResponse.PlaceItem place) {
        if (place == null || "숙소".equals(place.category()) || "이동".equals(place.category())) {
            return 0;
        }
        return place.estimatedCost() == null ? 0 : place.estimatedCost();
    }

    private boolean isBetween(String visitTime, LocalTime start, LocalTime end) {
        LocalTime time = parseTime(visitTime);
        return time != null && !time.isBefore(start) && !time.isAfter(end);
    }

    private boolean isAfterOrEqual(String visitTime, LocalTime threshold) {
        LocalTime time = parseTime(visitTime);
        return time != null && !time.isBefore(threshold);
    }

    private boolean hasEveningIntent(String value) {
        String normalized = normalize(value);
        return normalized.contains("야경") || normalized.contains("일몰");
    }

    private boolean hasSunriseIntent(String value) {
        return normalize(value).contains("일출");
    }

    private LocalTime parseTime(String visitTime) {
        if (visitTime == null || visitTime.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(visitTime, VISIT_TIME_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface TextNormalizer {
        String normalize(String value);
    }

    private record IncludeRequirement(
            String rawKeyword,
            List<String> placeKeywords,
            List<String> intentKeywords,
            TextNormalizer normalizer
    ) {
        private static IncludeRequirement from(String value, TextNormalizer normalizer) {
            String rawKeyword = normalizer.normalize(value)
                    .replace("야경", "")
                    .replace("일몰", "")
                    .replace("일출", "")
                    .replace("마지막날", "")
                    .replace("마지막", "");

            String tokenSource = safeForRequirement(value)
                    .replace("야경", " ")
                    .replace("일몰", " ")
                    .replace("일출", " ")
                    .replace("마지막날", " ")
                    .replace("마지막", " ");
            List<String> tokens = Arrays.stream(tokenSource.split("[\\s,./·]+"))
                    .map(normalizer::normalize)
                    .map(IncludeRequirement::normalizeToken)
                    .filter(token -> !token.isBlank())
                    .filter(token -> !INCLUDE_FILLER_TOKENS.contains(token))
                    .distinct()
                    .toList();

            Set<String> placeKeywords = new LinkedHashSet<>();
            Set<String> intentKeywords = new LinkedHashSet<>();
            for (String token : tokens) {
                if (isIntentKeyword(token)) {
                    intentKeywords.add(token);
                } else {
                    placeKeywords.add(token);
                }
            }
            if (placeKeywords.isEmpty() && intentKeywords.isEmpty() && !rawKeyword.isBlank()) {
                placeKeywords.add(rawKeyword);
            }
            return new IncludeRequirement(
                    rawKeyword,
                    List.copyOf(placeKeywords),
                    List.copyOf(intentKeywords),
                    normalizer
            );
        }

        private boolean isBlank() {
            return rawKeyword.isBlank() && placeKeywords.isEmpty() && intentKeywords.isEmpty();
        }

        private boolean matches(AiPlannerResponse.PlaceItem place) {
            String haystack = normalizer.normalize(
                    place.name() + " " + place.description() + " "
                            + place.category() + " " + place.areaHint() + " " + place.searchKeyword()
            );
            boolean hasPlaceKeyword = !placeKeywords.isEmpty();
            boolean placeMatched = placeKeywords.isEmpty()
                    || placeKeywords.stream().allMatch(keyword -> matchesPlaceKeyword(keyword, place, haystack));
            boolean intentMatched = intentKeywords.isEmpty()
                    || intentKeywords.stream().allMatch(keyword -> matchesIntent(keyword, place, haystack, hasPlaceKeyword));
            if (placeMatched && intentMatched) {
                return true;
            }
            return intentKeywords.isEmpty() && !rawKeyword.isBlank() && haystack.contains(rawKeyword);
        }

        private boolean matchesPlaceKeyword(
                String keyword,
                AiPlannerResponse.PlaceItem place,
                String haystack
        ) {
            if (isAccommodationKeyword(keyword)) {
                return haystack.contains(keyword) || "숙소".equals(place.category());
            }
            if ("마트".equals(keyword) || "장보기".equals(keyword) || "슈퍼".equals(keyword)
                    || "편의점".equals(keyword)) {
                return haystack.contains(keyword) || "쇼핑".equals(place.category());
            }
            return haystack.contains(keyword);
        }

        private boolean matchesIntent(
                String keyword,
                AiPlannerResponse.PlaceItem place,
                String haystack,
                boolean hasPlaceKeyword
        ) {
            if ("회".equals(keyword) || "횟집".equals(keyword) || "회센터".equals(keyword)
                    || "활어".equals(keyword) || "활어회".equals(keyword) || "생선회".equals(keyword)
                    || "해산물".equals(keyword) || "수산".equals(keyword)) {
                return haystack.contains(keyword)
                        || haystack.contains("횟집")
                        || haystack.contains("회센터")
                        || haystack.contains("활어")
                        || haystack.contains("생선회")
                        || haystack.contains("해산물")
                        || haystack.contains("수산")
                        || (hasPlaceKeyword && "식당".equals(place.category()));
            }
            if ("카페".equals(keyword) || "커피".equals(keyword) || "디저트".equals(keyword)) {
                return haystack.contains(keyword) || "카페".equals(place.category());
            }
            if ("바베큐".equals(keyword) || "바비큐".equals(keyword) || "bbq".equals(keyword)) {
                return haystack.contains("바베큐")
                        || haystack.contains("바비큐")
                        || haystack.contains("bbq")
                        || ("숙소".equals(place.category())
                        && (haystack.contains("준비") || haystack.contains("휴식") || haystack.contains("저녁")));
            }
            if ("저녁".equals(keyword)) {
                return haystack.contains("저녁")
                        || haystack.contains("바베큐")
                        || haystack.contains("바비큐")
                        || haystack.contains("bbq");
            }
            if ("장보기".equals(keyword) || "마트".equals(keyword) || "음료".equals(keyword)) {
                return haystack.contains(keyword) || "쇼핑".equals(place.category());
            }
            return haystack.contains(keyword);
        }

        private static boolean isIntentKeyword(String token) {
            return INCLUDE_INTENT_KEYWORDS.contains(token);
        }

        private static boolean isAccommodationKeyword(String token) {
            return token.contains("펜션")
                    || token.contains("글램핑")
                    || token.contains("캠핑")
                    || token.contains("숙소")
                    || token.contains("리조트");
        }

        private static String stripLocationMarker(String token) {
            String result = token;
            for (String marker : INCLUDE_FILLER_TOKENS) {
                if (result.endsWith(marker) && result.length() > marker.length()) {
                    result = result.substring(0, result.length() - marker.length());
                }
            }
            return result;
        }

        private static String normalizeToken(String token) {
            String result = stripKoreanSuffix(token);
            result = stripLocationMarker(result);
            result = stripKoreanSuffix(result);
            result = stripLocationMarker(result);
            return result;
        }

        private static String stripKoreanSuffix(String token) {
            String result = token;
            for (String suffix : INCLUDE_TOKEN_SUFFIXES) {
                if (result.endsWith(suffix) && result.length() > suffix.length()) {
                    return result.substring(0, result.length() - suffix.length());
                }
            }
            return result;
        }

        private static String safeForRequirement(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        }
    }

    private String representativeStation(String destination) {
        String normalizedDestination = normalize(destination);
        if (normalizedDestination.contains("부산")) {
            return "부산역";
        }
        if (normalizedDestination.contains("서울")) {
            return "서울역";
        }
        if (normalizedDestination.contains("강릉")) {
            return "강릉역";
        }
        if (normalizedDestination.contains("경주")) {
            return "경주역";
        }
        if (normalizedDestination.contains("여수")) {
            return "여수엑스포역";
        }
        return safe(destination) + "역";
    }

    private String displayName(AiPlannerResponse.PlaceItem place) {
        return place == null ? "(null)" : safe(place.name());
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record ValidationResult(boolean valid, List<String> reasons) {
        private static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        private static ValidationResult failure(List<String> reasons) {
            return new ValidationResult(false, List.copyOf(reasons));
        }

        public String toPromptFeedback() {
            return reasons.stream()
                    .map(reason -> "- " + reason)
                    .collect(Collectors.joining("\n"));
        }
    }

    private record Conditions(
            String destination,
            int durationNights,
            Integer perPersonLimit,
            String transport,
            List<String> constraints,
            List<String> preferences,
            List<String> hostRequests,
            List<String> mustInclude,
            List<String> mustExclude,
            Map<String, String> conflictWants
    ) {
        private static Conditions from(String conditionsJson, ObjectMapper objectMapper) {
            try {
                JsonNode root = objectMapper.readTree(conditionsJson);
                return new Conditions(
                        text(root.path("destination")),
                        durationNights(root),
                        nullableInt(root.path("budget").path("per_person_limit")),
                        text(root.path("transport")),
                        textArray(root.path("constraints")),
                        textArray(root.path("preferences")),
                        textArray(root.path("host_requests")),
                        textArray(root.path("must_include")),
                        textArray(root.path("must_exclude")),
                        conflictWants(root.path("conflicts"))
                );
            } catch (Exception e) {
                return new Conditions("", -1, null, "", List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
            }
        }

        private String conflictWants(String expectedLabel) {
            return conflictWants.getOrDefault(expectedLabel, "");
        }

        private boolean hasVeganConstraint() {
            String joined = normalizeForConditions(String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustExclude));
            return joined.contains("비건")
                    || joined.contains("채식")
                    || joined.contains("사찰음식")
                    || (joined.contains("고기") && joined.contains("유제품") && joined.contains("계란"));
        }

        private boolean hasFoodFocusedIntent() {
            String joined = normalizeForConditions(String.join(" ", constraints) + " "
                    + String.join(" ", preferences) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustInclude));
            return joined.contains("맛있는")
                    || joined.contains("맛집")
                    || joined.contains("먹")
                    || joined.contains("미식")
                    || joined.contains("식도락")
                    || joined.contains("음식");
        }

        private boolean hasSeafoodIntent() {
            String joined = normalizeForConditions(String.join(" ", constraints) + " "
                    + String.join(" ", preferences) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustInclude));
            return joined.contains("해산물")
                    || joined.contains("횟집")
                    || joined.contains("회먹")
                    || joined.contains("회 ")
                    || joined.contains("생선")
                    || joined.contains("수산");
        }

        private boolean hasGroupShoppingIntent() {
            String joined = conditionText();
            return hasBarbecueIntent()
                    || joined.contains("장보기")
                    || joined.contains("마트")
                    || joined.contains("슈퍼")
                    || (joined.contains("음료") && (joined.contains("술못마심") || joined.contains("술못마시")
                    || joined.contains("술약함") || joined.contains("무알코올")));
        }

        private boolean hasBarbecueIntent() {
            String joined = conditionText();
            return joined.contains("바베큐")
                    || joined.contains("바비큐")
                    || joined.contains("bbq");
        }

        private boolean requiresTrainStation() {
            String joined = normalizeForConditions(transport + " "
                    + String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests));
            return joined.contains("ktx") || joined.contains("기차") || joined.contains("역");
        }

        private boolean hasBoundaryTransitIntent() {
            String joined = normalizeForConditions(transport + " "
                    + String.join(" ", constraints) + " "
                    + String.join(" ", hostRequests));
            return joined.contains("ktx")
                    || joined.contains("기차")
                    || joined.contains("역")
                    || joined.contains("공항")
                    || joined.contains("항공")
                    || joined.contains("비행기")
                    || joined.contains("버스")
                    || joined.contains("터미널")
                    || joined.contains("대중교통")
                    || joined.contains("렌트");
        }

        private String conditionText() {
            return normalizeForConditions(String.join(" ", constraints) + " "
                    + String.join(" ", preferences) + " "
                    + String.join(" ", hostRequests) + " "
                    + String.join(" ", mustInclude) + " "
                    + String.join(" ", mustExclude) + " "
                    + String.join(" ", conflictWants.values()));
        }

        private static int durationNights(JsonNode root) {
            JsonNode direct = root.path("duration_nights");
            if (direct.isNumber()) {
                return direct.asInt();
            }
            JsonNode nested = root.path("dates").path("duration_nights");
            return nested.isNumber() ? nested.asInt() : -1;
        }

        private static Integer nullableInt(JsonNode node) {
            return node != null && node.isNumber() ? node.asInt() : null;
        }

        private static List<String> textArray(JsonNode node) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = text(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return values;
        }

        private static Map<String, String> conflictWants(JsonNode conflicts) {
            if (conflicts == null || !conflicts.isArray() || conflicts.isEmpty()) {
                return Map.of();
            }
            JsonNode opinions = conflicts.path(0).path("opinions");
            if (!opinions.isArray()) {
                return Map.of();
            }

            String aWants = opinions.size() > 0 ? text(opinions.path(0).path("wants")) : "";
            String bWants = opinions.size() > 1 ? text(opinions.path(1).path("wants")) : "";
            return Map.of(
                    "A", aWants,
                    "B", bWants
            );
        }

        private static String text(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.asText("").trim();
        }

        private static String normalizeForConditions(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }
    }
}
