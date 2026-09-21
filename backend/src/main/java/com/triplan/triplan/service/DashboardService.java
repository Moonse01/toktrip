package com.triplan.triplan.service;

import com.triplan.triplan.dto.DashboardResponse;
import com.triplan.triplan.dto.ParticipantVoteItemResponse;
import com.triplan.triplan.dto.ParticipantVoteResponse;
import com.triplan.triplan.dto.OrderChangeResponse;
import com.triplan.triplan.dto.PlaceVoteStatResponse;
import com.triplan.triplan.dto.VoteCommentResponse;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanComment;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.entity.VoteFeedback;
import com.triplan.triplan.repository.PlanCommentRepository;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.PlanParticipantRepository;
import com.triplan.triplan.repository.VoteFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final PlanRepository planRepository;
    private final PlanParticipantRepository planParticipantRepository;
    private final VoteFeedbackRepository voteFeedbackRepository;
    private final PlanCommentRepository planCommentRepository;
    private static final DateTimeFormatter COMMENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String uuid, User authenticatedUser) {

        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));
        String myRole = resolveRole(plan, authenticatedUser);

        List<VoteFeedback> allVotes = voteFeedbackRepository.findByPlanId(plan.getId());
        List<VoteFeedback> actualVotes = allVotes.stream()
                .filter(this::isActualVote)
                .toList();
        List<PlanComment> planComments = planCommentRepository.findByPlanIdOrderByCreatedAtDesc(plan.getId());

        long votedParticipants = actualVotes.stream()
                .map(v -> v.getUser().getId())
                .distinct()
                .count();
        long registeredParticipants = planParticipantRepository.countByPlanId(plan.getId());
        long parsedParticipants = plan.getParticipantCount() != null ? plan.getParticipantCount() : 0;
        boolean participantCountKnown = parsedParticipants > 0 || registeredParticipants > 0;
        long totalParticipants = Math.max(
                Math.max(parsedParticipants, registeredParticipants),
                votedParticipants
        );

        // S4는 "몇 명이 참여했는지"와 "각 사람이 무엇을 눌렀는지"를 분리해서 보여준다.
        List<Map.Entry<Long, List<VoteFeedback>>> votesByUser = actualVotes.stream()
                .collect(Collectors.groupingBy(v -> v.getUser().getId()))
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().stream()
                        .map(VoteFeedback::getCreatedAt)
                        .min(Comparator.nullsLast(Comparator.naturalOrder()))
                        .orElse(null), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // 게스트가 S3에서 카드 순서를 바꾼 기록(원본 순서와 다른 preferredOrder)만 추린다.
        List<VoteFeedback> orderChangeVotes = allVotes.stream()
                .filter(v -> v.getPreferredOrderIndex() != null)
                .filter(v -> {
                    Integer origDay = v.getPlace().getDayNumber();
                    Integer origOrder = v.getPlace().getOrderIndex();
                    Integer prefDay = v.getPreferredDayNumber() != null ? v.getPreferredDayNumber() : origDay;
                    return !java.util.Objects.equals(origDay, prefDay)
                            || !java.util.Objects.equals(origOrder, v.getPreferredOrderIndex());
                })
                .toList();

        Map<Long, String> voterDisplayNames = buildVoterDisplayNames(
                Stream.of(
                                actualVotes.stream().map(VoteFeedback::getUser),
                                planComments.stream().map(PlanComment::getUser),
                                orderChangeVotes.stream().map(VoteFeedback::getUser)
                        )
                        .flatMap(Function.identity())
                        .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left))
                        .values()
                        .stream()
                        .sorted(Comparator.comparing(User::getId))
                        .toList()
        );
        List<ParticipantVoteResponse> participants = buildParticipants(votesByUser, voterDisplayNames);

        Map<Long, List<VoteFeedback>> votesByPlace = actualVotes.stream()
                .collect(Collectors.groupingBy(v -> v.getPlace().getId()));

        List<PlaceVoteStatResponse> placeStats = votesByPlace.entrySet().stream()
                .map(entry -> {
                    Long placeId = entry.getKey();
                    List<VoteFeedback> votes = entry.getValue();

                    String placeName = votes.get(0).getPlace().getName();
                    String candidateLabel = votes.get(0).getCandidate().getLabel();
                    Integer dayNumber = votes.get(0).getPlace().getDayNumber();
                    Integer orderIndex = votes.get(0).getPlace().getOrderIndex();

                    long dislike = votes.stream().filter(v -> v.getVoteScore() == 1).count();
                    long like = votes.stream().filter(v -> v.getVoteScore() == 2).count();
                    long superLike = votes.stream().filter(v -> v.getVoteScore() == 3).count();
                    long pin = votes.stream().filter(v -> v.getVoteScore() == 4).count();

                    return new PlaceVoteStatResponse(
                            placeId, placeName, candidateLabel, dayNumber, orderIndex,
                            dislike, like, superLike, pin,
                            (long) votes.size()
                    );
                })
                .sorted(Comparator
                        .comparing(PlaceVoteStatResponse::candidateLabel, Comparator.nullsLast(String::compareTo))
                        .thenComparing(PlaceVoteStatResponse::dayNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PlaceVoteStatResponse::orderIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        List<VoteCommentResponse> comments = planComments.stream()
                .map(comment -> new VoteCommentResponse(
                        comment.getId(),
                        comment.getContent(),
                        voterDisplayNames.getOrDefault(comment.getUser().getId(), "참여자"),
                        comment.getUpdatedAt() != null ? comment.getUpdatedAt().format(COMMENT_TIME_FORMATTER) : null
                ))
                .toList();

        List<OrderChangeResponse> orderChanges = orderChangeVotes.stream()
                .map(v -> new OrderChangeResponse(
                        v.getPlace().getId(),
                        v.getPlace().getName(),
                        v.getCandidate().getLabel(),
                        v.getPlace().getDayNumber(),
                        v.getPlace().getOrderIndex(),
                        v.getPreferredDayNumber() != null ? v.getPreferredDayNumber() : v.getPlace().getDayNumber(),
                        v.getPreferredOrderIndex(),
                        voterDisplayNames.getOrDefault(v.getUser().getId(), "참여자")
                ))
                .sorted(Comparator
                        .comparing(OrderChangeResponse::candidateLabel, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OrderChangeResponse::toDayNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(OrderChangeResponse::toOrderIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        return new DashboardResponse(
                plan.getId(),
                plan.getUuid(),
                plan.getStatus().name(),
                totalParticipants,
                votedParticipants,
                participantCountKnown,
                placeStats,
                participants,
                comments,
                orderChanges,
                myRole
        );
    }

    private Map<Long, String> buildVoterDisplayNames(List<User> users) {
        Map<Long, String> names = new HashMap<>();
        int guestNumber = 1;
        for (User user : users) {
            // 비회원은 카카오 닉네임이 없으므로 투표 참여 순서대로 게스트1, 게스트2처럼 표시한다.
            if (isGuest(user)) {
                names.put(user.getId(), "게스트" + guestNumber);
                guestNumber++;
            } else {
                String nickname = user.getNickname();
                names.put(user.getId(), nickname != null && !nickname.isBlank() ? nickname : "회원");
            }
        }
        return names;
    }

    private List<ParticipantVoteResponse> buildParticipants(
            List<Map.Entry<Long, List<VoteFeedback>>> votesByUser,
            Map<Long, String> voterDisplayNames) {
        return votesByUser.stream()
                .map(entry -> {
                    List<VoteFeedback> votes = entry.getValue().stream()
                            .sorted(Comparator
                                    .comparing((VoteFeedback v) -> v.getCandidate().getLabel(), Comparator.nullsLast(String::compareTo))
                                    .thenComparing(v -> v.getPlace().getDayNumber(), Comparator.nullsLast(Integer::compareTo))
                                    .thenComparing(v -> v.getPlace().getOrderIndex(), Comparator.nullsLast(Integer::compareTo)))
                            .toList();
                    List<ParticipantVoteItemResponse> voteItems = votes.stream()
                            .map(v -> new ParticipantVoteItemResponse(
                                    v.getPlace().getId(),
                                    v.getPlace().getName(),
                                    v.getCandidate().getLabel(),
                                    v.getPlace().getDayNumber(),
                                    v.getPlace().getOrderIndex(),
                                    v.getVoteScore(),
                                    null,
                                    null
                            ))
                            .toList();
                    return new ParticipantVoteResponse(
                            entry.getKey(),
                            voterDisplayNames.getOrDefault(entry.getKey(), "참여자"),
                            votes.stream().filter(this::isActualVote).count(),
                            voteItems
                    );
                })
                .toList();
    }

    private boolean isGuest(User user) {
        return user.getKakaoId() != null && user.getKakaoId().startsWith("guest:");
    }

    private boolean isActualVote(VoteFeedback voteFeedback) {
        Byte voteScore = voteFeedback.getVoteScore();
        return voteScore != null && voteScore >= 1 && voteScore <= 4;
    }

    private String resolveRole(Plan plan, User authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        if (plan.getOwner() != null && plan.getOwner().getId().equals(authenticatedUser.getId())) {
            return "HOST";
        }
        if (planParticipantRepository.existsByPlanIdAndUserId(plan.getId(), authenticatedUser.getId())) {
            return "GUEST";
        }
        throw new AccessDeniedException("이 플랜의 참여자만 대시보드에 접근할 수 있습니다.");
    }
}
