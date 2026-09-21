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
