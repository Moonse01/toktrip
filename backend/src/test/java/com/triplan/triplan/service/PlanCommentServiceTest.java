package com.triplan.triplan.service;

import com.triplan.triplan.dto.PlanCommentRequest;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanComment;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.PlanCommentRepository;
import com.triplan.triplan.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanCommentServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PlanCommentRepository planCommentRepository;

    @Mock
    private VoterResolverService voterResolverService;

    @InjectMocks
    private PlanCommentService planCommentService;

    @Test
    void savesGeneralCommentWithoutAnyPlaceVote() {
        Plan plan = Plan.builder()
                .id(1L)
                .uuid("plan-uuid")
                .status(PlanStatus.VOTING)
                .build();
        User user = User.builder()
                .id(3L)
                .kakaoId("1234")
                .nickname("문석용")
                .build();

        when(planRepository.findByUuidForUpdate("plan-uuid")).thenReturn(Optional.of(plan));
        when(voterResolverService.resolve(user, null)).thenReturn(user);
        when(planCommentRepository.save(any(PlanComment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = planCommentService.addComment(
                "plan-uuid",
                new PlanCommentRequest("  전체 일정은 조금 더 여유로우면 좋겠어요.  "),
                user,
                null
        );

        ArgumentCaptor<PlanComment> commentCaptor = ArgumentCaptor.forClass(PlanComment.class);
        verify(planCommentRepository).save(commentCaptor.capture());

        assertThat(commentCaptor.getValue().getContent()).isEqualTo("전체 일정은 조금 더 여유로우면 좋겠어요.");
        assertThat(response.content()).isEqualTo("전체 일정은 조금 더 여유로우면 좋겠어요.");
        assertThat(response.voterName()).isEqualTo("문석용");
    }

    @Test
    void rejectsCommentOutsideVotingPeriod() {
        Plan plan = Plan.builder()
                .id(1L)
                .uuid("plan-uuid")
                .status(PlanStatus.AI_DONE)
                .build();

        when(planRepository.findByUuidForUpdate("plan-uuid")).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> planCommentService.addComment(
                "plan-uuid",
                new PlanCommentRequest("의견입니다."),
                null,
                "guest-uuid"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("투표 기간");
    }
}
