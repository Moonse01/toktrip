package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 같은 여행의 A/B 후보가 출발·도착 이동 거점을 다르게 표현하지 않도록 맞춘다.
 *
 * <p>이 서비스는 새로운 역이나 공항을 추측하지 않는다. 사용자가 이동수단을 언급했고
 * 후보 중 하나가 이미 경계 이동 카드를 제안한 경우에만 상대 후보의 같은 위치를 맞춘다.
 * 기존 일정은 유지하고, 이동 카드가 없는 후보에는 경계 이동 카드를 별도로 삽입한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerTransitConsistencyService {

    private static final DateTimeFormatter VISIT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper objectMapper;

    public AlignedPlans align(
            AiPlannerResponse.CandidatePlan planA,
            AiPlannerResponse.CandidatePlan planB,
            String conditionsJson
    ) {
        // 자차/렌트카/도보는 역·터미널·공항을 경유하지 않는다.
        // 플래너봇이 종료 거점(○○역 등)을 넣었으면 양쪽 후보에서 제거한다.
        if (isSelfMobilityWithoutHub(conditionsJson)) {
            return new AlignedPlans(
                    stripHubBoundary(planA),
                    stripHubBoundary(planB)
            );
        }
        if (!hasTransportIntent(conditionsJson)) {
            return new AlignedPlans(planA, planB);
        }

        AiPlannerResponse.CandidatePlan alignedA = planA;
        AiPlannerResponse.CandidatePlan alignedB = planB;

        AiPlannerResponse.PlaceItem sharedStart = firstTransit(alignedA);
        if (sharedStart == null) {
            sharedStart = firstTransit(alignedB);
        }
        if (sharedStart != null) {
            alignedA = replaceBoundary(alignedA, sharedStart, true);
            alignedB = replaceBoundary(alignedB, sharedStart, true);
        }

        AiPlannerResponse.PlaceItem sharedEnd = lastTransit(alignedA);
        if (sharedEnd == null) {
            sharedEnd = lastTransit(alignedB);
        }
        if (sharedEnd != null) {
            alignedA = replaceBoundary(alignedA, sharedEnd, false);
            alignedB = replaceBoundary(alignedB, sharedEnd, false);
        }

        alignedA = deduplicateTransitCards(alignedA);
        alignedB = deduplicateTransitCards(alignedB);

        return new AlignedPlans(alignedA, alignedB);
    }

    private AiPlannerResponse.CandidatePlan replaceBoundary(
            AiPlannerResponse.CandidatePlan plan,
            AiPlannerResponse.PlaceItem sharedTransit,
            boolean start
    ) {
        if (plan == null || plan.places() == null || plan.places().isEmpty()) {
            return plan;
        }

        List<AiPlannerResponse.PlaceItem> places = new ArrayList<>(plan.places());
        int boundaryIndex = boundaryIndex(places, start);
        if (boundaryIndex < 0) {
            return plan;
        }

        AiPlannerResponse.PlaceItem current = places.get(boundaryIndex);
        if (samePlace(current, sharedTransit)) {
            return plan;
        }

        if (!"이동".equals(current.category())) {
            insertBoundaryTransit(places, current, sharedTransit, start);
            log.info("[AI] A/B {} 이동 거점 삽입 - label: {}, {}",
                    start ? "시작" : "종료",
                    plan.label(),
                    sharedTransit.name());
            return copyWithPlaces(plan, places);
        }

        places.set(boundaryIndex, transitAt(
                sharedTransit,
                current.dayNumber(),
                current.orderIndex(),
                current.visitTime()
        ));
        log.info("[AI] A/B {} 이동 거점 교체 - label: {}, {} → {}",
                start ? "시작" : "종료",
                plan.label(),
                current.name(),
                sharedTransit.name());
        return copyWithPlaces(plan, places);
    }

    private void insertBoundaryTransit(
            List<AiPlannerResponse.PlaceItem> places,
            AiPlannerResponse.PlaceItem current,
            AiPlannerResponse.PlaceItem sharedTransit,
            boolean start
    ) {
        int dayNumber = current.dayNumber();
        int orderIndex = start ? current.orderIndex() : current.orderIndex() + 1;
        if (start) {
            for (int i = 0; i < places.size(); i++) {
                AiPlannerResponse.PlaceItem place = places.get(i);
                if (place.dayNumber().equals(dayNumber) && place.orderIndex() >= orderIndex) {
                    places.set(i, copyAt(place, place.orderIndex() + 1));
                }
            }
        }
        places.add(transitAt(
                sharedTransit,
                dayNumber,
                orderIndex,
                boundaryVisitTime(sharedTransit, current, start)
        ));
        places.sort(scheduleComparator());
    }

    private String boundaryVisitTime(
            AiPlannerResponse.PlaceItem sharedTransit,
            AiPlannerResponse.PlaceItem current,
            boolean start
    ) {
        if (start) {
            return sharedTransit.visitTime();
        }
        return visitTimeAfter(sharedTransit.visitTime(), current.visitTime());
    }

    private String visitTimeAfter(String preferredVisitTime, String previousVisitTime) {
        LocalTime previous = parseVisitTime(previousVisitTime);
        if (previous == null) {
            return preferredVisitTime;
        }

        LocalTime preferred = parseVisitTime(preferredVisitTime);
        if (preferred != null && preferred.isAfter(previous)) {
            return preferredVisitTime;
        }
        if (previous.isAfter(LocalTime.of(23, 29))) {
            return previousVisitTime;
        }
        return previous.plusMinutes(30).format(VISIT_TIME_FORMATTER);
    }

    private LocalTime parseVisitTime(String visitTime) {
        if (visitTime == null || visitTime.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(visitTime, VISIT_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private AiPlannerResponse.PlaceItem copyAt(
            AiPlannerResponse.PlaceItem place,
            int orderIndex
    ) {
        return new AiPlannerResponse.PlaceItem(
                place.dayNumber(),
                orderIndex,
                place.name(),
                place.category(),
                place.description(),
                place.estimatedCost(),
                place.durationMinutes(),
                place.visitTime(),
                place.areaHint(),
                place.searchKeyword(),
                place.kakaoPlaceId(),
                place.lat(),
                place.lng()
        );
    }

    private AiPlannerResponse.PlaceItem transitAt(
            AiPlannerResponse.PlaceItem sharedTransit,
            int dayNumber,
            int orderIndex,
            String visitTime
    ) {
        return new AiPlannerResponse.PlaceItem(
                dayNumber,
                orderIndex,
                sharedTransit.name(),
                "이동",
                sharedTransit.description(),
                0,
                sharedTransit.durationMinutes(),
                visitTime,
                sharedTransit.areaHint(),
                sharedTransit.searchKeyword(),
                sharedTransit.kakaoPlaceId(),
                sharedTransit.lat(),
                sharedTransit.lng()
        );
    }

    private AiPlannerResponse.CandidatePlan deduplicateTransitCards(
            AiPlannerResponse.CandidatePlan plan
    ) {
        if (plan == null || plan.places() == null || plan.places().isEmpty()) {
            return plan;
        }

        List<AiPlannerResponse.PlaceItem> sortedPlaces = new ArrayList<>(plan.places());
        sortedPlaces.sort(scheduleComparator());

        List<AiPlannerResponse.PlaceItem> keptPlaces = new ArrayList<>();
        boolean changed = false;

        for (AiPlannerResponse.PlaceItem place : sortedPlaces) {
            int duplicateIndex = duplicateTransitIndex(keptPlaces, place);
            if (duplicateIndex >= 0) {
                keptPlaces.set(duplicateIndex, laterBySchedule(keptPlaces.get(duplicateIndex), place));
                changed = true;
                continue;
            }
            keptPlaces.add(place);
        }

        if (!changed) {
            return plan;
        }

        keptPlaces.sort(scheduleComparator());
        List<AiPlannerResponse.PlaceItem> reindexedPlaces = reindexByDay(keptPlaces);
        log.info("[AI] 중복 이동 카드 제거 - label: {}", plan.label());
        return copyWithPlaces(plan, reindexedPlaces);
    }

    private int duplicateTransitIndex(
            List<AiPlannerResponse.PlaceItem> places,
            AiPlannerResponse.PlaceItem target
    ) {
        if (!isTransit(target)) {
            return -1;
        }
        for (int i = 0; i < places.size(); i++) {
            AiPlannerResponse.PlaceItem place = places.get(i);
            if (isTransit(place)
                    && sameDay(place, target)
                    && samePlace(place, target)) {
                return i;
            }
        }
        return -1;
    }

    private AiPlannerResponse.PlaceItem laterBySchedule(
            AiPlannerResponse.PlaceItem left,
            AiPlannerResponse.PlaceItem right
    ) {
        return scheduleComparator().compare(left, right) <= 0 ? right : left;
    }

    private List<AiPlannerResponse.PlaceItem> reindexByDay(
            List<AiPlannerResponse.PlaceItem> places
    ) {
        List<AiPlannerResponse.PlaceItem> reindexedPlaces = new ArrayList<>();
        Integer currentDay = null;
        int orderIndex = 1;

        for (AiPlannerResponse.PlaceItem place : places) {
            if (!sameDay(currentDay, place.dayNumber())) {
                currentDay = place.dayNumber();
                orderIndex = 1;
            }
            reindexedPlaces.add(copyAt(place, orderIndex));
            orderIndex++;
        }
        return reindexedPlaces;
    }

    private boolean isTransit(AiPlannerResponse.PlaceItem place) {
        return place != null && "이동".equals(place.category());
    }

    private boolean sameDay(AiPlannerResponse.PlaceItem left, AiPlannerResponse.PlaceItem right) {
        return left != null && right != null && sameDay(left.dayNumber(), right.dayNumber());
    }

    private boolean sameDay(Integer leftDay, Integer rightDay) {
        return leftDay == null ? rightDay == null : leftDay.equals(rightDay);
    }

    private int boundaryIndex(List<AiPlannerResponse.PlaceItem> places, boolean start) {
        Comparator<AiPlannerResponse.PlaceItem> comparator = scheduleComparator();

        AiPlannerResponse.PlaceItem boundary = start
                ? places.stream().min(comparator).orElse(null)
                : places.stream().max(comparator).orElse(null);
        return boundary == null ? -1 : places.indexOf(boundary);
    }

    private Comparator<AiPlannerResponse.PlaceItem> scheduleComparator() {
        return Comparator
                .comparing((AiPlannerResponse.PlaceItem place) ->
                        place.dayNumber() == null ? Integer.MAX_VALUE : place.dayNumber())
                .thenComparing(place ->
                        place.orderIndex() == null ? Integer.MAX_VALUE : place.orderIndex());
    }

    private AiPlannerResponse.PlaceItem firstTransit(AiPlannerResponse.CandidatePlan plan) {
        return boundaryTransit(plan, true);
    }

    private AiPlannerResponse.PlaceItem lastTransit(AiPlannerResponse.CandidatePlan plan) {
        return boundaryTransit(plan, false);
    }

    private AiPlannerResponse.PlaceItem boundaryTransit(
            AiPlannerResponse.CandidatePlan plan,
            boolean start
    ) {
        if (plan == null || plan.places() == null || plan.places().isEmpty()) {
            return null;
        }
        int index = boundaryIndex(plan.places(), start);
        if (index < 0) {
            return null;
        }
        AiPlannerResponse.PlaceItem boundary = plan.places().get(index);
        return "이동".equals(boundary.category()) ? boundary : null;
    }

    private AiPlannerResponse.CandidatePlan copyWithPlaces(
            AiPlannerResponse.CandidatePlan plan,
            List<AiPlannerResponse.PlaceItem> places
    ) {
        return new AiPlannerResponse.CandidatePlan(
                plan.label(),
                plan.name(),
                plan.concept(),
                plan.estimatedCostPerPerson(),
                plan.totalDistanceKm(),
                List.copyOf(places)
        );
    }

    private boolean samePlace(AiPlannerResponse.PlaceItem left, AiPlannerResponse.PlaceItem right) {
        return normalize(left.name()).equals(normalize(right.name()));
    }

    private boolean hasTransportIntent(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            String joined = normalize(
                    root.path("transport").asText("") + " "
                            + root.path("constraints") + " "
                            + root.path("host_requests")
            );
            return joined.contains("ktx")
                    || joined.contains("기차")
                    || joined.contains("역")
                    || joined.contains("공항")
                    || joined.contains("항공")
                    || joined.contains("비행기")
                    || joined.contains("버스")
                    || joined.contains("터미널")
                    || joined.contains("렌트");
        } catch (Exception e) {
            log.warn("[AI] 이동 거점 일관성 조건 파싱 실패 - {}", e.getMessage());
            return false;
        }
    }

    /**
     * 자차/렌트카/도보 이동이면서 KTX·공항을 따로 명시하지 않은 경우 true.
     * 이런 일정은 역·터미널·공항 종료 거점이 부적절하다.
     */
    private boolean isSelfMobilityWithoutHub(String conditionsJson) {
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            String transport = normalize(root.path("transport").asText(""));
            boolean selfMobility = transport.contains("렌트")
                    || transport.contains("자차")
                    || transport.contains("자가")
                    || transport.contains("도보");
            if (!selfMobility) {
                return false;
            }
            // 대화에서 KTX·기차·공항을 명시했다면 거점이 정당할 수 있어 제거하지 않는다.
            String joined = normalize(
                    root.path("constraints") + " " + root.path("host_requests"));
            boolean explicitHub = joined.contains("ktx")
                    || joined.contains("기차")
                    || joined.contains("공항")
                    || joined.contains("항공")
                    || joined.contains("비행기")
                    || joined.contains("터미널");
            return !explicitHub;
        } catch (Exception e) {
            log.warn("[AI] 자차/렌트 판별 조건 파싱 실패 - {}", e.getMessage());
            return false;
        }
    }

    /**
     * 일정의 시작·끝에 놓인 역·터미널·공항 이동 카드를 제거한다.
     * 자차/렌트 일정에서 플래너봇이 잘못 삽입한 종료 거점을 걷어낸다.
     */
    private AiPlannerResponse.CandidatePlan stripHubBoundary(
            AiPlannerResponse.CandidatePlan plan
    ) {
        if (plan == null || plan.places() == null || plan.places().isEmpty()) {
            return plan;
        }

        List<AiPlannerResponse.PlaceItem> places = new ArrayList<>(plan.places());
        places.sort(scheduleComparator());
        boolean changed = false;

        if (!places.isEmpty()) {
            AiPlannerResponse.PlaceItem last = places.get(places.size() - 1);
            if (isTransit(last) && isTransitHub(last.name())) {
                places.remove(places.size() - 1);
                changed = true;
            }
        }
        if (!places.isEmpty()) {
            AiPlannerResponse.PlaceItem first = places.get(0);
            if (isTransit(first) && isTransitHub(first.name())) {
                places.remove(0);
                changed = true;
            }
        }

        if (!changed) {
            return plan;
        }

        List<AiPlannerResponse.PlaceItem> reindexedPlaces = reindexByDay(places);
        log.info("[AI] 자차/렌트 일정 - 교통허브 경계 거점 제거: label: {}", plan.label());
        return copyWithPlaces(plan, reindexedPlaces);
    }

    private boolean isTransitHub(String name) {
        String normalized = normalize(name);
        return normalized.endsWith("역")
                || normalized.contains("터미널")
                || normalized.contains("공항");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public record AlignedPlans(
            AiPlannerResponse.CandidatePlan planA,
            AiPlannerResponse.CandidatePlan planB
    ) {}
}
