package com.triplan.triplan.service;

import com.triplan.triplan.dto.CandidateResponse;
import com.triplan.triplan.dto.AiFinalPlanResponse;
import com.triplan.triplan.dto.OrderPreferenceRequest;
import com.triplan.triplan.dto.PlaceResponse;
import com.triplan.triplan.dto.PlanCreateRequest;
import com.triplan.triplan.dto.PlanCreateResponse;
import com.triplan.triplan.dto.PlanResponse;
import com.triplan.triplan.dto.VoteHighlightResponse;
import com.triplan.triplan.entity.*;
import com.triplan.triplan.exception.BadRequestException;
import com.triplan.triplan.notification.NotificationService;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanCommentRepository;
import com.triplan.triplan.repository.PlanParticipantRepository;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.PlaceRepository;
import com.triplan.triplan.repository.VoteFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private static final int MAX_MISSION_LENGTH = 2_500;
    private static final String MISSING_BOTH_PROMPT_MESSAGE =
            "대화 내용과 여행 요청사항을 함께 넣어주세요.";
    private static final String MISSING_CHAT_LOG_MESSAGE =
            "여행 요청사항만으로는 대화 흐름을 알기 어려워요. 대화 파일을 올리거나 내용을 붙여넣어 주세요.";
    private static final String MISSING_MISSION_MESSAGE =
            "대화만으로는 여행 조건이 부족해요. 여행 요청사항에 기간, 예산, 꼭 넣을 곳을 함께 적어주세요.";

    private final PlanRepository planRepository;
    private final PlanCandidateRepository planCandidateRepository;
    private final PlanParticipantRepository planParticipantRepository;
    private final PlanCommentRepository planCommentRepository;
    private final PlaceRepository placeRepository;
    private final VoteFeedbackRepository voteFeedbackRepository;
    private final AiGenerationService aiGenerationService;
    private final FinalPlanAiService finalPlanAiService;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public PlanCreateResponse createPlan(PlanCreateRequest request, User authenticatedUser) {
        validateMission(request.mission());
        validatePromptSource(request.chatLog(), request.mission());

        Plan plan = Plan.builder()
                .uuid(UUID.randomUUID().toString())
                .owner(authenticatedUser)
                .mission(request.mission())
                .chatLogText(request.chatLog())
                .status(PlanStatus.DRAFT)
                .build();

        Plan saved = planRepository.save(plan);
        if (authenticatedUser != null) {
            ensureOwnerParticipant(saved, authenticatedUser);
        }
        log.info("[PLAN] 플랜 생성 완료 - planId: {}, uuid: {}, ownerId: {}",
                saved.getId(),
                saved.getUuid(),
                authenticatedUser != null ? authenticatedUser.getId() : null);

        return new PlanCreateResponse(
                saved.getId(),
                saved.getUuid(),
                saved.getStatus().name()
        );
    }

    private void validateMission(String mission) {
        if (mission != null && mission.length() > MAX_MISSION_LENGTH) {
            throw new BadRequestException("여행 요청사항은 최대 " + MAX_MISSION_LENGTH + "자까지 입력할 수 있습니다.");
        }
    }

    private void validatePromptSource(String chatLog, String mission) {
        boolean missingChatLog = chatLog == null || chatLog.isBlank();
        boolean missingMission = mission == null || mission.isBlank();
        if (missingChatLog && missingMission) {
            throw new BadRequestException(MISSING_BOTH_PROMPT_MESSAGE);
        }
        if (missingChatLog) {
            throw new BadRequestException(MISSING_CHAT_LOG_MESSAGE);
        }
        if (missingMission) {
            throw new BadRequestException(MISSING_MISSION_MESSAGE);
        }
    }

    @Transactional
    public void updateTitle(String uuid, String title, User authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        if (plan.getOwner() == null || !plan.getOwner().getId().equals(authenticatedUser.getId())) {
            throw new AccessDeniedException("플랜 소유자만 제목을 변경할 수 있습니다.");
        }
        if (title == null || title.isBlank()) {
            throw new BadRequestException("제목을 입력해주세요.");
        }
        if (title.length() > 100) {
            throw new BadRequestException("제목은 최대 100자까지 입력할 수 있습니다.");
        }
        plan.updateTitle(title.trim());
    }

    @Transactional
    public void sharePlan(String uuid, User authenticatedUser) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        claimOrValidateOwner(plan, authenticatedUser);

        if (plan.getStatus() == PlanStatus.AI_DONE) {
            plan.updateStatus(PlanStatus.VOTING);
            return;
        }
        if (plan.getStatus() == PlanStatus.VOTING) {
            return;
        }
        throw new IllegalStateException("AI 생성이 완료된 플랜만 공유할 수 있습니다.");
    }

    @Transactional
    public PlanResponse claimPlan(String uuid, User authenticatedUser) {
        Plan plan = planRepository.findByUuidForUpdate(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        claimOrValidateOwner(plan, authenticatedUser);
        return PlanResponse.from(plan);
    }

    @Transactional
    public void updatePlaceOrders(String uuid, List<OrderPreferenceRequest> requests, User authenticatedUser) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        claimOrValidateOwner(plan, authenticatedUser);

        if (requests == null || requests.isEmpty()) {
            return;
        }

        for (OrderPreferenceRequest request : requests) {
            PlanCandidate candidate = planCandidateRepository.findById(request.candidateId())
                    .orElseThrow(() -> new IllegalArgumentException("후보안을 찾을 수 없습니다: " + request.candidateId()));
            if (candidate.getPlan() == null
                    || !candidate.getPlan().getId().equals(plan.getId())) {
                throw new AccessDeniedException("해당 플랜의 후보안만 수정할 수 있습니다.");
            }
            validateCandidateOrderEditable(plan, candidate);

            Place place = placeRepository.findById(request.placeId())
                    .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + request.placeId()));
            if (place.getCandidate() == null || !place.getCandidate().getId().equals(candidate.getId())) {
                throw new AccessDeniedException("해당 후보안의 장소만 수정할 수 있습니다.");
            }

            place.updateSchedulePosition(request.preferredDayNumber(), request.preferredOrderIndex());
        }
    }

    private static final Set<String> UNDELETABLE_CATEGORIES = Set.of("숙소", "이동");

    @Transactional
    public void deletePlace(String uuid, Long candidateId, Long placeId, User authenticatedUser) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        claimOrValidateOwner(plan, authenticatedUser);

        PlanCandidate candidate = planCandidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("후보안을 찾을 수 없습니다: " + candidateId));
        if (candidate.getPlan() == null
                || !candidate.getPlan().getId().equals(plan.getId())) {
            throw new AccessDeniedException("해당 플랜의 후보안만 수정할 수 있습니다.");
        }
        validateCandidateDeleteEditable(plan, candidate);

        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + placeId));
        if (place.getCandidate() == null || !place.getCandidate().getId().equals(candidate.getId())) {
            throw new AccessDeniedException("해당 후보안의 장소만 삭제할 수 있습니다.");
        }

        if (UNDELETABLE_CATEGORIES.contains(place.getCategory())) {
            throw new BadRequestException("숙소와 이동 거점은 삭제할 수 없습니다.");
        }

        int deletedDay = place.getDayNumber();
        placeRepository.delete(place);
        placeRepository.flush();

        // 삭제된 날짜의 order_index 재정렬
        List<Place> remaining = placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(candidateId);
        List<Place> remainingInDeletedDay = remaining
                .stream()
                .filter(p -> p.getDayNumber().equals(deletedDay))
                .toList();
        for (int i = 0; i < remainingInDeletedDay.size(); i++) {
            remainingInDeletedDay.get(i).updateSchedulePosition(deletedDay, i + 1);
        }
        int recalculatedBudget = remaining.stream()
                .mapToInt(p -> p.getEstimatedCost() != null ? p.getEstimatedCost() : 0)
                .sum();
        candidate.updateEstimatedCostPerPerson(recalculatedBudget);

        log.info("[PLAN] 장소 삭제 - planId: {}, candidateId: {}, placeId: {}, name: {}, recalculatedBudget: {}",
                plan.getId(), candidateId, placeId, place.getName(), recalculatedBudget);
    }

    private void validateCandidateOrderEditable(Plan plan, PlanCandidate candidate) {
        boolean finalCandidate = Boolean.TRUE.equals(candidate.getIsFinal());
        boolean editableDraftCandidate = !finalCandidate
                && (plan.getStatus() == PlanStatus.AI_DONE || plan.getStatus() == PlanStatus.VOTING);
        boolean editableFinalCandidate = finalCandidate && plan.getStatus() == PlanStatus.CONFIRMED;

        if (!editableDraftCandidate && !editableFinalCandidate) {
            throw new IllegalStateException(
                    "AI 생성 완료/투표 중인 A/B안 또는 추천 일정만 장소 순서를 변경할 수 있습니다."
            );
        }
    }

    private void validateCandidateDeleteEditable(Plan plan, PlanCandidate candidate) {
        boolean finalCandidate = Boolean.TRUE.equals(candidate.getIsFinal());
        boolean editableDraftCandidate = !finalCandidate
                && (plan.getStatus() == PlanStatus.AI_DONE || plan.getStatus() == PlanStatus.VOTING);
        boolean editableFinalCandidate = finalCandidate && plan.getStatus() == PlanStatus.CONFIRMED;

        if (!editableDraftCandidate && !editableFinalCandidate) {
            throw new IllegalStateException(
                    "AI 생성 완료/투표 중인 A/B안 또는 추천 일정만 일정을 삭제할 수 있습니다."
            );
        }
    }

    @Transactional
    public void deletePlan(String uuid, User authenticatedUser) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        validateOwner(plan, authenticatedUser);
        planRepository.delete(plan);
    }

    @Transactional
    public void leavePlan(String uuid, User authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }

        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        if (plan.getOwner() != null && plan.getOwner().getId().equals(authenticatedUser.getId())) {
            throw new BadRequestException("내가 만든 여행은 플랜 삭제를 이용해 주세요.");
        }

        planParticipantRepository.findByPlanIdAndUserId(plan.getId(), authenticatedUser.getId())
                .ifPresent(planParticipantRepository::delete);
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlanByUuid(String uuid) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        return PlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getStatus(Long planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planId));
        return Map.of("status", plan.getStatus().name(), "uuid", plan.getUuid());
    }

    // =========================================================================
    // AI 실연동 — 권한 체크 후 @Async 서비스에 위임
    // =========================================================================

    /**
     * AI 일정 생성을 비동기로 시작한다.
     *
     * <p>권한 체크 + 상태 변경 후 즉시 리턴.
     * 실제 AI 호출은 {@link AiGenerationService#generateAsync(Long)}에서 별도 스레드로 처리.
     * 프론트는 {@code GET /plans/{id}/status}로 폴링하여 완료를 감지한다.
     */
    @Transactional
    public void generateWithAI(Long planId, User authenticatedUser) {
        log.info("[AI] 생성 요청 수신 - planId: {}, userId: {}",
                planId,
                authenticatedUser != null ? authenticatedUser.getId() : null);

        Plan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planId));
        if (plan.getOwner() != null) {
            validateOwner(plan, authenticatedUser);
        }

        if (plan.getStatus() == PlanStatus.AI_GENERATING
                || plan.getStatus() == PlanStatus.VOTING
                || plan.getStatus() == PlanStatus.CONFIRMED
                || plan.getStatus() == PlanStatus.COMPLETED) {
            log.warn("[AI] 생성 요청 무시 - planId: {}, status: {}", planId, plan.getStatus());
            return;
        }

        validatePromptSource(plan.getChatLogText(), plan.getMission());

        List<PlanCandidate> existingCandidates = planCandidateRepository.findByPlanId(planId);
        if (!existingCandidates.isEmpty() && plan.getStatus() == PlanStatus.DRAFT) {
            placeRepository.deleteNonFinalByPlanId(planId);
            planCandidateRepository.deleteNonFinalByPlanId(planId);
            log.warn("[AI] DRAFT 플랜에 남아있던 후보 데이터를 정리하고 재생성합니다 - planId: {}", planId);
        } else if (!existingCandidates.isEmpty()) {
            return;
        }

        plan.updateStatus(PlanStatus.AI_GENERATING);
        log.info("[AI] 플랜 상태 AI_GENERATING 전환 - planId: {}", planId);

        planRepository.flush();
        startAiGenerationAfterCommit(planId);
    }

    private void startAiGenerationAfterCommit(Long planId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            aiGenerationService.generateAsync(planId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiGenerationService.generateAsync(planId);
            }
        });
    }

    // =========================================================================
    // AI 미연동 Mock (테스트용 보존)
    // =========================================================================

    // AI 미연동 Mock — status를 AI_DONE으로 변경하고 더미 A/B안 데이터 삽입
    @Transactional
    public void generateMock(Long planId, User authenticatedUser) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planId));
        if (plan.getOwner() != null) {
            validateOwner(plan, authenticatedUser);
        }

        plan.updateStatus(PlanStatus.AI_DONE);
        plan.updateDemoInfo(
                "부산 2박 3일 여행",
                "부산",
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 17),
                4
        );

        // 이미 후보가 있으면 재생성 안 함
        if (!planCandidateRepository.findByPlanId(planId).isEmpty()) return;

        PlanCandidate planA = planCandidateRepository.save(PlanCandidate.builder()
                .plan(plan).label("A").name("빡빡한 명소 정복")
                .concept("맛볼 최대 공략 · 명소 집중 탐방")
                .estimatedCostPerPerson(128000).totalDistanceKm(new BigDecimal("42.50"))
                .version(1)
                .build());

        PlanCandidate planB = planCandidateRepository.save(PlanCandidate.builder()
                .plan(plan).label("B").name("여유로운 힐링")
                .concept("느린 템포 · 감성 힐링 중심 코스")
                .estimatedCostPerPerson(112000).totalDistanceKm(new BigDecimal("28.30"))
                .version(2)
                .build());

        placeRepository.saveAll(mockPlaces(planA));
        placeRepository.saveAll(mockPlaces(planB));
    }

    public CandidateResponse finalizePlan(String uuid, User authenticatedUser) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        FinalizeDraft draft = tx.execute(status -> loadFinalizeDraft(uuid, authenticatedUser));
        if (draft == null) {
            throw new IllegalStateException("추천 일정 생성 준비에 실패했습니다.");
        }
        if (draft.existingResponse() != null) {
            return draft.existingResponse();
        }

        Optional<AiFinalPlanResponse> aiResponse = finalPlanAiService.generate(draft.aiRequest());
        CandidateResponse response = tx.execute(status -> saveFinalPlan(uuid, authenticatedUser, aiResponse));
        if (response == null) {
            throw new IllegalStateException("추천 일정 저장에 실패했습니다.");
        }
        try {
            planRepository.findByUuid(uuid).ifPresent(notificationService::notifyFinalPlanCreated);
        } catch (Exception e) {
            log.warn("[Notification] 최종일정 알림 발송 실패 uuid={} cause={}", uuid, e.toString());
        }
        return response;
    }

    private FinalizeDraft loadFinalizeDraft(String uuid, User authenticatedUser) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        validateOwner(plan, authenticatedUser);

        if (plan.getStatus() == PlanStatus.CONFIRMED || plan.getStatus() == PlanStatus.COMPLETED) {
            return new FinalizeDraft(existingFinalResponse(plan), null);
        }
        if (plan.getStatus() != PlanStatus.VOTING && plan.getStatus() != PlanStatus.AI_DONE) {
            throw new IllegalStateException("AI 생성 완료 또는 투표 중인 플랜만 추천 일정을 만들 수 있습니다.");
        }

        List<PlanCandidate> candidates = planCandidateRepository.findByPlanIdAndIsFinalFalse(plan.getId());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("확정할 후보안이 없습니다: " + uuid);
        }

        List<VoteFeedback> votes = voteFeedbackRepository.findByPlanId(plan.getId());
        FinalPlanAiService.FinalPlanRequest aiRequest = buildFinalPlanRequest(plan, candidates, votes);
        return new FinalizeDraft(null, aiRequest);
    }

    private CandidateResponse saveFinalPlan(
            String uuid,
            User authenticatedUser,
            Optional<AiFinalPlanResponse> aiResponse
    ) {
        Plan plan = planRepository.findByUuidForUpdate(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        validateOwner(plan, authenticatedUser);

        if (plan.getStatus() == PlanStatus.CONFIRMED || plan.getStatus() == PlanStatus.COMPLETED) {
            return existingFinalResponse(plan);
        }
        if (plan.getStatus() != PlanStatus.VOTING && plan.getStatus() != PlanStatus.AI_DONE) {
            throw new IllegalStateException("AI 생성 완료 또는 투표 중인 플랜만 추천 일정을 만들 수 있습니다.");
        }

        List<PlanCandidate> candidates = planCandidateRepository.findByPlanIdAndIsFinalFalse(plan.getId());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("확정할 후보안이 없습니다: " + uuid);
        }
        List<VoteFeedback> votes = voteFeedbackRepository.findByPlanId(plan.getId());

        FinalPlanBuild finalPlan = aiResponse
                .flatMap(response -> buildFinalPlanFromAi(response, candidates, votes))
                .orElseGet(() -> buildFallbackFinalPlan(candidates, votes));
        if (finalPlan.selections().isEmpty()) {
            throw new IllegalArgumentException("확정할 장소가 없습니다: " + uuid);
        }

        int recalculatedBudget = finalPlan.selections().stream()
                .map(FinalPlaceSelection::place)
                .mapToInt(p -> p.getEstimatedCost() != null ? p.getEstimatedCost() : 0)
                .sum();

        PlanCandidate dPlan = planCandidateRepository.save(PlanCandidate.builder()
                .plan(plan)
                .label("D")
                .name(nonBlankOrDefault(sanitizeFinalDisplayText(finalPlan.name()), "투표 기반 추천 일정"))
                .concept(nonBlankOrDefault(sanitizeFinalDisplayText(finalPlan.concept()), "장소별 투표와 의견을 반영해 재구성한 여행 계획"))
                .splitReason(nonBlankOrDefault(sanitizeFinalSplitReason(finalPlan.splitReason()), "친구들이 선호한 장소를 중심으로 동선과 휴식 균형을 맞췄습니다."))
                .estimatedCostPerPerson(recalculatedBudget)
                .totalDistanceKm(null)
                .isFinal(true)
                .build());

        List<Place> savedPlaces = placeRepository.saveAll(
                finalPlan.selections().stream()
                        .map(selection -> {
                            Place p = selection.place();
                            return Place.builder()
                                    .candidate(dPlan)
                                    .dayNumber(selection.dayNumber())
                                    .orderIndex(selection.orderIndex())
                                    .name(p.getName())
                                    .category(p.getCategory())
                                    .description(p.getDescription())
                                    .estimatedCost(p.getEstimatedCost())
                                    .durationMinutes(selection.durationMinutes() != null
                                            ? selection.durationMinutes()
                                            : p.getDurationMinutes())
                                    .visitTime(selection.visitTime() != null
                                            ? selection.visitTime()
                                            : p.getVisitTime())
                                    .lat(p.getLat())
                                    .lng(p.getLng())
                                    .kakaoPlaceId(p.getKakaoPlaceId())
                                    .build();
                        })
                        .toList()
        );

        plan.updateStatus(PlanStatus.CONFIRMED);

        List<PlaceResponse> placeResponses = savedPlaces.stream().map(PlaceResponse::from).toList();
        return CandidateResponse.of(dPlan, placeResponses);
    }

    private CandidateResponse existingFinalResponse(Plan plan) {
        List<PlanCandidate> existing = planCandidateRepository.findByPlanIdAndIsFinalTrue(plan.getId());
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("추천 일정을 찾을 수 없습니다: " + plan.getUuid());
        }
        PlanCandidate dPlan = existing.get(0);
        List<PlaceResponse> places = placeRepository
                .findByCandidateIdOrderByDayNumberAscOrderIndexAsc(dPlan.getId())
                .stream()
                .map(PlaceResponse::from)
                .toList();
        return CandidateResponse.of(dPlan, places);
    }

    private FinalPlanAiService.FinalPlanRequest buildFinalPlanRequest(
            Plan plan,
            List<PlanCandidate> candidates,
            List<VoteFeedback> votes
    ) {
        Map<Long, List<VoteFeedback>> votesByPlaceId = votes.stream()
                .filter(v -> v.getPlace() != null && v.getPlace().getId() != null)
                .collect(Collectors.groupingBy(v -> v.getPlace().getId()));

        List<FinalPlanAiService.CandidateInput> candidateInputs = candidates.stream()
                .sorted(Comparator.comparing(PlanCandidate::getLabel, Comparator.nullsLast(String::compareTo)))
                .map(candidate -> {
                    List<FinalPlanAiService.PlaceInput> places = placeRepository
                            .findByCandidateIdOrderByDayNumberAscOrderIndexAsc(candidate.getId())
                            .stream()
                            .map(place -> {
                                VoteStats stats = voteStats(votesByPlaceId.getOrDefault(place.getId(), List.of()));
                                return new FinalPlanAiService.PlaceInput(
                                        place.getId(),
                                        place.getDayNumber(),
                                        place.getOrderIndex(),
                                        place.getName(),
                                        place.getCategory(),
                                        place.getDescription(),
                                        place.getEstimatedCost(),
                                        place.getDurationMinutes(),
                                        place.getVisitTime() != null ? place.getVisitTime().toString() : null,
                                        place.getLat(),
                                        place.getLng(),
                                        new FinalPlanAiService.VoteSummary(
                                                stats.dislikeCount(),
                                                stats.likeCount(),
                                                stats.superLikeCount(),
                                                stats.pinCount(),
                                                stats.actualVoteCount(),
                                                stats.weightedScore()
                                        )
                                );
                            })
                            .toList();
                    return new FinalPlanAiService.CandidateInput(
                            candidate.getId(),
                            candidate.getLabel(),
                            candidate.getName(),
                            candidate.getConcept(),
                            places
                    );
                })
                .toList();

        List<FinalPlanAiService.CommentInput> comments = new ArrayList<>();
        comments.addAll(votes.stream()
                .filter(v -> v.getComment() != null && !v.getComment().isBlank())
                .map(v -> new FinalPlanAiService.CommentInput(
                        v.getPlace().getId(),
                        v.getPlace().getName(),
                        v.getCandidate().getLabel(),
                        v.getVoteScore(),
                        v.getComment().trim()
                ))
                .toList());
        comments.addAll(planCommentRepository.findByPlanIdOrderByCreatedAtDesc(plan.getId()).stream()
                .filter(comment -> comment.getContent() != null && !comment.getContent().isBlank())
                .map(comment -> new FinalPlanAiService.CommentInput(
                        null,
                        "전체 일정",
                        null,
                        null,
                        comment.getContent().trim()
                ))
                .toList());

        return new FinalPlanAiService.FinalPlanRequest(
                plan.getId(),
                plan.getDestination(),
                plan.getMission(),
                calculateDayCount(plan, candidateInputs),
                plan.getParticipantCount(),
                candidateInputs,
                comments
        );
    }

    private int calculateDayCount(
            Plan plan,
            List<FinalPlanAiService.CandidateInput> candidateInputs
    ) {
        if (plan.getStartDate() != null && plan.getEndDate() != null && !plan.getEndDate().isBefore(plan.getStartDate())) {
            long days = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;
            return Math.max(1, Math.toIntExact(days));
        }
        return candidateInputs.stream()
                .flatMap(candidate -> candidate.places().stream())
                .map(FinalPlanAiService.PlaceInput::dayNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
    }

    private Optional<FinalPlanBuild> buildFinalPlanFromAi(
            AiFinalPlanResponse response,
            List<PlanCandidate> candidates,
            List<VoteFeedback> votes
    ) {
        Map<Long, Place> sourcePlacesById = sourcePlacesById(candidates);
        Set<Long> pinnedPlaceIds = pinnedPlaceIds(votes);
        Set<Long> selectedPlaceIds = new HashSet<>();
        List<FinalPlaceSelection> selections = new ArrayList<>();

        for (AiFinalPlanResponse.PlaceSelection selection : response.places()) {
            if (selection.sourcePlaceId() == null
                    || selection.dayNumber() == null || selection.dayNumber() < 1
                    || selection.orderIndex() == null || selection.orderIndex() < 1) {
                log.warn("[AI-FINAL] 필수 필드가 빠진 추천 일정 장소 응답 - selection: {}", selection);
                return Optional.empty();
            }
            Place sourcePlace = sourcePlacesById.get(selection.sourcePlaceId());
            if (sourcePlace == null) {
                log.warn("[AI-FINAL] 존재하지 않는 source_place_id 응답 - sourcePlaceId: {}", selection.sourcePlaceId());
                return Optional.empty();
            }
            if (!selectedPlaceIds.add(sourcePlace.getId())) {
                log.warn("[AI-FINAL] 중복 source_place_id 응답 - sourcePlaceId: {}", sourcePlace.getId());
                return Optional.empty();
            }
            selections.add(new FinalPlaceSelection(
                    sourcePlace,
                    selection.dayNumber(),
                    selection.orderIndex(),
                    parseVisitTime(selection.visitTime()),
                    selection.durationMinutes() != null && selection.durationMinutes() > 0
                            ? selection.durationMinutes()
                            : null
            ));
        }

        if (!selectedPlaceIds.containsAll(pinnedPlaceIds)) {
            log.warn("[AI-FINAL] 고정 장소가 누락되어 기존 확정 로직으로 대체 - pinnedPlaceIds: {}, selectedPlaceIds: {}",
                    pinnedPlaceIds, selectedPlaceIds);
            return Optional.empty();
        }
        int minimumPlaceCount = minimumFinalPlaceCount(sourcePlacesById.values());
        if (selections.size() < minimumPlaceCount) {
            log.warn("[AI-FINAL] 추천 일정 장소 수가 너무 적어 기존 추천 로직으로 대체 - selected: {}, minimum: {}",
                    selections.size(), minimumPlaceCount);
            return Optional.empty();
        }

        return Optional.of(new FinalPlanBuild(
                nonBlankOrDefault(response.name(), "투표 반영 추천 일정"),
                nonBlankOrDefault(response.concept(), "투표 결과를 바탕으로 A/B안의 장점을 절충했습니다."),
                nonBlankOrDefault(response.splitReason(), "친구들이 선호한 장소를 중심으로 동선과 휴식 균형을 맞췄습니다."),
                normalizeFinalSelections(selections)
        ));
    }

    private FinalPlanBuild buildFallbackFinalPlan(List<PlanCandidate> candidates, List<VoteFeedback> votes) {
        List<Place> selectedPlaces = selectFinalPlaces(candidates, votes);
        return new FinalPlanBuild(
                "투표 기반 추천 일정",
                "친구들의 선택을 바탕으로 다시 정리한 여행 계획",
                "친구들이 선호한 장소를 중심으로 구성하고, 반응이 낮았던 후보는 줄여 전체 흐름을 맞췄습니다.",
                buildDefaultFinalSelections(selectedPlaces)
        );
    }

    private String sanitizeFinalSplitReason(String value) {
        String sanitized = sanitizeFinalDisplayText(value);
        if (sanitized == null || sanitized.isBlank()) {
            return sanitized;
        }
        if (!containsVoteButtonLabel(sanitized)) {
            return sanitized;
        }
        if (containsAnyKeyword(sanitized, "식당", "맛집", "밀면", "국밥", "시장", "카페", "숙소")) {
            return "친구들이 선호한 식사와 숙소를 중심으로 잡고, 반응이 낮았던 후보는 줄여 동선과 쉬는 흐름을 맞췄습니다.";
        }
        return "친구들이 선호한 장소를 중심으로 구성하고, 반응이 낮았던 후보는 줄여 동선과 분위기의 균형을 맞췄습니다.";
    }

    private boolean containsVoteButtonLabel(String value) {
        return containsAnyKeyword(value, "왕따봉", "따봉", "고정", "싫어요", "핀 장소", "핀 고정");
    }

    private boolean containsAnyKeyword(String value, String... keywords) {
        if (value == null) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        return Arrays.stream(keywords)
                .map(keyword -> keyword.replaceAll("\\s+", ""))
                .anyMatch(normalized::contains);
    }

    private String sanitizeFinalDisplayText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replaceAll("(?i)\\s*\\((?:placeId|source_place_id|place_id|id)?\\s*#?\\d+\\)", "")
                .replaceAll("(?i)\\b(?:placeId|source_place_id|place_id|id)\\s*[:=]\\s*#?\\d+\\b", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private int minimumFinalPlaceCount(Collection<Place> sourcePlaces) {
        int availableCount = sourcePlaces.size();
        int maxDay = sourcePlaces.stream()
                .map(Place::getDayNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
        int target = switch (maxDay) {
            case 1 -> 5;
            case 2 -> 7;
            case 3 -> 10;
            default -> Math.max(10, maxDay * 3);
        };
        return Math.min(availableCount, target);
    }

    private List<FinalPlaceSelection> buildDefaultFinalSelections(List<Place> selectedPlaces) {
        return selectedPlaces.stream()
                .sorted(Comparator
                        .comparing(Place::getDayNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Place::getOrderIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(place -> new FinalPlaceSelection(
                        place,
                        place.getDayNumber() != null ? place.getDayNumber() : 1,
                        place.getOrderIndex() != null ? place.getOrderIndex() : 1,
                        null,
                        null
                ))
                .toList();
    }

    private Map<Long, Place> sourcePlacesById(List<PlanCandidate> candidates) {
        Map<Long, Place> result = new HashMap<>();
        for (PlanCandidate candidate : candidates) {
            for (Place place : placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(candidate.getId())) {
                result.put(place.getId(), place);
            }
        }
        return result;
    }

    private Set<Long> pinnedPlaceIds(List<VoteFeedback> votes) {
        return votes.stream()
                .filter(v -> v.getVoteScore() != null && v.getVoteScore() == 4)
                .map(VoteFeedback::getPlace)
                .filter(Objects::nonNull)
                .map(Place::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<FinalPlaceSelection> normalizeFinalSelections(List<FinalPlaceSelection> selections) {
        Map<Integer, Integer> nextOrderByDay = new HashMap<>();
        List<FinalPlaceSelection> normalized = new ArrayList<>();
        selections.stream()
                .sorted(Comparator
                        .comparing(FinalPlaceSelection::dayNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FinalPlaceSelection::orderIndex, Comparator.nullsLast(Integer::compareTo)))
                .forEach(selection -> {
                    int dayNumber = selection.dayNumber() != null ? selection.dayNumber() : 1;
                    int orderIndex = nextOrderByDay.merge(dayNumber, 1, Integer::sum);
                    normalized.add(new FinalPlaceSelection(
                            selection.place(),
                            dayNumber,
                            orderIndex,
                            selection.visitTime(),
                            selection.durationMinutes()
                    ));
                });
        return normalized;
    }

    private LocalTime parseVisitTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value.trim() : defaultValue;
    }

    @Transactional(readOnly = true)
    public CandidateResponse getFinalCandidate(String uuid) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        if (plan.getStatus() != PlanStatus.CONFIRMED && plan.getStatus() != PlanStatus.COMPLETED) {
            throw new IllegalStateException("아직 추천 일정이 생성되지 않은 플랜입니다.");
        }

        List<PlanCandidate> finalCandidates = planCandidateRepository.findByPlanIdAndIsFinalTrue(plan.getId());
        if (finalCandidates.isEmpty()) {
            throw new IllegalArgumentException("추천 일정을 찾을 수 없습니다: " + uuid);
        }

        PlanCandidate dPlan = finalCandidates.get(0);
        List<Place> finalPlaces = placeRepository
                .findByCandidateIdOrderByDayNumberAscOrderIndexAsc(dPlan.getId());
        List<PlaceResponse> places = finalPlaces.stream()
                .map(PlaceResponse::from)
                .toList();

        List<VoteFeedback> votes = voteFeedbackRepository.findByPlanId(plan.getId());
        List<VoteHighlightResponse> highlights = buildFinalHighlights(finalPlaces, votes);
        return CandidateResponse.of(dPlan, places, highlights);
    }

    /**
     * D안에 포함된 장소 중 투표 점수가 높은 상위 3곳을 추린다.
     *
     * <p>D안 장소는 원본 A/B 장소의 복사본이라 장소명으로 투표를 집계한다.
     * 고정(왕핀)이 많은 장소를 우선 노출하고, 동률이면 가중 점수로 정렬한다.
     */
    private List<VoteHighlightResponse> buildFinalHighlights(List<Place> finalPlaces, List<VoteFeedback> votes) {
        if (votes.isEmpty()) {
            return List.of();
        }
        Map<String, List<VoteFeedback>> votesByName = votes.stream()
                .filter(v -> v.getPlace() != null && v.getPlace().getName() != null)
                .collect(Collectors.groupingBy(v -> v.getPlace().getName()));

        record Ranked(Place place, VoteStats stats) {}

        return finalPlaces.stream()
                .map(place -> new Ranked(place, voteStats(votesByName.getOrDefault(place.getName(), List.of()))))
                .filter(r -> r.stats().pinCount() + r.stats().superLikeCount() + r.stats().likeCount() > 0)
                .sorted(Comparator
                        .comparingInt((Ranked r) -> r.stats().pinCount()).reversed()
                        .thenComparing(Comparator.comparingInt((Ranked r) -> r.stats().weightedScore()).reversed()))
                .limit(3)
                .map(r -> new VoteHighlightResponse(
                        r.place().getName(),
                        r.place().getCategory(),
                        r.stats().pinCount(),
                        r.stats().superLikeCount(),
                        r.stats().likeCount()
                ))
                .toList();
    }

    private List<Place> selectFinalPlaces(List<PlanCandidate> candidates, List<VoteFeedback> votes) {
        Map<Long, List<VoteFeedback>> votesByPlaceId = votes.stream()
                .collect(Collectors.groupingBy(v -> v.getPlace().getId()));
        Map<Long, Integer> candidateOrder = new HashMap<>();
        List<PlanCandidate> orderedCandidates = candidates.stream()
                .sorted(Comparator.comparing(PlanCandidate::getLabel, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (int i = 0; i < orderedCandidates.size(); i++) {
            candidateOrder.put(orderedCandidates.get(i).getId(), i);
        }

        Map<SlotKey, List<Place>> placesBySlot = new TreeMap<>();
        for (PlanCandidate candidate : orderedCandidates) {
            List<Place> places = placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(candidate.getId());
            for (Place place : places) {
                placesBySlot.computeIfAbsent(
                        new SlotKey(place.getDayNumber(), place.getOrderIndex()),
                        ignored -> new ArrayList<>()
                ).add(place);
            }
        }

        return placesBySlot.values().stream()
                .map(slotPlaces -> choosePlaceForSlot(slotPlaces, votesByPlaceId, candidateOrder))
                .filter(Objects::nonNull)
                .toList();
    }

    private Place choosePlaceForSlot(
            List<Place> slotPlaces,
            Map<Long, List<VoteFeedback>> votesByPlaceId,
            Map<Long, Integer> candidateOrder
    ) {
        return slotPlaces.stream()
                .max(Comparator
                        .comparing((Place p) -> voteStats(votesByPlaceId.getOrDefault(p.getId(), List.of())).pinCount())
                        .thenComparing(p -> voteStats(votesByPlaceId.getOrDefault(p.getId(), List.of())).weightedScore())
                        .thenComparing(p -> voteStats(votesByPlaceId.getOrDefault(p.getId(), List.of())).actualVoteCount())
                        .thenComparing(p -> -candidateOrder.getOrDefault(p.getCandidate().getId(), Integer.MAX_VALUE)))
                .orElse(null);
    }

    private VoteStats voteStats(List<VoteFeedback> votes) {
        int dislike = 0;
        int like = 0;
        int superLike = 0;
        int pin = 0;
        int weightedScore = 0;

        for (VoteFeedback vote : votes) {
            Byte score = vote.getVoteScore();
            if (score == null || score < 1 || score > 4) {
                continue;
            }
            switch (score) {
                case 1 -> {
                    dislike++;
                    weightedScore -= 3;
                }
                case 2 -> {
                    like++;
                    weightedScore += 2;
                }
                case 3 -> {
                    superLike++;
                    weightedScore += 4;
                }
                case 4 -> {
                    pin++;
                    weightedScore += 8;
                }
                default -> {
                }
            }
        }
        return new VoteStats(dislike, like, superLike, pin, dislike + like + superLike + pin, weightedScore);
    }


    private record SlotKey(Integer dayNumber, Integer orderIndex) implements Comparable<SlotKey> {
        @Override
        public int compareTo(SlotKey other) {
            int dayCompare = compareNullable(this.dayNumber, other.dayNumber);
            if (dayCompare != 0) {
                return dayCompare;
            }
            return compareNullable(this.orderIndex, other.orderIndex);
        }

        private static int compareNullable(Integer left, Integer right) {
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            return Integer.compare(left, right);
        }
    }

    private record VoteStats(
            int dislikeCount,
            int likeCount,
            int superLikeCount,
            int pinCount,
            int actualVoteCount,
            int weightedScore
    ) {}


    private record FinalizeDraft(
            CandidateResponse existingResponse,
            FinalPlanAiService.FinalPlanRequest aiRequest
    ) {}

    private record FinalPlanBuild(
            String name,
            String concept,
            String splitReason,
            List<FinalPlaceSelection> selections
    ) {}

    private record FinalPlaceSelection(
            Place place,
            Integer dayNumber,
            Integer orderIndex,
            LocalTime visitTime,
            Integer durationMinutes
    ) {}

    private void validateOwner(Plan plan, User authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        if (plan.getOwner() == null || !plan.getOwner().getId().equals(authenticatedUser.getId())) {
            throw new AccessDeniedException("호스트만 접근할 수 있습니다.");
        }
    }

    private void claimOrValidateOwner(Plan plan, User authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        if (plan.getOwner() == null) {
            plan.assignOwner(authenticatedUser);
            ensureOwnerParticipant(plan, authenticatedUser);
            return;
        }
        validateOwner(plan, authenticatedUser);
        ensureOwnerParticipant(plan, authenticatedUser);
    }

    private void ensureOwnerParticipant(Plan plan, User owner) {
        if (!planParticipantRepository.existsByPlanIdAndUserId(plan.getId(), owner.getId())) {
            planParticipantRepository.save(PlanParticipant.builder()
                    .plan(plan)
                    .user(owner)
                    .role(ParticipantRole.OWNER)
                    .build());
        }
    }

    private List<Place> mockPlaces(PlanCandidate candidate) {
        boolean isA = "A".equals(candidate.getLabel());
        if (isA) {
            return List.of(
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(1)
                    .name("해운대 해수욕장").category("관광지").description("부산의 대표 해변에서 산책하며 시작")
                    .estimatedCost(0).durationMinutes(60).visitTime(LocalTime.of(9, 0))
                    .lat(new BigDecimal("35.158700")).lng(new BigDecimal("129.160400"))
                    .kakaoPlaceId("8007150").build(),
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(2)
                    .name("해운대 전통시장").category("식당").description("씨앗호떡, 유부전골 등 시장 먹거리 투어")
                    .estimatedCost(15000).durationMinutes(90).visitTime(LocalTime.of(10, 30))
                    .lat(new BigDecimal("35.163100")).lng(new BigDecimal("129.163900"))
                    .kakaoPlaceId("8007151").build(),
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(3)
                    .name("더베이101").category("카페").description("마린시티 뷰를 보며 카페 타임")
                    .estimatedCost(8000).durationMinutes(60).visitTime(LocalTime.of(13, 0))
                    .lat(new BigDecimal("35.155500")).lng(new BigDecimal("129.142400"))
                    .kakaoPlaceId("8007152").build(),
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(4)
                    .name("광안리 해수욕장").category("관광지").description("광안대교 야경 포인트, 일몰 시간에 맞춰 도착")
                    .estimatedCost(0).durationMinutes(120).visitTime(LocalTime.of(18, 0))
                    .lat(new BigDecimal("35.153200")).lng(new BigDecimal("129.118800"))
                    .kakaoPlaceId("8007153").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(1)
                    .name("감천문화마을").category("관광지").description("알록달록 벽화마을 포토스팟 탐방")
                    .estimatedCost(5000).durationMinutes(120).visitTime(LocalTime.of(9, 30))
                    .lat(new BigDecimal("35.097500")).lng(new BigDecimal("129.010800"))
                    .kakaoPlaceId("8007154").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(2)
                    .name("국제시장").category("식당").description("비빔당면, 씨앗호떡 등 부산 로컬 푸드")
                    .estimatedCost(20000).durationMinutes(90).visitTime(LocalTime.of(12, 0))
                    .lat(new BigDecimal("35.100800")).lng(new BigDecimal("129.029000"))
                    .kakaoPlaceId("8007155").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(3)
                    .name("자갈치시장").category("식당").description("회센터에서 신선한 회 한 접시")
                    .estimatedCost(30000).durationMinutes(90).visitTime(LocalTime.of(14, 30))
                    .lat(new BigDecimal("35.096800")).lng(new BigDecimal("129.030800"))
                    .kakaoPlaceId("8007156").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(4)
                    .name("흰여울문화마을").category("관광지").description("절영해안산책로 따라 걸으며 바다 뷰 감상")
                    .estimatedCost(0).durationMinutes(60).visitTime(LocalTime.of(17, 0))
                    .lat(new BigDecimal("35.078100")).lng(new BigDecimal("129.039400"))
                    .kakaoPlaceId("8007157").build(),
                Place.builder().candidate(candidate).dayNumber(3).orderIndex(1)
                    .name("이기대 해안산책로").category("액티비티").description("오륙도 방향 해안 트레킹 코스")
                    .estimatedCost(0).durationMinutes(120).visitTime(LocalTime.of(9, 0))
                    .lat(new BigDecimal("35.117000")).lng(new BigDecimal("129.123000"))
                    .kakaoPlaceId("8007158").build(),
                Place.builder().candidate(candidate).dayNumber(3).orderIndex(2)
                    .name("센텀시티 신세계").category("쇼핑").description("세계 최대 백화점에서 기념품 쇼핑")
                    .estimatedCost(50000).durationMinutes(120).visitTime(LocalTime.of(12, 0))
                    .lat(new BigDecimal("35.169500")).lng(new BigDecimal("129.131600"))
                    .kakaoPlaceId("8007159").build()
            );
        } else {
            return List.of(
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(1)
                    .name("돼지국밥골목").category("식당").description("부산 아침은 돼지국밥으로 시작")
                    .estimatedCost(8000).durationMinutes(60).visitTime(LocalTime.of(9, 0))
                    .lat(new BigDecimal("35.114500")).lng(new BigDecimal("129.040000"))
                    .kakaoPlaceId("9007150").build(),
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(2)
                    .name("부산역 초량 이바구길").category("관광지").description("168 계단 따라 올라가며 부산 원도심 풍경")
                    .estimatedCost(0).durationMinutes(90).visitTime(LocalTime.of(10, 30))
                    .lat(new BigDecimal("35.115100")).lng(new BigDecimal("129.042800"))
                    .kakaoPlaceId("9007151").build(),
                Place.builder().candidate(candidate).dayNumber(1).orderIndex(3)
                    .name("부평깡통시장").category("식당").description("야간 먹자골목에서 다양한 길거리 음식")
                    .estimatedCost(20000).durationMinutes(120).visitTime(LocalTime.of(18, 0))
                    .lat(new BigDecimal("35.100300")).lng(new BigDecimal("129.026700"))
                    .kakaoPlaceId("9007152").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(1)
                    .name("태종대").category("관광지").description("다누비열차 타고 절벽 해안 감상")
                    .estimatedCost(5000).durationMinutes(150).visitTime(LocalTime.of(10, 0))
                    .lat(new BigDecimal("35.051900")).lng(new BigDecimal("129.084800"))
                    .kakaoPlaceId("9007153").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(2)
                    .name("영도 대선횟집").category("식당").description("태종대 근처 현지인 추천 횟집")
                    .estimatedCost(35000).durationMinutes(90).visitTime(LocalTime.of(13, 0))
                    .lat(new BigDecimal("35.069000")).lng(new BigDecimal("129.071000"))
                    .kakaoPlaceId("9007154").build(),
                Place.builder().candidate(candidate).dayNumber(2).orderIndex(3)
                    .name("송도해상케이블카").category("액티비티").description("바다 위를 날아가는 케이블카 체험")
                    .estimatedCost(17000).durationMinutes(60).visitTime(LocalTime.of(15, 30))
                    .lat(new BigDecimal("35.076800")).lng(new BigDecimal("129.019900"))
                    .kakaoPlaceId("9007155").build(),
                Place.builder().candidate(candidate).dayNumber(3).orderIndex(1)
                    .name("해리단길").category("카페").description("감성 카페와 소품샵이 모여있는 골목")
                    .estimatedCost(7000).durationMinutes(90).visitTime(LocalTime.of(10, 0))
                    .lat(new BigDecimal("35.156500")).lng(new BigDecimal("129.135000"))
                    .kakaoPlaceId("9007156").build(),
                Place.builder().candidate(candidate).dayNumber(3).orderIndex(2)
                    .name("해운대 포장마차촌").category("식당").description("바다 보며 조개구이, 회 한 접시")
                    .estimatedCost(25000).durationMinutes(120).visitTime(LocalTime.of(17, 0))
                    .lat(new BigDecimal("35.159000")).lng(new BigDecimal("129.162000"))
                    .kakaoPlaceId("9007157").build()
            );
        }
    }
}
