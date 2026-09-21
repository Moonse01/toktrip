# 실제 코드로 읽는 TokTrip

[프로젝트 소개](../README.md) · [개인 기여](contribution.md) · [시스템 구조](architecture.md)

원본 저장소 접근 권한 없이 핵심 구현을 읽을 수 있도록, **2026-09-21 로컬 작업 트리에서 직접 발췌한 코드**입니다. 설명을 위한 의사코드가 아니며, 각 블록은 주변 의존성 없이 실행되는 완전한 파일은 아닙니다.

원본 프로젝트의 패키지명 `com.triplan.triplan`은 개발 당시 명칭이라 그대로 남아 있습니다. 설정 파일·인증정보·실제 사용자 대화는 포함하지 않았습니다. 이 문서는 **구현 근거**이고, 개인 기여의 맥락은 [기여 문서](contribution.md), 실행 검증 범위는 [구조 문서](architecture.md#확인한-범위와-남은-과제)에서 확인할 수 있습니다.

<a id="ai"></a>

## 1. A안 결과를 B안의 입력으로 연결하기

**사용자 기능:** 서로 다른 여행 후보를 비교합니다.

원본: `triplan/src/main/java/com/triplan/triplan/service/AiGenerationService.java`, 141–180행.

```java
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
```

A/B를 서로 무관하게 생성하면 장소가 비슷해질 수 있어, A안의 장소 목록을 B안의 제외 목록으로 전달했습니다. 숙소·이동은 제외 목록에서 예외로 처리합니다. 이 방식은 중복률 0%를 보장한다는 성능 수치가 아닙니다.

### 생성 결과의 검증·보정·대체

같은 파일의 `callPlannerWithValidation`, 292–402행입니다. `PLANNER_MAX_ATTEMPTS`는 2입니다.

```java
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
```

**읽는 순서:** JSON 변환 → 검증 → 코드로 보정 가능한 데이터 보정 → 재검증 → 수정 요청 → 제한 횟수 뒤 fallback.

LLM 호출만 반복하지 않고, 일정 데이터에서 고칠 수 있는 부분은 코드로 먼저 보정합니다. 긴 실제 대화의 모든 필수 조건을 보존하거나 최신 메뉴·가격을 보장하는 기능은 아닙니다.

<a id="vote"></a>

## 2. 로그인 전 선택 보존 → 로그인 후 제출 → 재투표 갱신

**사용자 기능:** 먼저 일정을 보고 선택한 뒤 로그인해 제출합니다.

원본 프론트: `src/pages/S3VotePage.jsx`, 336–400행.

```jsx
// 로그인 후 돌아왔을 때 pending 투표 일괄 전송
  // pendingKey는 전송 성공 시에만 제거한다 — 도중 리로드돼도 다음 로드에서 재시도 가능.
  // 중복 전송은 백엔드 UPSERT로 안전하므로 localStorage 잠금 대신 ref로 이중 실행만 막는다.
  const pendingSubmitRef = useRef(false);
  useEffect(() => {
    const pendingVoteKey = `pending_submit_${activePlanUuid}`;
    const pendingCommentKey = `pending_comment_${activePlanUuid}`;
    const hasPendingVotes = Boolean(localStorage.getItem(pendingVoteKey));
    const savedComment = (localStorage.getItem(pendingCommentKey) || '').trim();
    if (!user || !candidates.length || (!hasPendingVotes && !savedComment)) return;
    if (pendingSubmitRef.current) return;
    pendingSubmitRef.current = true;

    const savedVotes = localStorage.getItem(`votes_${activePlanUuid}`);
    const parsedVotes = savedVotes ? JSON.parse(savedVotes) : {};
    const changedOrder = keepMovedOnly(diffOrderPreferences(candidates, originalOrderRef.current), movedPlaceIdsRef.current);
    const prefByPlaceId = orderPreferencesByPlaceId(changedOrder);
    const voteEntries = [];
    candidates.forEach(cand => {
      cand.places.forEach(place => {
        const voteState = parsedVotes[place.id];
        if (voteState && voteState.myVote !== null) {
          const original = originalOrderRef.current.get(Number(place.id));
          const pref = prefByPlaceId.get(Number(place.id));
          voteEntries.push({
            candidateId: original?.candidateId ?? cand.id,
            placeId: place.id,
            voteScore: voteState.myVote,
            preferredDayNumber: pref ? pref.preferredDayNumber : null,
            preferredOrderIndex: pref ? pref.preferredOrderIndex : null,
          });
        }
      });
    });
    const votedPlaceIds = new Set(voteEntries.map(entry => Number(entry.placeId)));
    const orderOnlyEntries = changedOrder.filter(pref => !votedPlaceIds.has(Number(pref.placeId)));

    (async () => {
      try {
        if (hasPendingVotes && (voteEntries.length > 0 || orderOnlyEntries.length > 0)) {
          await submitVotes(activePlanUuid, {
            votes: voteEntries,
            orderPreferences: toBatchOrderPreferences(orderOnlyEntries),
          }, null);
          localStorage.removeItem(pendingVoteKey);
          localStorage.removeItem(`votes_${activePlanUuid}`);
          localStorage.removeItem(`order_${activePlanUuid}`);
          localStorage.removeItem(`moved_${activePlanUuid}`);
          movedPlaceIdsRef.current = new Set();
          setShowLoginModal(true);
        }
        if (savedComment) {
          await submitPlanComment(activePlanUuid, savedComment, null);
          localStorage.removeItem(pendingCommentKey);
          showToast('의견이 등록되었습니다.');
        }
      } catch (err) {
        console.error('로그인 후 저장 실패:', err);
        showToast('저장에 실패했습니다. 잠시 후 다시 시도해주세요.', 'error');
      } finally {
        pendingSubmitRef.current = false;
      }
    })();
  }, [user, candidates, activePlanUuid, showToast]);
```

로그인 성공 뒤에 저장된 선택을 한 번에 전송하고, 성공한 데이터만 `localStorage`에서 지웁니다. `pendingSubmitRef`는 이 컴포넌트 내 중복 실행을 막는 장치이며 여러 기기의 분산 잠금은 아닙니다.

원본 백엔드: `triplan/src/main/java/com/triplan/triplan/service/VoteService.java`, `saveBatch`·`saveVote` 중심 발췌.

```java
package com.triplan.triplan.service;

import com.triplan.triplan.dto.OrderPreferenceRequest;
import com.triplan.triplan.dto.VoteBatchRequest;
import com.triplan.triplan.dto.VoteBatchResponse;
import com.triplan.triplan.dto.VoteRequest;
import com.triplan.triplan.dto.VoteResponse;
import com.triplan.triplan.entity.*;
import com.triplan.triplan.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VoteService {

    private final VoteFeedbackRepository voteFeedbackRepository;
    private final PlanRepository planRepository;
    private final PlanCandidateRepository planCandidateRepository;
    private final PlaceRepository placeRepository;
    private final PlanParticipantRepository planParticipantRepository;
    private final VoterResolverService voterResolverService;

    @Transactional
    public VoteResponse vote(String planUuid, VoteRequest request, User authenticatedUser, String guestUuid) {

        Plan plan = planRepository.findByUuidForUpdate(planUuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planUuid));
        validateVotingOpen(plan);

        User user = voterResolverService.resolve(authenticatedUser, guestUuid);
        ensureParticipant(plan, user);
        return saveVote(plan, request, user);
    }

    @Transactional
    public VoteBatchResponse saveBatch(
            String planUuid,
            VoteBatchRequest request,
            User authenticatedUser,
            String guestUuid
    ) {
        Plan plan = planRepository.findByUuidForUpdate(planUuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planUuid));
        validateVotingOpen(plan);

        User user = voterResolverService.resolve(authenticatedUser, guestUuid);
        ensureParticipant(plan, user);
        request.votes().forEach(voteRequest -> saveVote(plan, voteRequest, user));
        request.orderPreferences().forEach(orderRequest -> saveOrderPreference(plan, orderRequest, user));

        return new VoteBatchResponse(request.votes().size(), request.orderPreferences().size());
    }

    @Transactional
    public List<VoteResponse> saveOrderPreferences(
            String planUuid,
            List<OrderPreferenceRequest> requests,
            User authenticatedUser,
            String guestUuid
    ) {
        Plan plan = planRepository.findByUuidForUpdate(planUuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planUuid));
        validateVotingOpen(plan);

        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        User user = voterResolverService.resolve(authenticatedUser, guestUuid);
        return requests.stream()
                .map(request -> saveOrderPreference(plan, request, user))
                .toList();
    }

    private VoteResponse saveVote(Plan plan, VoteRequest request, User user) {
        PlanCandidate candidate = planCandidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new IllegalArgumentException("후보안을 찾을 수 없습니다: " + request.candidateId()));

        Place place = placeRepository.findById(request.placeId())
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + request.placeId()));
        validateVoteTarget(plan, candidate, place);

        Optional<VoteFeedback> existing = voteFeedbackRepository.findByUserIdAndPlaceId(user.getId(), request.placeId());

        VoteFeedback voteFeedback;
        boolean isUpdate;

        if (existing.isPresent()) {
            voteFeedback = existing.get();
            voteFeedback.updateVote(
                    request.voteScore(),
                    null,
                    request.preferredDayNumber(),
                    request.preferredOrderIndex()
            );
            isUpdate = true;
        } else {
            voteFeedback = VoteFeedback.builder()
                    .plan(plan)
                    .user(user)
                    .candidate(candidate)
                    .place(place)
                    .voteScore(request.voteScore())
                    .preferredDayNumber(request.preferredDayNumber())
                    .preferredOrderIndex(request.preferredOrderIndex())
                    .build();
            voteFeedbackRepository.save(voteFeedback);
            isUpdate = false;
        }

        return new VoteResponse(
                voteFeedback.getId(),
                voteFeedback.getPlace().getId(),
                voteFeedback.getVoteScore(),
                null,
                isUpdate ? "투표가 수정되었습니다." : "투표가 등록되었습니다."
        );
    }

    private VoteResponse saveOrderPreference(Plan plan, OrderPreferenceRequest request, User user) {
        PlanCandidate candidate = planCandidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new IllegalArgumentException("후보안을 찾을 수 없습니다: " + request.candidateId()));

        Place place = placeRepository.findById(request.placeId())
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + request.placeId()));
        validateVoteTarget(plan, candidate, place);

        Optional<VoteFeedback> existing = voteFeedbackRepository.findByUserIdAndPlaceId(user.getId(), request.placeId());
        VoteFeedback voteFeedback;
        boolean isUpdate;

        if (existing.isPresent()) {
            voteFeedback = existing.get();
            voteFeedback.updateOrderPreference(request.preferredDayNumber(), request.preferredOrderIndex());
            isUpdate = true;
        } else {
            voteFeedback = VoteFeedback.builder()
                    .plan(plan)
                    .user(user)
                    .candidate(candidate)
                    .place(place)
                    .voteScore((byte) 0)
                    .preferredDayNumber(request.preferredDayNumber())
                    .preferredOrderIndex(request.preferredOrderIndex())
                    .build();
            voteFeedbackRepository.save(voteFeedback);
            isUpdate = false;
        }

        return new VoteResponse(
                voteFeedback.getId(),
                voteFeedback.getPlace().getId(),
                voteFeedback.getVoteScore(),
                null,
                isUpdate ? "순서 선호가 수정되었습니다." : "순서 선호가 등록되었습니다."
        );
    }

    private void ensureParticipant(Plan plan, User user) {
        if (!planParticipantRepository.existsByPlanIdAndUserId(plan.getId(), user.getId())) {
            planParticipantRepository.save(PlanParticipant.builder()
                    .plan(plan)
                    .user(user)
                    .role(ParticipantRole.MEMBER)
                    .build());
        }
    }

    private void validateVotingOpen(Plan plan) {
        if (plan.getStatus() != PlanStatus.VOTING) {
            throw new IllegalStateException("투표 기간이 아닙니다.");
        }
    }

    private void validateVoteTarget(Plan plan, PlanCandidate candidate, Place place) {
        if (candidate.getPlan() == null || !candidate.getPlan().getId().equals(plan.getId())) {
            throw new AccessDeniedException("해당 플랜의 후보안에만 투표할 수 있습니다.");
        }
        if (Boolean.TRUE.equals(candidate.getIsFinal())) {
            throw new AccessDeniedException("추천 일정에는 투표할 수 없습니다.");
        }
        if (place.getCandidate() == null || !place.getCandidate().getId().equals(candidate.getId())) {
            throw new AccessDeniedException("해당 후보안의 장소에만 투표할 수 있습니다.");
        }
    }

}
```

**읽는 순서:** 플랜 잠금·상태 검사 → 후보·장소 소속 검사 → 사용자·장소 기존 행 조회 → 수정 또는 생성.

SQL의 `ON DUPLICATE KEY UPDATE`를 직접 호출한 것이 아니라, 기존 행을 조회해 JPA 엔티티를 갱신하거나 새로 저장합니다. 임시 브라우저 선택 보존은 DB 게스트 투표를 회원 계정으로 병합하는 기능과 다릅니다.

<a id="final"></a>

## 3. 최종 D안: 기존 장소 재구성과 트랜잭션 경계

**사용자 기능:** 호스트가 장소별 투표와 의견을 보고 최종 일정 생성을 요청합니다.

원본: `triplan/src/main/java/com/triplan/triplan/service/PlanService.java`, 400–473행.

```java
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
```

**읽는 순서:** 준비 조회 트랜잭션 → 트랜잭션 밖 AI 호출 → 저장 트랜잭션. 저장 단계에서 `findByUuidForUpdate` 뒤에 확정 상태를 다시 검사합니다.

저장 시 이미 확정된 경우 기존 결과를 반환합니다. 다만 저장 전에 두 요청이 각각 AI를 호출할 수 있어, 중복 AI 비용 방지까지 보장하지 않습니다.

### AI가 돌려준 장소 ID를 기존 데이터와 대조

```java
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
```

후보에 없는 ID·중복 ID·고정 장소 누락은 AI 결과를 채택하지 않는 조건입니다. D안에는 AI가 새 장소명·좌표·비용을 만들기보다 기존 A/B 장소 데이터를 사용합니다. 최종안이 반드시 A/B를 같은 비율로 섞는 것은 아닙니다.

---

## 이 코드가 보여주는 범위

| 구분 | 내용 |
| :--- | :--- |
| 구현 | 제외 목록 전달, 검증·보정·대체, 로그인 복귀 제출, 재투표 갱신, D안 저장 시 상태 재검사 |
| 실행 검증 | 이번 공개 문서 작업에서 AI 호출·서버 배포·동시성 시험을 다시 실행하지 않음 |
| 측정하지 않은 것 | 모델 정확도 개선률, 합의 시간 단축, 로그인 전환율, AI 비용 절감률 |
