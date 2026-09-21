package com.triplan.triplan.service;

import com.triplan.triplan.dto.PlanCreateRequest;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.exception.BadRequestException;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanCommentRepository;
import com.triplan.triplan.repository.PlanParticipantRepository;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.PlaceRepository;
import com.triplan.triplan.repository.VoteFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceGenerateWithAiTest {

    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanCandidateRepository planCandidateRepository;
    @Mock
    private PlanParticipantRepository planParticipantRepository;
    @Mock
    private PlanCommentRepository planCommentRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private VoteFeedbackRepository voteFeedbackRepository;
    @Mock
    private AiGenerationService aiGenerationService;
    @Mock
    private FinalPlanAiService finalPlanAiService;
    @Mock
    private com.triplan.triplan.notification.NotificationService notificationService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PlanService planService;

    @BeforeEach
    void setUp() {
        planService = new PlanService(
                planRepository,
                planCandidateRepository,
                planParticipantRepository,
                planCommentRepository,
                placeRepository,
                voteFeedbackRepository,
                aiGenerationService,
                finalPlanAiService,
                notificationService,
                transactionManager
        );
    }

    @Test
    void rejectsGenerationWhenOnlyMissionWasProvided() {
        Plan plan = plan(null, "부산 1박 2일로 바다 산책과 맛집 중심 일정을 추천해줘.");
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> planService.generateWithAI(plan.getId(), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("여행 요청사항만으로는 대화 흐름을 알기 어려워요. 대화 파일을 올리거나 내용을 붙여넣어 주세요.");

        verify(aiGenerationService, never()).generateAsync(plan.getId());
    }

    @Test
    void rejectsGenerationWhenOnlyChatLogWasProvided() {
        Plan plan = plan("민수: 전주 한옥마을 가자\n지현: 비빔밥도 먹고 싶어", null);
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> planService.generateWithAI(plan.getId(), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("대화만으로는 여행 조건이 부족해요. 여행 요청사항에 기간, 예산, 꼭 넣을 곳을 함께 적어주세요.");

        verify(aiGenerationService, never()).generateAsync(plan.getId());
    }

    @Test
    void startsGenerationWhenChatLogAndMissionWereProvided() {
        Plan plan = plan(
                "민수: 전주 한옥마을 가자\n지현: 비빔밥도 먹고 싶어",
                "전주 당일치기, 현장 지출은 10만원 안쪽으로 맞춰줘."
        );
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));
        when(planCandidateRepository.findByPlanId(plan.getId())).thenReturn(List.of());

        planService.generateWithAI(plan.getId(), null);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.AI_GENERATING);
        verify(aiGenerationService).generateAsync(plan.getId());
    }

    @Test
    void rejectsGenerationWhenChatAndMissionAreBothBlank() {
        Plan plan = plan(" ", " ");
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> planService.generateWithAI(plan.getId(), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("대화 내용과 여행 요청사항을 함께 넣어주세요.");

        verify(aiGenerationService, never()).generateAsync(plan.getId());
    }

    @Test
    void rejectsPlanCreationWhenChatAndMissionAreBothBlank() {
        assertThatThrownBy(() -> planService.createPlan(new PlanCreateRequest(" ", " "), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("대화 내용과 여행 요청사항을 함께 넣어주세요.");

        verify(planRepository, never()).save(any(Plan.class));
    }

    @Test
    void rejectsPlanCreationWhenOnlyChatLogWasProvided() {
        assertThatThrownBy(() -> planService.createPlan(new PlanCreateRequest("대화 내용", " "), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("대화만으로는 여행 조건이 부족해요. 여행 요청사항에 기간, 예산, 꼭 넣을 곳을 함께 적어주세요.");

        verify(planRepository, never()).save(any(Plan.class));
    }

    @Test
    void rejectsPlanCreationWhenOnlyMissionWasProvided() {
        assertThatThrownBy(() -> planService.createPlan(new PlanCreateRequest(" ", "여행 요청사항"), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("여행 요청사항만으로는 대화 흐름을 알기 어려워요. 대화 파일을 올리거나 내용을 붙여넣어 주세요.");

        verify(planRepository, never()).save(any(Plan.class));
    }

    private Plan plan(String chatLog, String mission) {
        return Plan.builder()
                .id(10L)
                .uuid("plan-uuid")
                .status(PlanStatus.DRAFT)
                .chatLogText(chatLog)
                .mission(mission)
                .build();
    }
}
