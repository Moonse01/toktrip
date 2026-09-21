package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 응답의 단순 구조 오류를 기계적으로 보정한다.
 * retry 전에 이 서비스로 보정을 시도하면 불필요한 API 호출을 줄일 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerRepairService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern MISSING_RESTAURANT = Pattern.compile("(\\d+)일차에 식당.*없습니다");
    private static final Pattern MISSING_STATION = Pattern.compile("마지막 장소는 (.+)이어야 합니다");
    private static final Pattern AFTER_CHECKOUT = Pattern.compile("마지막 날은 체크아웃 이후 최소 2개");
    private static final Pattern CHECKOUT_NAME = Pattern.compile("체크아웃 숙소명은 체크인 숙소명과");
    private static final Pattern CHECKIN_TIME = Pattern.compile("숙소 체크인은 15:00~16:00");
    private static final Pattern CHECKOUT_FIRST = Pattern.compile("마지막 날 order_index=1은 숙소 체크아웃");
    private static final Pattern ORDER_OR_TIME = Pattern.compile("(order_index는|visit_time은)");
    private static final Pattern EVENING_MUST_INCLUDE = Pattern.compile("must_include 야경/일몰 항목은 18:00 이후");
    private static final Pattern CHECKOUT_TIME = Pattern.compile("숙소 체크아웃은 10:00~11:00");
    private static final Pattern SUNRISE_CONFLICT = Pattern.compile("conflict 의견의 일출 일정은 04:00~08:30");

    private final ObjectMapper objectMapper;

    public RepairResult tryRepair(
            AiPlannerResponse.CandidatePlan plan,
            String conditionsJson,
            PlannerValidationService.ValidationResult validation,
            List<String> forbiddenPlaceNames
    ) {
        if (validation.valid() || plan == null || plan.places() == null) {
            return new RepairResult(plan, List.of());
        }

        boolean anyDayMissing = validation.reasons().stream()
                .anyMatch(r -> r.contains("일차 일정이 없습니다"));
        if (anyDayMissing) {
            return new RepairResult(plan, List.of());
        }

        String destination = parseDestination(conditionsJson);
        int durationNights = parseDurationNights(conditionsJson);
        FoodConstraints foodConstraints = parseFoodConstraints(conditionsJson);
        MealPreparationIntent mealPreparationIntent = parseMealPreparationIntent(conditionsJson);
        Set<String> requiredKeywords = new HashSet<>(parseMustIncludeKeywords(conditionsJson));
        if (mealPreparationIntent.hasGroupShoppingIntent()) {
            requiredKeywords.addAll(Set.of("마트", "장보기", "음료", "바베큐", "바비큐", "bbq"));
        }
        if (destination.isBlank()) {
            return new RepairResult(plan, List.of());
        }

        Set<String> usedNames = plan.places().stream()
                .map(p -> normalize(p.name()))
                .filter(n -> !n.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> forbidden = forbiddenPlaceNames == null
                ? Set.of()
                : forbiddenPlaceNames.stream()
                .map(this::normalize)
                .filter(n -> !n.isBlank())
                .collect(Collectors.toSet());

        List<AiPlannerResponse.PlaceItem> places = new ArrayList<>(plan.places());
        List<String> repairs = new ArrayList<>();

        int removedAccommodations = removeMiddleDayAccommodations(places, durationNights);
        if (removedAccommodations > 0) {
            repairs.add("중간 날짜 숙소 복귀 " + removedAccommodations + "개 생략");
        }

        int lastDay = Math.max(1, durationNights + 1);
        for (int day = 1; day <= lastDay; day++) {
            int maxPlaces = day == 1 ? 4 : 5;
            int removed = trimDay(places, day, maxPlaces, requiredKeywords);
            if (removed > 0) {
                repairs.add(day + "일차 과밀 일정 " + removed + "개 정리");
            }
        }

        if (durationNights >= 1
                && mealPreparationIntent.hasBarbecueIntent()
                && repairBarbecuePreparation(places, destination, usedNames, forbidden, foodConstraints)) {
            repairs.add("바베큐/장보기 흐름 보정");
        }

        for (String reason : validation.reasons()) {
            Matcher m;

            m = MISSING_RESTAURANT.matcher(reason);
            if (m.find()) {
                int day = Integer.parseInt(m.group(1));
                if (insertRestaurant(places, day, destination, usedNames, forbidden, foodConstraints)) {
                    repairs.add(day + "일차 식당 추가");
                }
                continue;
            }

            m = MISSING_STATION.matcher(reason);
            if (m.find()) {
                String stationName = m.group(1);
                if (appendEndpoint(places, lastDay, stationName, destination)) {
                    repairs.add("마지막 장소 " + stationName + " 추가");
                }
                continue;
            }

            if (AFTER_CHECKOUT.matcher(reason).find()) {
                int added = insertAfterCheckout(places, lastDay, destination, usedNames, forbidden);
                if (added > 0) {
                    repairs.add("마지막 날 일정 " + added + "개 추가");
                }
                continue;
            }

            if (CHECKOUT_NAME.matcher(reason).find()) {
                if (fixCheckoutName(places, durationNights)) {
                    repairs.add("체크아웃 숙소명 보정");
                }
                continue;
            }

            if (CHECKIN_TIME.matcher(reason).find()) {
                if (fixCheckInTime(places)) {
                    repairs.add("체크인 시간 보정");
                }
                continue;
            }

            if (CHECKOUT_FIRST.matcher(reason).find()) {
                if (ensureCheckoutFirst(places, durationNights)) {
                    repairs.add("체크아웃 위치 보정");
                }
                continue;
            }

            if (ORDER_OR_TIME.matcher(reason).find()) {
                repairs.add("일정 시간 순서 보정");
                continue;
            }

            if (CHECKOUT_TIME.matcher(reason).find()) {
                if (fixCheckOutTime(places, durationNights)) {
                    repairs.add("체크아웃 시간 보정");
                }
                continue;
            }

            if (SUNRISE_CONFLICT.matcher(reason).find()) {
                if (fixSunriseTime(places)) {
                    repairs.add("일출 일정 시간 보정");
                }
                continue;
            }

            if (EVENING_MUST_INCLUDE.matcher(reason).find()) {
                if (fixEveningMustIncludeTime(places, conditionsJson)) {
                    repairs.add("야경/일몰 필수 일정 시간 보정");
                }
            }
        }

        if (repairs.isEmpty()) {
            return new RepairResult(plan, List.of());
        }

        for (int day = 1; day <= lastDay; day++) {
            int maxPlaces = day == 1 ? 4 : 5;
            int removed = trimDay(places, day, maxPlaces, requiredKeywords);
            if (removed > 0) {
                repairs.add(day + "일차 보정 후 과밀 일정 " + removed + "개 정리");
            }
        }

        renumberAllDays(places);

        int totalCost = places.stream()
                .filter(p -> !"숙소".equals(p.category()) && !"이동".equals(p.category()))
                .mapToInt(p -> p.estimatedCost() == null ? 0 : p.estimatedCost())
                .sum();

        AiPlannerResponse.CandidatePlan repaired = new AiPlannerResponse.CandidatePlan(
                plan.label(),
                plan.name(),
                plan.concept(),
                totalCost,
                plan.totalDistanceKm(),
                List.copyOf(places)
        );

        return new RepairResult(repaired, repairs);
    }

    // =========================================================================
    // 일정 밀도 보정
    // =========================================================================

    private int removeMiddleDayAccommodations(
            List<AiPlannerResponse.PlaceItem> places,
            int durationNights
    ) {
        if (durationNights < 2) {
            return 0;
        }
        int lastDay = durationNights + 1;
        int before = places.size();
        places.removeIf(place -> place.dayNumber() != null
                && place.dayNumber() > 1
                && place.dayNumber() < lastDay
                && "숙소".equals(place.category()));
        return before - places.size();
    }

    private int trimDay(
            List<AiPlannerResponse.PlaceItem> places,
            int day,
            int maxPlaces,
            Set<String> requiredKeywords
    ) {
        int removed = 0;
        while (countDayPlaces(places, day) > maxPlaces) {
            int removalIndex = findRemovalIndex(places, day, requiredKeywords);
            if (removalIndex < 0) {
                break;
            }
            places.remove(removalIndex);
            removed++;
        }
        return removed;
    }

    private int findRemovalIndex(
            List<AiPlannerResponse.PlaceItem> places,
            int day,
            Set<String> requiredKeywords
    ) {
        int selectedIndex = -1;
        int selectedPriority = Integer.MAX_VALUE;
        int selectedOrder = Integer.MIN_VALUE;

        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (!Integer.valueOf(day).equals(place.dayNumber())
                    || !isRemovableExtra(places, place, requiredKeywords)) {
                continue;
            }

            int priority = removalPriority(place.category());
            int order = place.orderIndex() == null ? Integer.MAX_VALUE : place.orderIndex();
            if (priority < selectedPriority || (priority == selectedPriority && order > selectedOrder)) {
                selectedIndex = i;
                selectedPriority = priority;
                selectedOrder = order;
            }
        }
        return selectedIndex;
    }

    private boolean isRemovableExtra(
            List<AiPlannerResponse.PlaceItem> places,
            AiPlannerResponse.PlaceItem place,
            Set<String> requiredKeywords
    ) {
        if ("숙소".equals(place.category()) || "이동".equals(place.category())) {
            return false;
        }

        String placeText = normalize(place.name() + " " + place.description());
        boolean required = requiredKeywords.stream()
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(placeText::contains);
        if (required) {
            return false;
        }

        if ("식당".equals(place.category())) {
            long restaurantsOnDay = places.stream()
                    .filter(item -> Objects.equals(item.dayNumber(), place.dayNumber()))
                    .filter(item -> "식당".equals(item.category()))
                    .count();
            return restaurantsOnDay > 1;
        }
        return true;
    }

    private int removalPriority(String category) {
        return switch (category == null ? "" : category) {
            case "카페" -> 0;
            case "쇼핑" -> 1;
            case "관광지" -> 2;
            case "액티비티" -> 3;
            case "식당" -> 4;
            default -> 5;
        };
    }

    private long countDayPlaces(List<AiPlannerResponse.PlaceItem> places, int day) {
        return places.stream()
                .filter(place -> Integer.valueOf(day).equals(place.dayNumber()))
                .count();
    }

    private Set<String> parseMustIncludeKeywords(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            Set<String> keywords = new HashSet<>();
            root.path("must_include").forEach(node -> {
                String keyword = normalizeRequiredKeyword(node.asText(""));
                if (!keyword.isBlank()) {
                    keywords.add(keyword);
                }
            });
            return Set.copyOf(keywords);
        } catch (Exception e) {
            log.warn("[AI] repair 필수 장소 파싱 실패 - {}", e.getMessage());
            return Set.of();
        }
    }

    private String normalizeRequiredKeyword(String value) {
        return normalize(value)
                .replace("야경", "")
                .replace("일몰", "")
                .replace("일출", "")
                .replace("마지막날", "")
                .replace("마지막", "");
    }

    // =========================================================================
    // MT 바베큐/장보기 흐름 보정
    // =========================================================================

    private boolean repairBarbecuePreparation(
            List<AiPlannerResponse.PlaceItem> places,
            String destination,
            Set<String> usedNames,
            Set<String> forbidden,
            FoodConstraints foodConstraints
    ) {
        boolean changed = false;
        if (ensureDayOneLunchRestaurant(places, destination, usedNames, forbidden, foodConstraints)) {
            changed = true;
        }
        if (moveDayOneExternalDinnerToLunch(places)) {
            changed = true;
        }
        if (ensureDayOneShoppingCard(places, destination, usedNames, forbidden)) {
            changed = true;
        }
        if (enrichDayOneAccommodationForBarbecue(places)) {
            changed = true;
        }
        if (normalizeDayOneBarbecueTimes(places)) {
            changed = true;
        }
        if (compactDayOneForBarbecue(places)) {
            changed = true;
        }
        return changed;
    }

    private boolean ensureDayOneLunchRestaurant(
            List<AiPlannerResponse.PlaceItem> places,
            String destination,
            Set<String> usedNames,
            Set<String> forbidden,
            FoodConstraints foodConstraints
    ) {
        boolean hasDayOneRestaurant = places.stream()
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "식당".equals(place.category()));
        if (hasDayOneRestaurant) {
            return false;
        }
        return insertRestaurant(places, 1, destination, usedNames, forbidden, foodConstraints);
    }

    private boolean moveDayOneExternalDinnerToLunch(List<AiPlannerResponse.PlaceItem> places) {
        List<Integer> lateRestaurantIndexes = new ArrayList<>();
        boolean hasLunchRestaurant = false;
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (!Integer.valueOf(1).equals(place.dayNumber()) || !"식당".equals(place.category())) {
                continue;
            }
            LocalTime visitTime = parseTime(place.visitTime());
            if (visitTime != null && !visitTime.isBefore(LocalTime.of(17, 0))) {
                lateRestaurantIndexes.add(i);
            } else {
                hasLunchRestaurant = true;
            }
        }

        boolean changed = false;
        if (!hasLunchRestaurant && !lateRestaurantIndexes.isEmpty()) {
            int keepIndex = lateRestaurantIndexes.remove(0);
            places.set(keepIndex, withVisitTime(places.get(keepIndex), "12:00"));
            changed = true;
            hasLunchRestaurant = true;
        }

        for (int i = lateRestaurantIndexes.size() - 1; i >= 0; i--) {
            int index = lateRestaurantIndexes.get(i);
            if (hasLunchRestaurant) {
                places.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private boolean ensureDayOneShoppingCard(
            List<AiPlannerResponse.PlaceItem> places,
            String destination,
            Set<String> usedNames,
            Set<String> forbidden
    ) {
        boolean hasShopping = places.stream()
                .anyMatch(place -> Integer.valueOf(1).equals(place.dayNumber())
                        && "쇼핑".equals(place.category())
                        && containsAnyNormalized(place.name() + " " + place.description(),
                        "마트", "슈퍼", "편의점", "장보기", "음료", "바베큐", "바비큐", "bbq"));
        if (hasShopping) {
            return false;
        }

        BackupPlace shopping = pickBackup(backupShopping(destination), usedNames, forbidden);
        if (shopping == null) {
            return false;
        }
        places.add(new AiPlannerResponse.PlaceItem(
                1, 0,
                shopping.name, shopping.category,
                shopping.description,
                shopping.cost, 40, "15:00",
                shopping.area,
                shopping.area + " " + shopping.name,
                null, null, null
        ));
        usedNames.add(normalize(shopping.name));
        return true;
    }

    private boolean enrichDayOneAccommodationForBarbecue(List<AiPlannerResponse.PlaceItem> places) {
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (!Integer.valueOf(1).equals(place.dayNumber()) || !"숙소".equals(place.category())) {
                continue;
            }
            String normalizedDescription = normalize(place.description());
            boolean hasBarbecueText = containsAnyNormalized(place.description(), "바베큐", "바비큐", "bbq");
            boolean hasDinnerText = normalizedDescription.contains("저녁");
            boolean hasLodgingText = containsAnyNormalized(place.description(), "펜션", "글램핑", "숙소");
            if (hasBarbecueText && hasDinnerText && hasLodgingText && "16:00".equals(place.visitTime())) {
                return false;
            }

            String baseDescription = place.description() == null || place.description().isBlank()
                    ? "숙소에서 체크인합니다."
                    : place.description().trim();
            String suffix = " 체크인 후 펜션/글램핑 숙소에서 저녁 바베큐와 무알코올 음료 준비를 합니다.";
            String description = hasBarbecueText && hasDinnerText && hasLodgingText
                    ? baseDescription
                    : baseDescription + suffix;
            places.set(i, withDescriptionAndVisitTime(place, description, "16:00"));
            return true;
        }
        return false;
    }

    private boolean normalizeDayOneBarbecueTimes(List<AiPlannerResponse.PlaceItem> places) {
        boolean changed = false;
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (!Integer.valueOf(1).equals(place.dayNumber())) {
                continue;
            }
            if ("쇼핑".equals(place.category()) && !"15:00".equals(place.visitTime())) {
                places.set(i, withVisitTime(place, "15:00"));
                changed = true;
            }
            if ("숙소".equals(place.category()) && !"16:00".equals(place.visitTime())) {
                places.set(i, withVisitTime(place, "16:00"));
                changed = true;
            }
        }
        return changed;
    }

    private boolean compactDayOneForBarbecue(List<AiPlannerResponse.PlaceItem> places) {
        boolean changed = false;
        while (countDayPlaces(places, 1) > 4) {
            int removalIndex = findBarbecueDayOneRemovalIndex(places);
            if (removalIndex < 0) {
                break;
            }
            places.remove(removalIndex);
            changed = true;
        }
        return changed;
    }

    private int findBarbecueDayOneRemovalIndex(List<AiPlannerResponse.PlaceItem> places) {
        int selectedIndex = -1;
        int selectedPriority = Integer.MAX_VALUE;
        int selectedOrder = Integer.MIN_VALUE;

        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (!Integer.valueOf(1).equals(place.dayNumber()) || isBarbecueCoreDayOnePlace(place)) {
                continue;
            }
            int priority = removalPriority(place.category());
            int order = place.orderIndex() == null ? Integer.MAX_VALUE : place.orderIndex();
            if (priority < selectedPriority || (priority == selectedPriority && order > selectedOrder)) {
                selectedIndex = i;
                selectedPriority = priority;
                selectedOrder = order;
            }
        }
        return selectedIndex;
    }

    private boolean isBarbecueCoreDayOnePlace(AiPlannerResponse.PlaceItem place) {
        return "식당".equals(place.category())
                || "쇼핑".equals(place.category())
                || "숙소".equals(place.category());
    }

    // =========================================================================
    // 식당 삽입
    // =========================================================================

    private boolean insertRestaurant(
            List<AiPlannerResponse.PlaceItem> places,
            int targetDay,
            String destination,
            Set<String> usedNames,
            Set<String> forbidden,
            FoodConstraints foodConstraints
    ) {
        BackupPlace restaurant = pickRestaurantBackup(
                backupRestaurants(destination),
                usedNames,
                forbidden,
                foodConstraints
        );
        if (restaurant == null) {
            return false;
        }

        String time = findMealSlot(places, targetDay);
        if (time == null) {
            return false;
        }

        places.add(new AiPlannerResponse.PlaceItem(
                targetDay, 0,
                restaurant.name, "식당",
                restaurant.description,
                restaurant.cost, 60, time,
                restaurant.area,
                restaurant.area + " " + restaurant.name,
                null, null, null
        ));
        usedNames.add(normalize(restaurant.name));
        return true;
    }

    private String findMealSlot(List<AiPlannerResponse.PlaceItem> places, int day) {
        List<LocalTime> dayTimes = places.stream()
                .filter(p -> Integer.valueOf(day).equals(p.dayNumber()))
                .map(p -> parseTime(p.visitTime()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        for (String candidate : List.of("12:00", "12:30", "18:00", "18:30", "13:00", "17:30")) {
            LocalTime t = LocalTime.parse(candidate, TIME_FMT);
            if (hasGap(dayTimes, t)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasGap(List<LocalTime> existingTimes, LocalTime candidate) {
        for (LocalTime existing : existingTimes) {
            if (Math.abs(existing.toSecondOfDay() - candidate.toSecondOfDay()) < 1800) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // 역/공항 끝점 추가
    // =========================================================================

    private boolean appendEndpoint(
            List<AiPlannerResponse.PlaceItem> places,
            int lastDay,
            String stationName,
            String destination
    ) {
        boolean alreadyExists = places.stream()
                .anyMatch(p -> normalize(p.name()).contains(normalize(stationName)));
        if (alreadyExists) {
            return false;
        }

        String lastTime = places.stream()
                .filter(p -> Integer.valueOf(lastDay).equals(p.dayNumber()))
                .map(AiPlannerResponse.PlaceItem::visitTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse("16:00");

        LocalTime after = parseTime(lastTime);
        String endpointTime = after != null
                ? after.plusMinutes(90).format(TIME_FMT)
                : "17:00";

        places.add(new AiPlannerResponse.PlaceItem(
                lastDay, 0,
                stationName, "이동",
                "귀가를 위해 " + stationName + "으로 이동합니다.",
                0, 30, endpointTime,
                stationName,
                destination + " " + stationName,
                null, null, null
        ));
        return true;
    }

    // =========================================================================
    // 체크아웃 이후 장소 보충
    // =========================================================================

    private int insertAfterCheckout(
            List<AiPlannerResponse.PlaceItem> places,
            int lastDay,
            String destination,
            Set<String> usedNames,
            Set<String> forbidden
    ) {
        long lastDayCount = places.stream()
                .filter(p -> Integer.valueOf(lastDay).equals(p.dayNumber()))
                .count();
        int needed = (int) Math.max(0, 3 - lastDayCount);
        int added = 0;

        if (needed > 0) {
            BackupPlace attraction = pickBackup(backupAttractions(destination), usedNames, forbidden);
            if (attraction != null) {
                places.add(new AiPlannerResponse.PlaceItem(
                        lastDay, 0,
                        attraction.name, attraction.category,
                        attraction.description,
                        attraction.cost, 60, "13:00",
                        attraction.area,
                        attraction.area + " " + attraction.name,
                        null, null, null
                ));
                usedNames.add(normalize(attraction.name));
                added++;
                needed--;
            }
        }

        if (needed > 0) {
            BackupPlace cafe = pickBackup(backupCafes(destination), usedNames, forbidden);
            if (cafe != null) {
                places.add(new AiPlannerResponse.PlaceItem(
                        lastDay, 0,
                        cafe.name, cafe.category,
                        cafe.description,
                        cafe.cost, 45, "15:00",
                        cafe.area,
                        cafe.area + " " + cafe.name,
                        null, null, null
                ));
                usedNames.add(normalize(cafe.name));
                added++;
            }
        }

        return added;
    }

    // =========================================================================
    // 체크인 시간 보정
    // =========================================================================

    private boolean fixCheckInTime(List<AiPlannerResponse.PlaceItem> places) {
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem p = places.get(i);
            if (Integer.valueOf(1).equals(p.dayNumber()) && "숙소".equals(p.category())) {
                if ("15:30".equals(p.visitTime())) {
                    return false;
                }
                places.set(i, withVisitTime(p, "15:30"));
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // 체크아웃 시간 보정 (마지막 날 숙소 → 10:30)
    // =========================================================================

    private boolean fixCheckOutTime(List<AiPlannerResponse.PlaceItem> places, int durationNights) {
        int lastDay = Math.max(1, durationNights + 1);
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem p = places.get(i);
            if (Integer.valueOf(lastDay).equals(p.dayNumber()) && "숙소".equals(p.category())) {
                if ("10:30".equals(p.visitTime())) {
                    return false;
                }
                places.set(i, withVisitTime(p, "10:30"));
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // conflict 일출 시간 보정 (일출 장소 → 06:00, 04:00~08:30 윈도우 안)
    // =========================================================================

    private boolean fixSunriseTime(List<AiPlannerResponse.PlaceItem> places) {
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem p = places.get(i);
            String text = normalize(p.name() + " " + p.description() + " "
                    + p.areaHint() + " " + p.searchKeyword());
            if (!text.contains("일출") && !text.contains("향일암")) {
                continue;
            }
            LocalTime t = parseTime(p.visitTime());
            if (t != null && !t.isBefore(LocalTime.of(4, 0)) && !t.isAfter(LocalTime.of(8, 30))) {
                return false;
            }
            places.set(i, withVisitTime(p, "06:00"));
            return true;
        }
        return false;
    }

    // =========================================================================
    // 체크아웃 위치 보정
    // =========================================================================

    private boolean ensureCheckoutFirst(List<AiPlannerResponse.PlaceItem> places, int durationNights) {
        if (durationNights < 1) {
            return false;
        }
        int lastDay = durationNights + 1;
        AiPlannerResponse.PlaceItem checkIn = places.stream()
                .filter(p -> Integer.valueOf(1).equals(p.dayNumber()))
                .filter(p -> "숙소".equals(p.category()))
                .findFirst()
                .orElse(null);
        if (checkIn == null) {
            return false;
        }

        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem p = places.get(i);
            if (Integer.valueOf(lastDay).equals(p.dayNumber()) && "숙소".equals(p.category())) {
                places.set(i, new AiPlannerResponse.PlaceItem(
                        lastDay, 0,
                        checkIn.name(), "숙소",
                        p.description(), 0,
                        p.durationMinutes(), "10:30",
                        checkIn.areaHint(), checkIn.searchKeyword(),
                        checkIn.kakaoPlaceId(), checkIn.lat(), checkIn.lng()
                ));
                return true;
            }
        }

        places.add(new AiPlannerResponse.PlaceItem(
                lastDay, 0,
                checkIn.name(), "숙소",
                checkIn.description(), 0,
                checkIn.durationMinutes(), "10:30",
                checkIn.areaHint(), checkIn.searchKeyword(),
                checkIn.kakaoPlaceId(), checkIn.lat(), checkIn.lng()
        ));
        return true;
    }

    private boolean fixEveningMustIncludeTime(List<AiPlannerResponse.PlaceItem> places, String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            boolean changed = false;
            for (JsonNode node : root.path("must_include")) {
                String raw = node.asText("");
                String normalizedRaw = normalize(raw);
                if (!normalizedRaw.contains("야경") && !normalizedRaw.contains("일몰")) {
                    continue;
                }
                String keyword = normalizeRequiredKeyword(raw);
                if (keyword.isBlank()) {
                    continue;
                }
                for (int i = 0; i < places.size(); i++) {
                    AiPlannerResponse.PlaceItem place = places.get(i);
                    String haystack = normalize(place.name() + " " + place.description() + " "
                            + place.areaHint() + " " + place.searchKeyword());
                    LocalTime visitTime = parseTime(place.visitTime());
                    if (haystack.contains(keyword)
                            && (visitTime == null || visitTime.isBefore(LocalTime.of(18, 0)))) {
                        places.set(i, withVisitTime(place, "19:00"));
                        changed = true;
                        break;
                    }
                }
            }
            return changed;
        } catch (Exception e) {
            log.warn("[AI] repair 야경/일몰 필수 일정 보정 실패 - {}", e.getMessage());
            return false;
        }
    }

    private AiPlannerResponse.PlaceItem withVisitTime(AiPlannerResponse.PlaceItem p, String visitTime) {
        return new AiPlannerResponse.PlaceItem(
                p.dayNumber(), p.orderIndex(),
                p.name(), p.category(),
                p.description(), p.estimatedCost(),
                p.durationMinutes(), visitTime,
                p.areaHint(), p.searchKeyword(),
                p.kakaoPlaceId(), p.lat(), p.lng()
        );
    }

    private AiPlannerResponse.PlaceItem withDescriptionAndVisitTime(
            AiPlannerResponse.PlaceItem p,
            String description,
            String visitTime
    ) {
        return new AiPlannerResponse.PlaceItem(
                p.dayNumber(), p.orderIndex(),
                p.name(), p.category(),
                description, p.estimatedCost(),
                p.durationMinutes(), visitTime,
                p.areaHint(), p.searchKeyword(),
                p.kakaoPlaceId(), p.lat(), p.lng()
        );
    }

    // =========================================================================
    // 체크아웃 숙소명 보정
    // =========================================================================

    private boolean fixCheckoutName(List<AiPlannerResponse.PlaceItem> places, int durationNights) {
        if (durationNights < 1) {
            return false;
        }
        int lastDay = durationNights + 1;

        AiPlannerResponse.PlaceItem checkIn = places.stream()
                .filter(p -> Integer.valueOf(1).equals(p.dayNumber()))
                .filter(p -> "숙소".equals(p.category()))
                .findFirst().orElse(null);
        if (checkIn == null || checkIn.name() == null) {
            return false;
        }

        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem p = places.get(i);
            if (Integer.valueOf(lastDay).equals(p.dayNumber())
                    && "숙소".equals(p.category())
                    && !checkIn.name().equals(p.name())) {
                places.set(i, new AiPlannerResponse.PlaceItem(
                        p.dayNumber(), p.orderIndex(),
                        checkIn.name(), p.category(),
                        p.description(), p.estimatedCost(),
                        p.durationMinutes(), p.visitTime(),
                        p.areaHint(), p.searchKeyword(),
                        p.kakaoPlaceId(), p.lat(), p.lng()
                ));
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // order_index 재정렬
    // =========================================================================

    private void renumberAllDays(List<AiPlannerResponse.PlaceItem> places) {
        Map<Integer, List<AiPlannerResponse.PlaceItem>> byDay = new LinkedHashMap<>();
        for (AiPlannerResponse.PlaceItem p : places) {
            if (p.dayNumber() != null) {
                byDay.computeIfAbsent(p.dayNumber(), k -> new ArrayList<>()).add(p);
            }
        }

        places.clear();
        List<Integer> sortedDays = new ArrayList<>(byDay.keySet());
        sortedDays.sort(Comparator.naturalOrder());

        for (Integer day : sortedDays) {
            List<AiPlannerResponse.PlaceItem> dayPlaces = byDay.get(day);
            dayPlaces.sort(Comparator
                    .comparing(
                            (AiPlannerResponse.PlaceItem p) -> parseTime(p.visitTime()),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            p -> p.orderIndex() == null ? Integer.MAX_VALUE : p.orderIndex()));

            for (int i = 0; i < dayPlaces.size(); i++) {
                AiPlannerResponse.PlaceItem old = dayPlaces.get(i);
                LocalTime visitTime = parseTime(old.visitTime());
                if (i > 0) {
                    LocalTime previousTime = parseTime(places.get(places.size() - 1).visitTime());
                    if (previousTime != null && (visitTime == null || !visitTime.isAfter(previousTime))) {
                        visitTime = previousTime.plusMinutes(30);
                    }
                }
                places.add(new AiPlannerResponse.PlaceItem(
                        old.dayNumber(), i + 1,
                        old.name(), old.category(),
                        old.description(), old.estimatedCost(),
                        old.durationMinutes(), visitTime != null ? visitTime.format(TIME_FMT) : old.visitTime(),
                        old.areaHint(), old.searchKeyword(),
                        old.kakaoPlaceId(), old.lat(), old.lng()
                ));
            }
        }
    }

    // =========================================================================
    // 백업 장소 카탈로그
    // =========================================================================

    private BackupPlace pickBackup(List<BackupPlace> candidates, Set<String> usedNames, Set<String> forbidden) {
        for (BackupPlace bp : candidates) {
            String norm = normalize(bp.name);
            if (!usedNames.contains(norm) && !forbidden.contains(norm)) {
                return bp;
            }
        }
        return null;
    }

    private BackupPlace pickRestaurantBackup(
            List<BackupPlace> candidates,
            Set<String> usedNames,
            Set<String> forbidden,
            FoodConstraints foodConstraints
    ) {
        for (BackupPlace candidate : candidates) {
            String normalizedName = normalize(candidate.name);
            if (!usedNames.contains(normalizedName)
                    && !forbidden.contains(normalizedName)
                    && foodConstraints.allows(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<BackupPlace> backupRestaurants(String destination) {
        String d = normalize(destination);
        if (d.contains("부산")) {
            return List.of(
                    restaurant("밀양순대돼지국밥", 12000, "부산", "부산 대표 돼지국밥 식사입니다.", "고기"),
                    restaurant("합천일류돼지국밥", 12000, "부산", "부산 대표 돼지국밥 식사입니다.", "고기"),
                    restaurant("개미집 서면본점", 15000, "서면", "부산 서면의 대표 낙곱새 식당입니다.", "고기", "해산물", "매운음식"),
                    restaurant("해운대암소갈비집", 20000, "해운대", "해운대 인근 식사입니다.", "고기")
            );
        }
        if (d.contains("제주")) {
            return List.of(
                    restaurant("우진해장국", 10000, "제주시", "제주 대표 해장국 식당입니다.", "고기"),
                    restaurant("올래국수 본점", 12000, "제주시", "제주 대표 고기국수 식당입니다.", "고기"),
                    restaurant("자매국수 본점", 12000, "제주시", "제주 대표 고기국수 식당입니다.", "고기"),
                    restaurant("제주김만복", 15000, "제주시", "제주 김밥 전문점입니다.", "계란")
            );
        }
        if (d.contains("강릉")) {
            return List.of(
                    restaurant("초당할머니순두부", 13000, "초당동", "강릉 대표 순두부 식당입니다."),
                    restaurant("동화가든 본점", 15000, "초당동", "강릉 대표 짬뽕순두부 식당입니다.", "해산물", "매운음식"),
                    restaurant("차현희순두부청국장", 13000, "초당동", "강릉 대표 순두부 식당입니다."),
                    restaurant("교동반점", 10000, "강릉", "강릉의 중식 식당입니다.", "해산물", "매운음식")
            );
        }
        if (d.contains("경주")) {
            return List.of(
                    restaurant("진수성찬", 18000, "교촌마을", "경주의 한식 식당입니다."),
                    restaurant("향적원", 20000, "불국사", "경주의 사찰음식 식당입니다.", "비건"),
                    restaurant("마조르", 15000, "황리단길", "경주의 비건 식당입니다.", "비건"),
                    restaurant("삼릉식당", 12000, "경주", "경주의 한식 식당입니다.")
            );
        }
        if (d.contains("서울")) {
            return List.of(
                    restaurant("광화문미진", 12000, "종로", "서울 도심의 식당입니다."),
                    restaurant("소문난성수감자탕", 12000, "성수", "서울 성수의 식당입니다.", "고기"),
                    restaurant("다운타우너 안국", 15000, "안국", "서울 안국의 식당입니다.", "고기", "유제품")
            );
        }
        if (d.contains("여수")) {
            return List.of(
                    restaurant("로타리식당", 15000, "여수", "여수의 지역 식당입니다.", "해산물"),
                    restaurant("순이네밥상", 15000, "여수", "여수의 지역 식당입니다.", "해산물"),
                    restaurant("꽃돌게장1번가", 25000, "여수", "여수의 지역 식당입니다.", "해산물")
            );
        }
        if (d.contains("가평")) {
            return List.of(
                    restaurant("송원막국수", 12000, "가평", "가평의 지역 식당입니다."),
                    restaurant("동기간", 15000, "가평", "가평의 지역 식당입니다.", "고기"),
                    restaurant("남이섬꼬꼬춘천닭갈비", 15000, "가평", "가평의 지역 식당입니다.", "고기", "매운음식")
            );
        }
        return List.of();
    }

    private List<BackupPlace> backupAttractions(String destination) {
        String d = normalize(destination);
        if (d.contains("부산")) {
            return List.of(
                    bp("민락수변공원", "관광지", 0, "광안리", "바다를 보며 여유롭게 산책합니다."),
                    bp("광안리해수욕장", "관광지", 0, "광안리", "광안리 해변을 따라 산책합니다."),
                    bp("자갈치시장", "관광지", 0, "남포동", "부산의 시장 분위기를 둘러봅니다.")
            );
        }
        if (d.contains("제주")) {
            return List.of(
                    bp("용두암", "관광지", 0, "제주시", "제주 대표 명소를 둘러봅니다."),
                    bp("이호테우해변", "관광지", 0, "제주시", "해변을 따라 여유롭게 산책합니다."),
                    bp("도두봉", "관광지", 0, "제주시", "바다 풍경을 감상합니다.")
            );
        }
        if (d.contains("강릉")) {
            return List.of(
                    bp("경포해변", "관광지", 0, "경포", "경포 해변을 산책합니다."),
                    bp("강문해변", "관광지", 0, "강문", "해변 풍경을 감상합니다."),
                    bp("오죽헌", "관광지", 3000, "강릉", "강릉의 문화 명소를 둘러봅니다.")
            );
        }
        if (d.contains("경주")) {
            return List.of(
                    bp("대릉원", "관광지", 3000, "경주 도심", "신라 시대 유적을 둘러봅니다."),
                    bp("첨성대", "관광지", 0, "경주 도심", "경주의 대표 문화재를 둘러봅니다."),
                    bp("국립경주박물관", "관광지", 0, "경주 도심", "신라 문화 자료를 관람합니다.")
            );
        }
        if (d.contains("서울")) {
            return List.of(
                    bp("서울숲", "관광지", 0, "성수", "도심 속 산책을 즐깁니다."),
                    bp("경복궁", "관광지", 3000, "종로", "서울의 대표 문화 명소를 둘러봅니다."),
                    bp("북촌한옥마을", "관광지", 0, "종로", "한옥 골목을 가볍게 둘러봅니다.")
            );
        }
        return List.of();
    }

    private List<BackupPlace> backupCafes(String destination) {
        String d = normalize(destination);
        if (d.contains("부산")) {
            return List.of(
                    bp("스타벅스 광안리점", "카페", 7000, "광안리", "해변 인근에서 쉬어 가는 카페입니다."),
                    bp("투썸플레이스 부산광안리점", "카페", 7000, "광안리", "해변 인근에서 쉬어 가는 카페입니다.")
            );
        }
        if (d.contains("제주")) {
            return List.of(
                    bp("더클리프", "카페", 7000, "중문", "바다를 보며 쉬어 가는 카페입니다."),
                    bp("우무", "카페", 6000, "제주시", "가볍게 쉬어 가는 디저트 공간입니다.")
            );
        }
        if (d.contains("강릉")) {
            return List.of(
                    bp("보사노바 커피로스터스 강릉점", "카페", 7000, "안목", "해변 인근에서 쉬어 가는 카페입니다."),
                    bp("테라로사 커피공장 강릉본점", "카페", 7000, "강릉", "여유롭게 쉬어 가는 카페입니다.")
            );
        }
        if (d.contains("경주")) {
            return List.of(
                    bp("카페 아덴", "카페", 7000, "보문관광단지", "보문호 인근에서 쉬어 가는 카페입니다."),
                    bp("1894사랑채", "카페", 7000, "황리단길", "한옥 분위기에서 쉬어 가는 카페입니다.")
            );
        }
        if (d.contains("서울")) {
            return List.of(
                    bp("블루보틀 성수 카페", "카페", 7000, "성수", "도심에서 쉬어 가는 카페입니다."),
                    bp("어니언 안국", "카페", 7000, "안국", "도심에서 쉬어 가는 카페입니다.")
            );
        }
        return List.of();
    }

    private List<BackupPlace> backupShopping(String destination) {
        String d = normalize(destination);
        if (d.contains("가평")) {
            return List.of(
                    bp("농협하나로마트 가평군농협자라섬점", "쇼핑", 20000, "가평", "바베큐 재료와 무알코올 음료를 준비합니다."),
                    bp("CU 가평자라섬점", "쇼핑", 12000, "가평", "간단한 음료와 간식을 준비합니다.")
            );
        }
        return List.of();
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private String parseDestination(String conditionsJson) {
        try {
            return objectMapper.readTree(conditionsJson).path("destination").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private int parseDurationNights(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            JsonNode direct = root.path("duration_nights");
            if (direct.isNumber()) {
                return direct.asInt();
            }
            return root.path("dates").path("duration_nights").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private FoodConstraints parseFoodConstraints(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            Set<String> excludedTags = new HashSet<>();
            root.path("must_exclude").forEach(node -> {
                String value = normalize(node.asText(""));
                if (!value.isBlank()) {
                    excludedTags.add(value);
                }
            });

            String joined = normalize(
                    root.path("constraints").toString() + " "
                            + root.path("host_requests").toString() + " "
                            + root.path("must_exclude").toString()
            );
            boolean veganRequired = joined.contains("비건")
                    || joined.contains("채식")
                    || joined.contains("사찰음식")
                    || (joined.contains("고기") && joined.contains("유제품") && joined.contains("계란"));
            if (joined.contains("매운")) {
                excludedTags.add("매운음식");
            }
            return new FoodConstraints(Set.copyOf(excludedTags), veganRequired);
        } catch (Exception e) {
            log.warn("[AI] repair 식사 제약 파싱 실패 - {}", e.getMessage());
            return new FoodConstraints(Set.of(), false);
        }
    }

    private MealPreparationIntent parseMealPreparationIntent(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            String joined = normalize(
                    root.path("constraints").toString() + " "
                            + root.path("preferences").toString() + " "
                            + root.path("host_requests").toString() + " "
                            + root.path("must_include").toString() + " "
                            + root.path("must_exclude").toString() + " "
                            + root.path("conflicts").toString()
            );
            boolean barbecue = joined.contains("바베큐")
                    || joined.contains("바비큐")
                    || joined.contains("bbq");
            boolean groupShopping = barbecue
                    || joined.contains("장보기")
                    || joined.contains("마트")
                    || joined.contains("슈퍼")
                    || (joined.contains("음료") && (joined.contains("술못마심")
                    || joined.contains("술못마시") || joined.contains("술약함") || joined.contains("무알코올")));
            return new MealPreparationIntent(barbecue, groupShopping);
        } catch (Exception e) {
            log.warn("[AI] repair 바베큐/장보기 의도 파싱 실패 - {}", e.getMessage());
            return new MealPreparationIntent(false, false);
        }
    }

    private LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(time, TIME_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean containsAnyNormalized(String value, String... keywords) {
        String normalized = normalize(value);
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private BackupPlace restaurant(String name, int cost, String area, String description, String... tags) {
        return new BackupPlace(name, "식당", cost, area, description, Set.of(tags));
    }

    private BackupPlace bp(String name, String category, int cost, String area, String description) {
        return new BackupPlace(name, category, cost, area, description, Set.of());
    }

    private record BackupPlace(
            String name,
            String category,
            int cost,
            String area,
            String description,
            Set<String> tags
    ) {}

    private record FoodConstraints(Set<String> excludedTags, boolean veganRequired) {
        private boolean allows(BackupPlace candidate) {
            if (veganRequired && !candidate.tags.contains("비건")) {
                return false;
            }
            return candidate.tags.stream().noneMatch(excludedTags::contains);
        }
    }

    private record MealPreparationIntent(boolean hasBarbecueIntent, boolean hasGroupShoppingIntent) {}

    public record RepairResult(AiPlannerResponse.CandidatePlan repairedPlan, List<String> appliedRepairs) {}
}
