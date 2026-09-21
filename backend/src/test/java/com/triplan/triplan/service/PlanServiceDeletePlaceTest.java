package com.triplan.triplan.service;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.Place;
import com.triplan.triplan.entity.User;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceDeletePlaceTest {

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
    void deletesPlaceReordersDayAndRecalculatesCandidateBudget() {
        Fixture fixture = fixture(PlanStatus.AI_DONE);
        Place deleted = place(21L, fixture.candidate(), 1, 2, "카페", 7_000);
        Place first = place(20L, fixture.candidate(), 1, 1, "관광지", 0);
        Place third = place(22L, fixture.candidate(), 1, 3, "식당", 13_000);
        Place nextDay = place(23L, fixture.candidate(), 2, 1, "카페", 5_000);

        when(planCandidateRepository.findById(fixture.candidate().getId()))
                .thenReturn(Optional.of(fixture.candidate()));
        when(placeRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
        when(placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(fixture.candidate().getId()))
                .thenReturn(List.of(first, third, nextDay));

        planService.deletePlace(fixture.plan().getUuid(), fixture.candidate().getId(), deleted.getId(), fixture.owner());

        verify(placeRepository).delete(deleted);
        verify(placeRepository).flush();
        assertThat(first.getOrderIndex()).isEqualTo(1);
        assertThat(third.getOrderIndex()).isEqualTo(2);
        assertThat(nextDay.getOrderIndex()).isEqualTo(1);
        assertThat(fixture.candidate().getEstimatedCostPerPerson()).isEqualTo(18_000);
    }

    @Test
    void rejectsProtectedAccommodation() {
        Fixture fixture = fixture(PlanStatus.AI_DONE);
        Place accommodation = place(21L, fixture.candidate(), 1, 2, "숙소", 0);
        when(planCandidateRepository.findById(fixture.candidate().getId()))
                .thenReturn(Optional.of(fixture.candidate()));
        when(placeRepository.findById(accommodation.getId())).thenReturn(Optional.of(accommodation));

        assertThatThrownBy(() ->
                planService.deletePlace(fixture.plan().getUuid(), fixture.candidate().getId(), accommodation.getId(), fixture.owner()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("숙소와 이동 거점은 삭제할 수 없습니다.");

        verify(placeRepository, never()).delete(accommodation);
    }

    @Test
    void deletesFinalPlaceAfterPlanIsConfirmed() {
        Fixture fixture = fixture(PlanStatus.CONFIRMED, true);
        Place deleted = place(31L, fixture.candidate(), 1, 2, "카페", 7_000);
        Place first = place(30L, fixture.candidate(), 1, 1, "관광지", 0);
        Place third = place(32L, fixture.candidate(), 1, 3, "식당", 13_000);

        when(planCandidateRepository.findById(fixture.candidate().getId()))
                .thenReturn(Optional.of(fixture.candidate()));
        when(placeRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
        when(placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(fixture.candidate().getId()))
                .thenReturn(List.of(first, third));

        planService.deletePlace(fixture.plan().getUuid(), fixture.candidate().getId(), deleted.getId(), fixture.owner());

        verify(placeRepository).delete(deleted);
        assertThat(first.getOrderIndex()).isEqualTo(1);
        assertThat(third.getOrderIndex()).isEqualTo(2);
        assertThat(fixture.candidate().getEstimatedCostPerPerson()).isEqualTo(13_000);
    }

    @Test
    void allowsDeletionDuringVoting() {
        Fixture fixture = fixture(PlanStatus.VOTING);
        Place deleted = place(21L, fixture.candidate(), 1, 2, "카페", 7_000);
        Place first = place(20L, fixture.candidate(), 1, 1, "관광지", 0);

        when(planCandidateRepository.findById(fixture.candidate().getId()))
                .thenReturn(Optional.of(fixture.candidate()));
        when(placeRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
        when(placeRepository.findByCandidateIdOrderByDayNumberAscOrderIndexAsc(fixture.candidate().getId()))
                .thenReturn(List.of(first));

        planService.deletePlace(fixture.plan().getUuid(), fixture.candidate().getId(), deleted.getId(), fixture.owner());

        verify(placeRepository).delete(deleted);
    }

    @Test
    void rejectsDraftCandidateDeletionAfterConfirmed() {
        Fixture fixture = fixture(PlanStatus.CONFIRMED);
        when(planCandidateRepository.findById(fixture.candidate().getId()))
                .thenReturn(Optional.of(fixture.candidate()));

        assertThatThrownBy(() ->
                planService.deletePlace(fixture.plan().getUuid(), fixture.candidate().getId(), 21L, fixture.owner()))
                .isInstanceOf(IllegalStateException.class);

        verify(placeRepository, never()).findById(21L);
    }

    private Fixture fixture(PlanStatus status) {
        return fixture(status, false);
    }

    private Fixture fixture(PlanStatus status, boolean isFinal) {
        User owner = User.builder().id(1L).kakaoId("owner").build();
        Plan plan = Plan.builder().id(10L).uuid("plan-uuid").owner(owner).status(status).build();
        PlanCandidate candidate = PlanCandidate.builder()
                .id(11L)
                .plan(plan)
                .label(isFinal ? "D" : "A")
                .estimatedCostPerPerson(99_000)
                .isFinal(isFinal)
                .build();

        when(planRepository.findByUuid(plan.getUuid())).thenReturn(Optional.of(plan));
        when(planParticipantRepository.existsByPlanIdAndUserId(plan.getId(), owner.getId())).thenReturn(true);
        return new Fixture(owner, plan, candidate);
    }

    private Place place(Long id, PlanCandidate candidate, int dayNumber, int orderIndex, String category, int cost) {
        return Place.builder()
                .id(id)
                .candidate(candidate)
                .dayNumber(dayNumber)
                .orderIndex(orderIndex)
                .name(category + "-" + id)
                .category(category)
                .estimatedCost(cost)
                .build();
    }

    private record Fixture(User owner, Plan plan, PlanCandidate candidate) {
    }
}
