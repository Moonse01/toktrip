package com.triplan.triplan.service;

import com.triplan.triplan.dto.OrderPreferenceRequest;
import com.triplan.triplan.dto.VoteBatchRequest;
import com.triplan.triplan.dto.VoteRequest;
import com.triplan.triplan.entity.Place;
import com.triplan.triplan.entity.PlanParticipant;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.entity.VoteFeedback;
import com.triplan.triplan.repository.PlaceRepository;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanParticipantRepository;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.VoteFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoteServiceBatchTest {

    @Mock
    private VoteFeedbackRepository voteFeedbackRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PlanCandidateRepository planCandidateRepository;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlanParticipantRepository planParticipantRepository;

    @Mock
    private VoterResolverService voterResolverService;

    @InjectMocks
    private VoteService voteService;

    @Test
    void savesMultiplePlaceVotesInOneRequest() {
        Plan plan = Plan.builder()
                .id(1L)
                .uuid("plan-uuid")
                .status(PlanStatus.VOTING)
                .build();
        PlanCandidate candidate = PlanCandidate.builder()
                .id(20L)
                .plan(plan)
                .label("A")
                .isFinal(false)
                .build();
        Place firstPlace = Place.builder()
                .id(101L)
                .candidate(candidate)
                .name("경복궁")
                .build();
        Place secondPlace = Place.builder()
                .id(102L)
                .candidate(candidate)
                .name("을지로")
                .build();
        User user = User.builder()
                .id(10L)
                .kakaoId("1234")
                .nickname("문석용")
                .build();

        when(planRepository.findByUuidForUpdate("plan-uuid")).thenReturn(Optional.of(plan));
        when(voterResolverService.resolve(user, null)).thenReturn(user);
        when(planCandidateRepository.findById(20L)).thenReturn(Optional.of(candidate));
        when(placeRepository.findById(101L)).thenReturn(Optional.of(firstPlace));
        when(placeRepository.findById(102L)).thenReturn(Optional.of(secondPlace));
        when(voteFeedbackRepository.findByUserIdAndPlaceId(10L, 101L)).thenReturn(Optional.empty());
        when(voteFeedbackRepository.findByUserIdAndPlaceId(10L, 102L)).thenReturn(Optional.empty());
        when(voteFeedbackRepository.save(any(VoteFeedback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = voteService.saveBatch(
                "plan-uuid",
                new VoteBatchRequest(
                        List.of(
                                new VoteRequest(20L, 101L, (byte) 2, null, null),
                                new VoteRequest(20L, 102L, (byte) 4, null, null)
                        ),
                        List.of()
                ),
                user,
                null
        );

        ArgumentCaptor<VoteFeedback> voteCaptor = ArgumentCaptor.forClass(VoteFeedback.class);
        ArgumentCaptor<PlanParticipant> participantCaptor = ArgumentCaptor.forClass(PlanParticipant.class);
        verify(voteFeedbackRepository, times(2)).save(voteCaptor.capture());
        verify(planParticipantRepository).save(participantCaptor.capture());

        assertThat(response.savedVoteCount()).isEqualTo(2);
        assertThat(response.savedOrderPreferenceCount()).isZero();
        assertThat(voteCaptor.getAllValues())
                .extracting(vote -> vote.getPlace().getId())
                .containsExactly(101L, 102L);
        assertThat(participantCaptor.getValue().getPlan().getId()).isEqualTo(1L);
        assertThat(participantCaptor.getValue().getUser().getId()).isEqualTo(10L);
    }

    @Test
    void savesOrderPreferencesInBatch() {
        Plan plan = Plan.builder()
                .id(1L)
                .uuid("plan-uuid")
                .status(PlanStatus.VOTING)
                .build();
        PlanCandidate candidate = PlanCandidate.builder()
                .id(20L)
                .plan(plan)
                .label("A")
                .isFinal(false)
                .build();
        Place place = Place.builder()
                .id(101L)
                .candidate(candidate)
                .name("경복궁")
                .build();
        User user = User.builder()
                .id(10L)
                .kakaoId("1234")
                .nickname("문석용")
                .build();

        when(planRepository.findByUuidForUpdate("plan-uuid")).thenReturn(Optional.of(plan));
        when(voterResolverService.resolve(user, null)).thenReturn(user);
        when(planCandidateRepository.findById(20L)).thenReturn(Optional.of(candidate));
        when(placeRepository.findById(101L)).thenReturn(Optional.of(place));
        when(voteFeedbackRepository.findByUserIdAndPlaceId(10L, 101L)).thenReturn(Optional.empty());
        when(voteFeedbackRepository.save(any(VoteFeedback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = voteService.saveBatch(
                "plan-uuid",
                new VoteBatchRequest(
                        List.of(),
                        List.of(new OrderPreferenceRequest(20L, 101L, 2, 1))
                ),
                user,
                null
        );

        ArgumentCaptor<VoteFeedback> voteCaptor = ArgumentCaptor.forClass(VoteFeedback.class);
        ArgumentCaptor<PlanParticipant> participantCaptor = ArgumentCaptor.forClass(PlanParticipant.class);
        verify(voteFeedbackRepository).save(voteCaptor.capture());
        verify(planParticipantRepository).save(participantCaptor.capture());

        assertThat(response.savedVoteCount()).isZero();
        assertThat(response.savedOrderPreferenceCount()).isEqualTo(1);
        assertThat(voteCaptor.getValue().getVoteScore()).isZero();
        assertThat(voteCaptor.getValue().getPreferredDayNumber()).isEqualTo(2);
        assertThat(voteCaptor.getValue().getPreferredOrderIndex()).isEqualTo(1);
        assertThat(participantCaptor.getValue().getPlan().getId()).isEqualTo(1L);
        assertThat(participantCaptor.getValue().getUser().getId()).isEqualTo(10L);
    }
}
