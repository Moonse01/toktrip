package com.triplan.triplan.service;

import com.triplan.triplan.dto.PlanCommentRequest;
import com.triplan.triplan.dto.PlanCommentResponse;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanComment;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.PlanCommentRepository;
import com.triplan.triplan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class PlanCommentService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PlanRepository planRepository;
    private final PlanCommentRepository planCommentRepository;
    private final VoterResolverService voterResolverService;

    @Transactional
    public PlanCommentResponse addComment(
            String planUuid,
            PlanCommentRequest request,
            User authenticatedUser,
            String guestUuid
    ) {
        Plan plan = planRepository.findByUuidForUpdate(planUuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + planUuid));
        if (plan.getStatus() != PlanStatus.VOTING) {
            throw new IllegalStateException("투표 기간에만 의견을 남길 수 있습니다.");
        }

        User user = voterResolverService.resolve(authenticatedUser, guestUuid);
        PlanComment comment = planCommentRepository.save(PlanComment.builder()
                .plan(plan)
                .user(user)
                .content(request.content().trim())
                .build());

        return new PlanCommentResponse(
                comment.getId(),
                comment.getContent(),
                displayName(user),
                comment.getUpdatedAt() != null ? comment.getUpdatedAt().format(TIME_FORMATTER) : null
        );
    }

    private String displayName(User user) {
        if (user.getKakaoId() != null && user.getKakaoId().startsWith("guest:")) {
            return "게스트";
        }
        return user.getNickname() != null && !user.getNickname().isBlank() ? user.getNickname() : "회원";
    }
}
