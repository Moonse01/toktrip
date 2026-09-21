package com.triplan.triplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.dto.AiPlannerResponse;
import com.triplan.triplan.entity.Place;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.exception.AiGenerationException;
import com.triplan.triplan.infra.OpenAiClient;
import com.triplan.triplan.infra.PromptTemplates;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * AI 일정 생성 비동기 서비스.
 *
 * <p>{@code @Async}가 프록시 기반이므로 PlanService와 분리.
 * PlanService에서 이 서비스의 {@link #generateAsync(Long)}를 호출하면
 * 별도 스레드에서 AI 호출 → DB 저장이 진행된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenerationService {

    private final PlanRepository planRepository;
    private final PlanCandidateRepository planCandidateRepository;
    private final PlaceRepository placeRepository;
    private final OpenAiClient openAiClient;
    private final PlaceValidationService placeValidationService;
    private final ParserNormalizationService parserNormalizationService;
    private final PlannerValidationService plannerValidationService;
    private final PlannerFallbackService plannerFallbackService;
    private final PlannerRepairService plannerRepairService;
    private final PlannerTransitConsistencyService plannerTransitConsistencyService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private static final int API_MAX_RETRY = 3;
    private static final int PLANNER_MAX_ATTEMPTS = 2;
    private static final int PARSER_MAX_TOKENS = 1600;
    private static final int PLANNER_MAX_TOKENS = 4000;
    private static final List<String> DINING_SEARCH_KEYWORDS = List.of(
            "짬뽕순두부", "초당순두부", "돼지국밥", "고기국수", "사찰음식",
            "순두부", "닭갈비", "막국수", "흑돼지", "밀면", "게장", "갈비",
            "두부", "국밥", "찌개", "전골", "냉면", "비빔밥", "횟집", "회",
            "해장국", "칼국수", "삼합", "장어", "불고기", "떡갈비", "김밥", "한정식"
    );
    private static final List<String> ACCOMMODATION_SEARCH_KEYWORDS = List.of(
            "글램핑", "캠핑", "펜션", "풀빌라", "호텔", "리조트", "게스트하우스"
    );
    private static final List<String> SHOPPING_SEARCH_KEYWORDS = List.of(
            "하나로마트", "마트", "슈퍼", "편의점"
    );
    private static final List<String> ACTIVITY_SEARCH_KEYWORDS = List.of(
            "서핑", "서프", "해양스포츠", "수상스포츠", "레저"
    );
    private static final List<String> SIGHT_SEARCH_KEYWORDS = List.of(
            "해수욕장", "해변", "해안", "바다", "호수", "산책로", "둘레길", "수목원",
            "전망대", "전망", "공원", "숲", "계곡", "폭포", "등대", "항구", "시장",
            "케이블카", "출렁다리", "정원", "생태", "습지", "동굴", "사찰", "유적"
    );
    private static final List<String> GYEONGJU_VEGETARIAN_RESTAURANT_NAMES = List.of(
            "마조르", "여기당", "다유", "쑥부쟁이", "향적원", "연화바루", "소소", "발우공양"
    );
    @Value("${openai.parser.model:gpt-4o-mini}")
    private String parserModel;

    @Value("${openai.planner.model:gpt-4o-mini}")
    private String plannerModel;

    /**
     * 비동기로 AI 일정을 생성한다.
     *
     * <p>호출 시점에 Plan.status는 이미 AI_GENERATING이어야 한다.
     * 성공 시 AI_DONE, 실패 시 DRAFT로 롤백.
     */
    @Async
    @Transactional
    public void generateAsync(Long planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planId));

        String chatLog = plan.getChatLogText();
        String mission = plan.getMission();

        try {
            // ① 1차 파서봇: 카톡 대화 → 조건 JSON (retry 포함)
            log.info("[AI] 1차 파서봇 호출 시작 - planId: {}, model: {}", planId, parserModel);
            String rawConditionsJson = callWithRetry(
                    () -> openAiClient.chat(
                            parserModel,
                            PromptTemplates.PARSER_SYSTEM,
                            PromptTemplates.parserUserPrompt(mission, chatLog),
                            0.1,
                            PARSER_MAX_TOKENS
                    ),
                    "1차 파서봇"
            );
            log.info("[AI] 1차 파서봇 완료 - planId: {}", planId);

            String conditionsJson = parserNormalizationService.normalize(rawConditionsJson, mission, chatLog);
            log.info("[AI] 1차 파서봇 normalization 완료 - planId: {}", planId);
            try {
                var conflictsNode = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(conditionsJson).path("conflicts");
                log.info("[AI][DEBUG] 파싱된 conflicts - planId: {}, conflicts: {}", planId, conflictsNode);
            } catch (Exception ignore) { /* 디버그 로그 실패는 무시 */ }

            // ② 2-A 플래너봇: 조건 JSON → A안 단일 JSON (retry 포함)
            log.info("[AI] 2-A 플래너봇 호출 시작 - planId: {}, model: {}", planId, plannerModel);
            AiPlannerResponse.CandidatePlan planA = callPlannerWithValidation(
                    PromptTemplates.PLANNER_A_SYSTEM,
                    PromptTemplates.plannerAUserPrompt(conditionsJson),
                    0.2,
                    "2-A 플래너봇",
                    "A",
                    conditionsJson,
                    List.of()
            );
            log.info("[AI] 2-A 플래너봇 완료 - planId: {}", planId);

            // ③ 2-B 플래너봇: A안 장소 목록을 금지 목록으로 전달해 B안 단일 JSON 생성
            log.info("[AI] 2-B 플래너봇 호출 시작 - planId: {}, model: {}", planId, plannerModel);
            List<String> forbiddenPlaceNames = extractForbiddenPlaceNames(planA);
            AiPlannerResponse.CandidatePlan planB = callPlannerWithValidation(
                    PromptTemplates.PLANNER_B_SYSTEM,
                    PromptTemplates.plannerBUserPrompt(
                            conditionsJson, forbiddenPlaceNames,
                            planA.name(), planA.concept()
                    ),
                    0.2,
                    "2-B 플래너봇",
                    "B",
                    conditionsJson,
                    forbiddenPlaceNames
            );
            log.info("[AI] 2-B 플래너봇 완료 - planId: {}", planId);
            PlannerTransitConsistencyService.AlignedPlans alignedPlans =
                    plannerTransitConsistencyService.align(planA, planB, conditionsJson);
            planA = alignedPlans.planA();
            planB = alignedPlans.planB();

            // ④ Plan 정보 업데이트 (destination, 날짜 등)
            updatePlanInfoFromConditions(plan, conditionsJson);

            // ⑤ A/B안 DB 저장
            String splitReason = buildSplitReason(conditionsJson);
            boolean allowGenericRestaurantReplacement = !hasDietaryRestriction(conditionsJson);
            saveCandidateAndPlaces(plan, normalizeLabel(planA, "A"), splitReason, allowGenericRestaurantReplacement);
            saveCandidateAndPlaces(plan, normalizeLabel(planB, "B"), splitReason, allowGenericRestaurantReplacement);

            // ⑥ 완료
            plan.updateStatus(PlanStatus.AI_DONE);
            planRepository.flush();
            log.info("[AI] 후보 저장 및 AI_DONE 전환 완료 - planId: {}, planTitle: {}",
                    planId,
                    plan.getTitle());
            log.info("[AI] 일정 생성 완료 - planId: {}", planId);

        } catch (Exception e) {
            log.error("[AI] 생성 실패 - planId: {}", planId, e);
            scheduleFailedGenerationRecovery(planId);
            markCurrentGenerationTransactionRollbackOnly(planId);
            // @Async이므로 예외를 던져도 호출자에게 전달되지 않음 — 로그로 기록
        }
    }

    private void scheduleFailedGenerationRecovery(Long planId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recoverFailedGenerationSafely(planId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                recoverFailedGenerationSafely(planId);
            }
        });
    }

    private void recoverFailedGenerationSafely(Long planId) {
        try {
            recoverFailedGeneration(planId);
        } catch (Exception recoveryError) {
            log.error("[AI] 생성 실패 복구도 실패했습니다 - planId: {}", planId, recoveryError);
        }
    }

    private void recoverFailedGeneration(Long planId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            cleanupGeneratedData(planId);
            restoreDraftStatus(planId);
        });
    }

    private void markCurrentGenerationTransactionRollbackOnly(Long planId) {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception rollbackMarkError) {
            log.warn("[AI] 생성 실패 트랜잭션 rollback-only 표시를 건너뜁니다 - planId: {}, reason: {}",
                    planId, rollbackMarkError.getMessage());
        }
    }

    private void cleanupGeneratedData(Long planId) {
        placeRepository.deleteNonFinalByPlanId(planId);
        planCandidateRepository.deleteNonFinalByPlanId(planId);
        log.info("[AI] 생성 실패 데이터 정리 완료 - planId: {}", planId);
    }

    private void restoreDraftStatus(Long planId) {
        // delete 쿼리의 clearAutomatically=true가 영속성 컨텍스트를 비운다.
        // 따라서 기존 plan 객체를 재사용하지 않고 다시 조회해 실패 상태를 저장한다.
        Plan failedPlan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planId));
        failedPlan.updateStatus(PlanStatus.DRAFT);
        planRepository.saveAndFlush(failedPlan);
        log.warn("[AI] 생성 실패 플랜을 DRAFT로 복구했습니다 - planId: {}", planId);
    }

    // =========================================================================
    // Retry 로직
    // =========================================================================

    @FunctionalInterface
    private interface AiCall {
        String execute();
    }

    /**
     * 파서 등 일반 AI 호출을 최대 API_MAX_RETRY회 재시도한다.
     * 모든 시도 실패 시 AiGenerationException을 던진다.
     */
    private String callWithRetry(AiCall call, String stepName) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= API_MAX_RETRY; attempt++) {
            try {
                return call.execute();
            } catch (Exception e) {
                lastException = e;
                log.warn("[AI] {} 실패 ({}회차/{}) - {}", stepName, attempt, API_MAX_RETRY, e.getMessage());
                if (attempt < API_MAX_RETRY) {
                    try {
                        Thread.sleep(2000L * attempt); // 2초, 4초 대기 후 재시도
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AiGenerationException(stepName + " 재시도 중 인터럽트", ie);
                    }
                }
            }
        }

        throw new AiGenerationException(
                stepName + " " + API_MAX_RETRY + "회 모두 실패: " + lastException.getMessage(),
                lastException
        );
    }

    private AiPlannerResponse.CandidatePlan callPlannerWithValidation(
            String systemPrompt,
            String baseUserPrompt,
            double temperature,
            String stepName,
            String expectedLabel,
            String conditionsJson,
            List<String> forbiddenPlaceNames
    ) {
        Exception lastException = null;
        String userPrompt = baseUserPrompt;

        for (int attempt = 1; attempt <= PLANNER_MAX_ATTEMPTS; attempt++) {
            try {
                String responseJson = openAiClient.chat(
                        plannerModel,
                        systemPrompt,
                        userPrompt,
                        temperature,
                        PLANNER_MAX_TOKENS
                );
                AiPlannerResponse.CandidatePlan candidatePlan =
                        objectMapper.readValue(responseJson, AiPlannerResponse.CandidatePlan.class);
                candidatePlan = normalizeLabel(candidatePlan, expectedLabel);

                PlannerValidationService.ValidationResult validation = plannerValidationService.validate(
                        candidatePlan,
                        expectedLabel,
                        conditionsJson,
                        forbiddenPlaceNames
                );
                if (validation.valid()) {
                    return candidatePlan;
                }

                String retryResponseJson = responseJson;
                PlannerValidationService.ValidationResult retryValidation = validation;
                PlannerRepairService.RepairResult repairResult = plannerRepairService.tryRepair(
                        candidatePlan, conditionsJson, validation, forbiddenPlaceNames
                );
                if (!repairResult.appliedRepairs().isEmpty()) {
                    PlannerValidationService.ValidationResult revalidation = plannerValidationService.validate(
                            repairResult.repairedPlan(), expectedLabel, conditionsJson, forbiddenPlaceNames
                    );
                    if (revalidation.valid()) {
                        log.info("[AI] {} 기계 보정으로 검증 통과 ({}회차) - 보정: {}",
                                stepName, attempt, repairResult.appliedRepairs());
                        return repairResult.repairedPlan();
                    }
                    log.info("[AI] {} 기계 보정 후에도 검증 실패 ({}회차) - 남은 사유: {}",
                            stepName, attempt, revalidation.reasons());
                    retryResponseJson = objectMapper.writeValueAsString(repairResult.repairedPlan());
                    retryValidation = revalidation;
                }

                lastException = new AiGenerationException(stepName + " 검증 실패: " + retryValidation.reasons());
                log.warn("[AI] {} 검증 실패 ({}회차/{}) - {}",
                        stepName, attempt, PLANNER_MAX_ATTEMPTS, retryValidation.reasons());
                userPrompt = buildPlannerRepairPrompt(
                        conditionsJson,
                        retryResponseJson,
                        retryValidation,
                        expectedLabel,
                        forbiddenPlaceNames
                );
            } catch (Exception e) {
                lastException = e;
                log.warn("[AI] {} 실패 ({}회차/{}) - {}", stepName, attempt, PLANNER_MAX_ATTEMPTS, e.getMessage());
            }

            if (attempt < PLANNER_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AiGenerationException(stepName + " 재시도 중 인터럽트", ie);
                }
            }
        }
        log.warn("[AI] {} {}회 검증 실패, fallback 후보 생성을 시작합니다.", stepName, PLANNER_MAX_ATTEMPTS);
        AiPlannerResponse.CandidatePlan fallback = plannerFallbackService.create(
                expectedLabel,
                conditionsJson,
                forbiddenPlaceNames
        );
        PlannerValidationService.ValidationResult fallbackValidation = plannerValidationService.validate(
                fallback,
                expectedLabel,
                conditionsJson,
                forbiddenPlaceNames
        );
        if (fallbackValidation.valid()) {
            log.warn("[AI] {} fallback 후보를 사용합니다.", stepName);
            return fallback;
        }

        // must_include만 미충족이면 DRAFT 크래시 대신 fallback을 그대로 사용한다 (graceful degrade).
        // 특이한 표현으로 특정 must_include를 못 맞춰도, 유효한 플랜은 내보내야 데모가 끊기지 않는다.
        boolean onlyMustIncludeUnmet = !fallbackValidation.reasons().isEmpty()
                && fallbackValidation.reasons().stream().allMatch(reason -> reason.contains("must_include"));
        if (onlyMustIncludeUnmet) {
            log.warn("[AI] {} fallback이 must_include만 미충족 - 데모 안정성 위해 fallback 사용 (미반영: {})",
                    stepName, fallbackValidation.reasons());
            return fallback;
        }

        throw new AiGenerationException(
                stepName + " " + PLANNER_MAX_ATTEMPTS + "회 및 fallback 검증 실패: "
                        + fallbackValidation.reasons() + " / 마지막 AI 오류: " + lastException.getMessage(),
                lastException
        );
    }

    private String buildPlannerRepairPrompt(
            String conditionsJson,
            String invalidResponseJson,
            PlannerValidationService.ValidationResult validation,
            String expectedLabel,
            List<String> forbiddenPlaceNames
    ) {
        String forbiddenPlaces = forbiddenPlaceNames == null || forbiddenPlaceNames.isEmpty()
                ? "없음"
                : "- " + String.join("\n- ", forbiddenPlaceNames);

        return """
                이전 플래너 응답은 백엔드 검증에 실패했습니다.
                아래 실패 사유를 모두 고쳐서 같은 스키마의 JSON 객체 1개만 다시 출력하세요.
                설명, 마크다운, 코드블록은 절대 출력하지 마세요.

                [반드시 유지할 조건]
                - label은 "%s"입니다.
                - 여행 조건 JSON의 destination, duration_nights, budget, transport, accommodation, constraints, must_include, must_exclude를 그대로 따르세요.
                - duration_nights=1이면 day_number는 1, 2만 사용합니다.
                - duration_nights=2이면 day_number는 1, 2, 3만 사용합니다.
                - 숙박 여행이면 첫날 실제 숙소 체크인과 마지막 날 order_index=1의 같은 숙소 체크아웃이 반드시 있어야 합니다.
                - 마지막 날은 체크아웃 이후 카페/관광지/식당 중 최소 2개를 더 포함하세요.
                - total_budget, split_theme은 출력하지 마세요.
                - kakao_place_id, lat, lng는 항상 null입니다.

                [A안에서 이미 사용한 장소명 - B안이면 사용 금지]
                %s

                [검증 실패 사유]
                %s

                [여행 조건 JSON]
                %s

                [수정 대상 JSON]
                %s
                """.formatted(
                expectedLabel,
                forbiddenPlaces,
                validation.toPromptFeedback(),
                conditionsJson,
                invalidResponseJson
        );
    }

    private AiPlannerResponse.CandidatePlan normalizeLabel(
            AiPlannerResponse.CandidatePlan candidatePlan,
            String label
    ) {
        String normalizedName = sanitizeCandidateName(candidatePlan.name());
        if (label.equals(candidatePlan.label())
                && Objects.equals(normalizedName, candidatePlan.name())) {
            return candidatePlan;
        }
        return new AiPlannerResponse.CandidatePlan(
                label,
                normalizedName,
                candidatePlan.concept(),
                candidatePlan.estimatedCostPerPerson(),
                candidatePlan.totalDistanceKm(),
                candidatePlan.places()
        );
    }

    static String sanitizeCandidateName(String name) {
        if (name == null) {
            return null;
        }
        String sanitized = name
                .replaceAll("\\s*\\d+\\s*박\\s*\\d+\\s*일\\s*", " ")
                .replaceAll("\\s*당일\\s*치기\\s*", " ")
                .replaceAll("\\s{2,}", " ")
                .trim()
                .replaceAll("^[·ㆍ,\\-/\\s]+|[·ㆍ,\\-/\\s]+$", "");
        return sanitized.isBlank() ? name.trim() : sanitized;
    }

    private List<String> extractForbiddenPlaceNames(AiPlannerResponse.CandidatePlan planA) {
        if (planA == null || planA.places() == null) {
            return List.of();
        }
        return planA.places().stream()
                .filter(p -> p.name() != null && !p.name().isBlank())
                .filter(p -> !isSharedAllowedCategory(p.category()))
                .map(AiPlannerResponse.PlaceItem::name)
                .distinct()
                .toList();
    }

    private boolean isSharedAllowedCategory(String category) {
        return "숙소".equals(category) || "이동".equals(category);
    }

    private boolean hasDietaryRestriction(String conditionsJson) {
        try {
            var conditions = objectMapper.readTree(conditionsJson);
            if (conditions.path("must_exclude").isArray() && !conditions.path("must_exclude").isEmpty()) {
                return true;
            }
            String normalized = conditionsJson == null
                    ? ""
                    : conditionsJson.toLowerCase().replaceAll("\\s+", "");
            return normalized.contains("알레르기")
                    || normalized.contains("비건")
                    || normalized.contains("채식")
                    || normalized.contains("못먹");
        } catch (Exception e) {
            log.warn("[AI] 식이 제한 여부 파싱 실패, 범용 식당 교체를 비활성화합니다.", e);
            return true;
        }
    }

    // =========================================================================
    // DB 저장 헬퍼
    // =========================================================================

    private void updatePlanInfoFromConditions(Plan plan, String conditionsJson) {
        try {
            var conditions = objectMapper.readTree(conditionsJson);
            String destination = conditions.path("destination").asText(null);
            String startDateStr = textPath(conditions, "start_date");
            String endDateStr = textPath(conditions, "end_date");
            if (startDateStr == null) {
                startDateStr = textPath(conditions.path("dates"), "start");
            }
            if (endDateStr == null) {
                endDateStr = textPath(conditions.path("dates"), "end");
            }

            int participantCount = conditions.path("participant_count").asInt(0);
            if (participantCount == 0) {
                participantCount = conditions.path("participants").path("count").asInt(0);
            }

            LocalDate startDate = (startDateStr != null && !"null".equals(startDateStr))
                    ? LocalDate.parse(startDateStr) : null;
            LocalDate endDate = (endDateStr != null && !"null".equals(endDateStr))
                    ? LocalDate.parse(endDateStr) : null;

            // 위트있는 제목 생성: 핵심 키워드 + 목적지 + 기간
            String themeKeyword = extractThemeKeyword(conditions);
            String durationTag = buildDurationTag(startDate, endDate, conditions);
            String dest = destination != null ? destination : "여행";
            String title = themeKeyword != null
                    ? themeKeyword + " " + dest + (durationTag != null ? " " + durationTag : "")
                    : dest + (durationTag != null ? " " + durationTag : "");

            // 요약 생성: 쟁점이나 핵심 취향 기반
            String summary = buildPlanSummary(conditions, destination);
            plan.updateSummary(summary);

            plan.updateDemoInfo(title, destination, startDate, endDate,
                    participantCount > 0 ? participantCount : null);
        } catch (Exception e) {
            log.warn("[AI] 조건 JSON에서 Plan 정보 업데이트 실패 (무시하고 진행)", e);
        }
    }

    /**
     * conditions에서 핵심 테마 키워드를 추출한다.
     * conflicts > preferences > host_requests 순으로 우선순위.
     */
    private String extractThemeKeyword(com.fasterxml.jackson.databind.JsonNode conditions) {
        // 1. conflicts가 있으면 쟁점 주제를 활용
        var conflicts = conditions.path("conflicts");
        if (conflicts.isArray() && !conflicts.isEmpty()) {
            String topic = textPath(conflicts.get(0), "topic");
            if (topic != null) {
                // "한라산 등반 vs 오름" → "한라산 vs 오름"  (짧게)
                if (topic.contains(" vs ")) {
                    return topic.length() > 20 ? topic.substring(0, 20) : topic;
                }
                return topic.length() > 15 ? topic.substring(0, 15) : topic;
            }
        }

        // 2. preferences에서 테마 키워드 추출
        var preferences = conditions.path("preferences");
        if (preferences.isArray() && !preferences.isEmpty()) {
            for (var pref : preferences) {
                String text = pref.asText("");
                for (String keyword : List.of("맛집", "식도락", "힐링", "카페", "바다", "자연",
                        "야경", "액티비티", "문화", "역사", "쇼핑", "미식", "산책",
                        "오름", "한옥", "전통", "해변", "서핑", "등산", "캠핑")) {
                    if (text.contains(keyword)) {
                        return keyword + " 탐방";
                    }
                }
            }
            // 첫 번째 preference를 짧게 사용
            String first = preferences.get(0).asText("");
            if (!first.isBlank() && first.length() <= 10) {
                return first;
            }
        }

        return null;
    }

    private String buildDurationTag(LocalDate startDate, LocalDate endDate,
                                    com.fasterxml.jackson.databind.JsonNode conditions) {
        if (startDate != null && endDate != null) {
            int nights = (int) startDate.until(endDate).getDays();
            if (nights == 0) return "당일치기";
            return nights + "박 " + (nights + 1) + "일";
        }
        int durationNights = conditions.path("dates").path("duration_nights").asInt(-1);
        if (durationNights == 0) return "당일치기";
        if (durationNights > 0) return durationNights + "박 " + (durationNights + 1) + "일";
        return null;
    }

    private String buildPlanSummary(com.fasterxml.jackson.databind.JsonNode conditions, String destination) {
        var conflicts = conditions.path("conflicts");
        if (conflicts.isArray() && !conflicts.isEmpty()) {
            var first = conflicts.get(0);
            String topic = textPath(first, "topic");
            var opinions = first.path("opinions");
            if (topic != null && opinions.isArray() && opinions.size() >= 2) {
                String wantA = textPath(opinions.get(0), "wants");
                String wantB = textPath(opinions.get(1), "wants");
                if (wantA != null && wantB != null) {
                    return wantA + " vs " + wantB + ", 어떤 쪽이 더 좋을까?";
                }
            }
            if (topic != null) {
                return topic + " 의견이 갈려서 두 가지 안을 준비했어요.";
            }
        }

        // conflicts 없으면 preferences 기반 요약
        var preferences = conditions.path("preferences");
        if (preferences.isArray() && preferences.size() >= 2) {
            return preferences.get(0).asText("") + ", " + preferences.get(1).asText("") + " 중심으로 구성했어요.";
        }
        if (preferences.isArray() && !preferences.isEmpty()) {
            return preferences.get(0).asText("") + " 중심의 " + (destination != null ? destination : "") + " 여행이에요.";
        }

        return destination != null ? destination + " 여행 일정이에요." : "여행 일정이에요.";
    }

    private void saveCandidateAndPlaces(
            Plan plan,
            AiPlannerResponse.CandidatePlan candidatePlan,
            String splitReason,
            boolean allowGenericRestaurantReplacement
    ) {
        PlanCandidate candidate = planCandidateRepository.save(PlanCandidate.builder()
                .plan(plan)
                .label(candidatePlan.label())
                .name(candidatePlan.name())
                .concept(candidatePlan.concept())
                .splitReason(splitReason)
                .estimatedCostPerPerson(0)
                .totalDistanceKm(candidatePlan.totalDistanceKm())
                .version(1)
                .build());

        int lastDay = candidatePlan.places().stream()
                .map(AiPlannerResponse.PlaceItem::dayNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
        List<Place> places = new ArrayList<>();
        Set<String> usedKakaoPlaceIds = new HashSet<>();
        for (AiPlannerResponse.PlaceItem item : candidatePlan.places()) {
            toPlace(
                    candidate,
                    item,
                    plan.getDestination(),
                    lastDay,
                    usedKakaoPlaceIds,
                    allowGenericRestaurantReplacement
            ).ifPresent(place -> {
                places.add(place);
                rememberKakaoPlaceId(place, usedKakaoPlaceIds);
            });
        }

        ensureRequiredAccommodationIsVerified(places, lastDay);
        ensureRequiredDiningIsVerified(places, lastDay);
        normalizeOrderIndexes(places);
        int recalculatedEstimatedCostPerPerson = places.stream()
                .mapToInt(p -> p.getEstimatedCost() != null ? p.getEstimatedCost() : 0)
                .sum();
        candidate.updateEstimatedCostPerPerson(recalculatedEstimatedCostPerPerson);
        placeRepository.saveAll(places);
    }

    private Optional<Place> toPlace(
            PlanCandidate candidate,
            AiPlannerResponse.PlaceItem item,
            String destination,
            int lastDay,
            Set<String> usedKakaoPlaceIds,
            boolean allowGenericRestaurantReplacement
    ) {
        PlaceValidationService.ValidatedPlace validated = placeValidationService
                .validate(item, destination)
                .orElse(null);
        if (isDuplicateNonSharedPlace(item.category(), validated, usedKakaoPlaceIds)) {
            validated = null;
        }

        // 카페, 숙소, 식당은 같은 권역에서 의미를 보존한 실제 장소를 찾은 경우에만 대체한다.
        if ((validated == null || !validated.kakaoVerified())
                && isReplaceableCategory(item.category())) {
            PlaceValidationService.ValidatedPlace replacement = tryFindReplacement(
                    item,
                    destination,
                    usedKakaoPlaceIds,
                    allowGenericRestaurantReplacement
            );
            if (replacement != null && replacement.kakaoVerified()) {
                log.info("[AI] 카카오 미검증 장소 교체: {} → {}", item.name(), replacement.name());
                return Optional.of(Place.builder()
                        .candidate(candidate)
                        .dayNumber(item.dayNumber())
                        .orderIndex(item.orderIndex())
                        .name(replacement.name())
                        .category(item.category())
                        .description(normalizedDescription(item, replacement.name(), lastDay))
                        .estimatedCost(normalizedCost(item))
                        .durationMinutes(item.durationMinutes())
                        .visitTime(parseVisitTime(item.visitTime()))
                        .lat(replacement.lat())
                        .lng(replacement.lng())
                        .kakaoPlaceId(replacement.kakaoPlaceId())
                        .build());
            }
        }

        if (shouldExcludeUnverifiedPlace(item.category(), validated)) {
            log.warn("[AI] 카카오 미검증 장소 제외 - name: {}, category: {}, area: {}",
                    item.name(), item.category(), item.areaHint());
            return Optional.empty();
        }

        String name = validated != null && validated.name() != null ? validated.name() : item.name();
        BigDecimal lat = null;
        BigDecimal lng = null;
        if (validated != null && isValidCoordinate(validated.lat(), validated.lng(), destination)) {
            lat = validated.lat();
            lng = validated.lng();
        } else if (isValidCoordinate(item.lat(), item.lng(), destination)) {
            lat = item.lat();
            lng = item.lng();
        }
        String kakaoPlaceId = validated != null && validated.kakaoPlaceId() != null
                ? validated.kakaoPlaceId()
                : item.kakaoPlaceId();

        return Optional.of(Place.builder()
                .candidate(candidate)
                .dayNumber(item.dayNumber())
                .orderIndex(item.orderIndex())
                .name(name)
                .category(item.category())
                .description(normalizedDescription(item, name, lastDay))
                .estimatedCost(normalizedCost(item))
                .durationMinutes(item.durationMinutes())
                .visitTime(parseVisitTime(item.visitTime()))
                .lat(lat)
                .lng(lng)
                .kakaoPlaceId(kakaoPlaceId)
                .build());
    }

    static boolean shouldExcludeUnverifiedPlace(
            String category,
            PlaceValidationService.ValidatedPlace validated
    ) {
        if ("이동".equals(category)) {
            return false;
        }
        return validated == null || !validated.kakaoVerified();
    }

    private void ensureRequiredAccommodationIsVerified(List<Place> places, int lastDay) {
        if (lastDay <= 1) {
            return;
        }
        List<Place> accommodations = places.stream()
                .filter(place -> "숙소".equals(place.getCategory()))
                .toList();
        boolean hasVerifiedCheckIn = accommodations.stream()
                .anyMatch(place -> Integer.valueOf(1).equals(place.getDayNumber())
                        && hasKakaoCoordinate(place));
        boolean hasVerifiedCheckOut = accommodations.stream()
                .anyMatch(place -> Integer.valueOf(lastDay).equals(place.getDayNumber())
                        && Integer.valueOf(1).equals(place.getOrderIndex())
                        && hasKakaoCoordinate(place));
        if (!hasVerifiedCheckIn || !hasVerifiedCheckOut) {
            throw new AiGenerationException("카카오맵에서 검증 가능한 숙소 체크인/체크아웃을 구성하지 못했습니다.");
        }
    }

    private boolean hasKakaoCoordinate(Place place) {
        return place.getKakaoPlaceId() != null
                && !place.getKakaoPlaceId().isBlank()
                && place.getLat() != null
                && place.getLng() != null;
    }

    private boolean isDuplicateNonSharedPlace(
            String category,
            PlaceValidationService.ValidatedPlace validated,
            Set<String> usedKakaoPlaceIds
    ) {
        return !isSharedAllowedCategory(category)
                && validated != null
                && validated.kakaoPlaceId() != null
                && usedKakaoPlaceIds.contains(validated.kakaoPlaceId());
    }

    private void rememberKakaoPlaceId(Place place, Set<String> usedKakaoPlaceIds) {
        if (!isSharedAllowedCategory(place.getCategory())
                && place.getKakaoPlaceId() != null
                && !place.getKakaoPlaceId().isBlank()) {
            usedKakaoPlaceIds.add(place.getKakaoPlaceId());
        }
    }

    private void ensureRequiredDiningIsVerified(List<Place> places, int lastDay) {
        if (!hasRequiredDiningCoverage(places, lastDay)) {
            throw new AiGenerationException("카카오맵 검증 후 식당 일정이 부족합니다. 검증 가능한 식당으로 다시 생성해 주세요.");
        }
    }

    static boolean hasRequiredDiningCoverage(List<Place> places, int lastDay) {
        if (lastDay <= 1) {
            return true;
        }
        long restaurantCount = places.stream()
                .filter(place -> "식당".equals(place.getCategory()))
                .count();
        int minimumRestaurantCount = lastDay == 2 ? 2 : 3;
        boolean hasRestaurantEveryDay = IntStream.rangeClosed(1, lastDay)
                .allMatch(day -> places.stream()
                        .anyMatch(place -> Integer.valueOf(day).equals(place.getDayNumber())
                                && "식당".equals(place.getCategory())));
        return restaurantCount >= minimumRestaurantCount && hasRestaurantEveryDay;
    }

    private void normalizeOrderIndexes(List<Place> places) {
        Map<Integer, List<Place>> placesByDay = places.stream()
                .collect(Collectors.groupingBy(Place::getDayNumber));
        placesByDay.values().forEach(dayPlaces -> {
            dayPlaces.sort(Comparator.comparing(Place::getOrderIndex));
            for (int i = 0; i < dayPlaces.size(); i++) {
                dayPlaces.get(i).updateSchedulePosition(dayPlaces.get(i).getDayNumber(), i + 1);
            }
        });
    }

    private boolean isReplaceableCategory(String category) {
        return "카페".equals(category)
                || "숙소".equals(category)
                || "식당".equals(category)
                || "쇼핑".equals(category)
                || "액티비티".equals(category)
                || "관광지".equals(category);
    }

    /**
     * 카카오 검색 실패한 장소와 같은 카테고리로 카카오맵에서 실제 검색되는 대체 장소를 찾는다.
     * areaHint → 상위 권역 → destination 순으로 fallback하며 검색한다.
     */
    private PlaceValidationService.ValidatedPlace tryFindReplacement(
            AiPlannerResponse.PlaceItem item,
            String destination,
            Set<String> usedKakaoPlaceIds,
            boolean allowGenericRestaurantReplacement
    ) {
        Set<String> excludedKakaoPlaceIds = isSharedAllowedCategory(item.category())
                ? Set.of()
                : usedKakaoPlaceIds;

        for (String area : candidateAreas(item.areaHint(), destination)) {
            for (String searchQuery : replacementQueriesForArea(
                    area,
                    item,
                    destination,
                    allowGenericRestaurantReplacement
            )) {
                AiPlannerResponse.PlaceItem searchItem = new AiPlannerResponse.PlaceItem(
                        item.dayNumber(), item.orderIndex(),
                        searchQuery, item.category(),
                        item.description(), item.estimatedCost(),
                        item.durationMinutes(), item.visitTime(),
                        area,
                        searchQuery,
                        null, null, null
                );
                PlaceValidationService.ValidatedPlace result =
                        placeValidationService.validate(searchItem, destination, excludedKakaoPlaceIds).orElse(null);
                if (result != null
                        && result.kakaoVerified()
                        && isAcceptableReplacement(item, destination, area, result)) {
                    return result;
                }
            }
        }
        return null;
    }

    static boolean isAcceptableReplacement(
            AiPlannerResponse.PlaceItem item,
            String destination,
            String area,
            PlaceValidationService.ValidatedPlace result
    ) {
        if (item == null || result == null || !"식당".equals(item.category())) {
            if (item != null && "액티비티".equals(item.category())) {
                return isAcceptableActivityReplacement(item, result);
            }
            return true;
        }

        String source = String.join(" ",
                Objects.toString(item.name(), ""),
                Objects.toString(item.searchKeyword(), ""),
                Objects.toString(item.description(), ""));
        if (!isGyeongjuVegetarianDiningContext(area, destination, source)) {
            return true;
        }

        String normalized = normalizeText(String.join(" ",
                Objects.toString(result.name(), ""),
                Objects.toString(result.categoryName(), "")
        ));
        return GYEONGJU_VEGETARIAN_RESTAURANT_NAMES.stream()
                .map(AiGenerationService::normalizeText)
                .anyMatch(normalized::contains)
                || normalized.contains("채식")
                || normalized.contains("비건")
                || normalized.contains("사찰");
    }

    private static boolean isAcceptableActivityReplacement(
            AiPlannerResponse.PlaceItem item,
            PlaceValidationService.ValidatedPlace result
    ) {
        String source = normalizeText(String.join(" ",
                Objects.toString(item.name(), ""),
                Objects.toString(item.searchKeyword(), ""),
                Objects.toString(item.description(), "")));
        if (!source.contains("서핑") && !source.contains("서프")) {
            return true;
        }

        String replacement = normalizeText(String.join(" ",
                Objects.toString(result.name(), ""),
                Objects.toString(result.categoryName(), "")));
        return replacement.contains("서핑")
                || replacement.contains("서프")
                || replacement.contains("해양스포츠")
                || replacement.contains("수상스포츠")
                || replacement.contains("레저");
    }

    static List<String> candidateAreas(String areaHint, String destination) {
        List<String> areas = new ArrayList<>();
        if (areaHint != null && !areaHint.isBlank()) {
            String trimmedAreaHint = areaHint.trim();
            areas.add(trimmedAreaHint);

            int lastSpaceIndex = trimmedAreaHint.lastIndexOf(' ');
            if (lastSpaceIndex > 0) {
                String broaderArea = trimmedAreaHint.substring(0, lastSpaceIndex).trim();
                if (!broaderArea.isBlank() && !areas.contains(broaderArea)) {
                    areas.add(broaderArea);
                }
            }
        }
        if (destination != null && !destination.isBlank()) {
            String trimmed = destination.trim();
            if (!areas.contains(trimmed)) {
                areas.add(trimmed);
            }
        }
        return areas;
    }

    static String replacementQuery(AiPlannerResponse.PlaceItem item, String destination) {
        return replacementQuery(item, destination, false);
    }

    static String replacementQuery(
            AiPlannerResponse.PlaceItem item,
            String destination,
            boolean allowGenericRestaurantReplacement
    ) {
        String area = item.areaHint() != null && !item.areaHint().isBlank()
                ? item.areaHint().trim()
                : destination;
        return replacementQueryForArea(area, item, allowGenericRestaurantReplacement);
    }

    static String replacementQueryForArea(
            String area,
            AiPlannerResponse.PlaceItem item,
            boolean allowGenericRestaurantReplacement
    ) {
        return replacementQueriesForArea(area, item, null, allowGenericRestaurantReplacement)
                .stream()
                .findFirst()
                .orElse(null);
    }

    static List<String> replacementQueriesForArea(
            String area,
            AiPlannerResponse.PlaceItem item,
            String destination,
            boolean allowGenericRestaurantReplacement
    ) {
        String category = item.category();
        if ("카페".equals(category)) {
            return List.of(area + " 카페");
        }

        String source = String.join(" ",
                Objects.toString(item.name(), ""),
                Objects.toString(item.searchKeyword(), ""),
                Objects.toString(item.description(), ""));
        if ("숙소".equals(category)) {
            String accommodationType = firstContainedKeyword(source, ACCOMMODATION_SEARCH_KEYWORDS);
            return List.of(accommodationType != null ? area + " " + accommodationType : area + " 숙소");
        }
        if ("식당".equals(category)) {
            String diningKeyword = firstContainedKeyword(source, DINING_SEARCH_KEYWORDS);
            if (diningKeyword != null) {
                List<String> queries = new ArrayList<>();
                queries.add(area + " " + diningKeyword);
                if (isGyeongjuVegetarianDiningContext(area, destination, source)) {
                    queries.addAll(gyeongjuVegetarianDiningQueries(area));
                }
                return queries.stream().distinct().toList();
            }
            List<String> queries = new ArrayList<>();
            if (isBusanDiningContext(area, destination, source)) {
                queries.add(area + " 밀면");
                queries.add(area + " 돼지국밥");
                if (!normalizeText(area).contains("부산")) {
                    queries.add("부산 밀면");
                    queries.add("부산 돼지국밥");
                }
            }
            if (isGyeongjuVegetarianDiningContext(area, destination, source)) {
                queries.addAll(gyeongjuVegetarianDiningQueries(area));
            }
            if (allowGenericRestaurantReplacement) {
                queries.add(area + " 식당");
            }
            return queries.stream().distinct().toList();
        }
        if ("쇼핑".equals(category)) {
            if (hasPackagedLocalSnackIntent(source)) {
                List<String> queries = new ArrayList<>();
                queries.add(area + " 황남빵");
                if (!normalizeText(area).contains("경주")) {
                    queries.add("경주 황남빵");
                }
                queries.add("황남빵");
                return queries.stream().distinct().toList();
            }
            String shoppingKeyword = firstContainedKeyword(source, SHOPPING_SEARCH_KEYWORDS);
            if (shoppingKeyword != null) {
                return List.of(area + " " + shoppingKeyword);
            }
            if (source.contains("음료") || source.contains("장보기")
                    || source.contains("바베큐") || source.contains("바비큐") || source.contains("bbq")) {
                return List.of(area + " 마트");
            }
            return List.of(area + " 쇼핑");
        }
        if ("액티비티".equals(category)) {
            String activityKeyword = firstContainedKeyword(source, ACTIVITY_SEARCH_KEYWORDS);
            if (activityKeyword == null) {
                return List.of();
            }
            if (activityKeyword.contains("서프")) {
                activityKeyword = "서핑";
            }

            List<String> queries = new ArrayList<>();
            queries.add(area + " " + activityKeyword);
            if (destination != null && !destination.isBlank()
                    && !normalizeText(area).contains(normalizeText(destination))) {
                queries.add(destination + " " + activityKeyword);
            }
            if (normalizeText(destination).contains("강릉") || normalizeText(area).contains("경포")) {
                queries.add("경포해변 " + activityKeyword);
                queries.add("사천진 " + activityKeyword);
            }
            return queries.stream().distinct().toList();
        }
        if ("관광지".equals(category)) {
            // 실존하는데 정확한 상호가 아니라 검증 실패한 관광지를 제외하지 않고
            // 같은 권역의 의미가 비슷한 명소로 대체한다.
            List<String> queries = new ArrayList<>();
            String sightKeyword = firstContainedKeyword(source, SIGHT_SEARCH_KEYWORDS);
            if (sightKeyword != null) {
                queries.add(area + " " + sightKeyword);
                if (destination != null && !destination.isBlank()
                        && !normalizeText(area).contains(normalizeText(destination))) {
                    queries.add(destination + " " + sightKeyword);
                }
            }
            queries.add(area + " 명소");
            queries.add(area + " 가볼만한곳");
            return queries.stream().distinct().toList();
        }
        return List.of();
    }

    private static List<String> gyeongjuVegetarianDiningQueries(String area) {
        List<String> queries = new ArrayList<>();
        queries.add(area + " 채식");
        queries.add(area + " 비건");
        queries.add(area + " 사찰음식");
        if (!normalizeText(area).contains("경주")) {
            queries.add("경주 채식");
            queries.add("경주 비건");
            queries.add("경주 사찰음식");
        }
        queries.add("경주 마조르");
        queries.add("경주 여기당");
        queries.add("경주 다유");
        queries.add("경주 쑥부쟁이");
        queries.add("경주 향적원");
        queries.add("경주 연화바루");
        return queries;
    }

    private static boolean isBusanDiningContext(String area, String destination, String source) {
        String normalized = normalizeText(String.join(" ",
                Objects.toString(area, ""),
                Objects.toString(destination, ""),
                Objects.toString(source, "")
        ));
        return normalized.contains("부산")
                || normalized.contains("광안리")
                || normalized.contains("해운대")
                || normalized.contains("서면")
                || normalized.contains("남포동")
                || normalized.contains("초량");
    }

    private static boolean isGyeongjuVegetarianDiningContext(String area, String destination, String source) {
        String normalized = normalizeText(String.join(" ",
                Objects.toString(area, ""),
                Objects.toString(destination, ""),
                Objects.toString(source, "")
        ));
        boolean gyeongjuArea = normalized.contains("경주")
                || normalized.contains("불국사")
                || normalized.contains("황리단길")
                || normalized.contains("보문");
        boolean vegetarianIntent = normalized.contains("비건")
                || normalized.contains("채식")
                || normalized.contains("사찰음식")
                || normalized.contains("향적원")
                || normalized.contains("마조르")
                || normalized.contains("발우공양")
                || normalized.contains("연화바루");
        return gyeongjuArea && vegetarianIntent;
    }

    private static boolean hasPackagedLocalSnackIntent(String source) {
        String normalized = normalizeText(source);
        return normalized.contains("황남빵")
                || normalized.contains("빵포장")
                || normalized.contains("포장")
                || normalized.contains("기념품");
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private static String firstContainedKeyword(String source, List<String> keywords) {
        String normalized = source == null ? "" : source.replaceAll("\\s+", "");
        return keywords.stream()
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    private String replacementDescription(String placeName, String category) {
        return switch (category == null ? "" : category) {
            case "식당" -> placeName + "에서 식사합니다.";
            case "카페" -> placeName + "에서 쉬어 갑니다.";
            case "관광지" -> placeName + "을 둘러봅니다.";
            default -> placeName + "을 방문합니다.";
        };
    }

    private String normalizedDescription(AiPlannerResponse.PlaceItem item, String placeName, int lastDay) {
        if (!"숙소".equals(item.category())) {
            return hasUnverifiableViewClaim(item.description())
                    ? replacementDescription(placeName, item.category())
                    : item.description();
        }
        if (Integer.valueOf(1).equals(item.dayNumber())) {
            if (hasGroupMealPreparationClaim(item.description())) {
                return placeName + "에서 체크인하고 바베큐와 휴식 준비를 합니다.";
            }
            return placeName + "에서 체크인합니다.";
        }
        if (Integer.valueOf(lastDay).equals(item.dayNumber())) {
            return placeName + "에서 체크아웃합니다.";
        }
        return item.description();
    }

    private boolean hasGroupMealPreparationClaim(String description) {
        if (description == null || description.isBlank()) {
            return false;
        }
        String normalized = description.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("바베큐")
                || normalized.contains("바비큐")
                || normalized.contains("bbq")
                || normalized.contains("음료")
                || normalized.contains("장보기");
    }

    private boolean hasUnverifiableViewClaim(String description) {
        if (description == null || description.isBlank()) {
            return false;
        }
        String normalized = description.replaceAll("\\s+", "");
        return normalized.contains("바다전망")
                || normalized.contains("오션뷰")
                || normalized.contains("해변이보이는")
                || normalized.contains("바다가보이는")
                || normalized.contains("바다를보며")
                || normalized.contains("바라보며");
    }

    private boolean isValidCoordinate(BigDecimal lat, BigDecimal lng, String destination) {
        return DestinationGeoFence.allows(lat, lng, destination);
    }

    private int normalizedCost(AiPlannerResponse.PlaceItem item) {
        if (item == null) {
            return 0;
        }
        String category = item.category();
        if ("숙소".equals(category) || "이동".equals(category)) {
            return 0;
        }
        return item.estimatedCost() != null ? item.estimatedCost() : 0;
    }

    private LocalTime parseVisitTime(String visitTime) {
        if (visitTime == null || visitTime.isBlank()) return null;
        try {
            return LocalTime.parse(visitTime, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            log.warn("[AI] visit_time 파싱 실패: {} (무시)", visitTime);
            return null;
        }
    }

    private String textPath(com.fasterxml.jackson.databind.JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() || "null".equals(text) ? null : text;
    }

    private String buildSplitReason(String conditionsJson) {
        try {
            var conditions = objectMapper.readTree(conditionsJson);
            var conflicts = conditions.path("conflicts");
            if (conflicts.isArray() && !conflicts.isEmpty()) {
                String topic = textPath(conflicts.get(0), "topic");
                if (topic != null) {
                    return topic + " 의견이 갈려 A안과 B안을 서로 다른 선택지로 나눴습니다. 나머지 식당, 카페, 관광지도 각 방향에 맞게 다르게 구성했습니다.";
                }
            }
        } catch (Exception e) {
            log.warn("[AI] split_reason 생성 실패 (기본 문구 사용)", e);
        }
        return "대화에서 드러난 선호를 기준으로 A안과 B안의 분위기와 동선을 다르게 구성했습니다.";
    }
}
