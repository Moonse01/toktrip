package com.triplan.triplan.scheduler;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.VoteFeedback;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.VoteFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "triplan.voting.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VotingCheckScheduler {

    private final PlanRepository planRepository;
    private final VoteFeedbackRepository voteFeedbackRepository;

    // 10분마다 실행
    @Scheduled(fixedRate = 60000) //테스트용으로 1분으로 함 나중에 10분이나 다른 시간대로 변경 필요
    public void checkMajorityVoting() {
        // VOTING 상태인 플랜만 조회
        List<Plan> votingPlans = planRepository.findByStatus(PlanStatus.VOTING);

        for (Plan plan : votingPlans) {
            int totalParticipants = plan.getParticipantCount() != null ? plan.getParticipantCount() : 0;

            if (totalParticipants == 0) {
                continue;
            }

            // 투표한 유저 수
            List<VoteFeedback> votes = voteFeedbackRepository.findByPlanId(plan.getId());
            long votedCount = votes.stream()
                    .filter(v -> v.getVoteScore() != null && v.getVoteScore() >= 1 && v.getVoteScore() <= 4)
                    .map(v -> v.getUser().getId())
                    .distinct()
                    .count();

            // 과반수 체크
            if (votedCount >= Math.ceil(totalParticipants / 2.0)) {
                log.info("[Scheduler] 과반수 달성! planId={}, 투표={}/{}",
                        plan.getId(), votedCount, totalParticipants);

                // TODO: 카카오 알림톡 발송 (7주차)
                // 지금은 로그만 출력
            }
        }
    }
}
