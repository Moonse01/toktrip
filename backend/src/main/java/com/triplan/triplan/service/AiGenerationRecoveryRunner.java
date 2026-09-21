package com.triplan.triplan.service;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.repository.PlaceRepository;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiGenerationRecoveryRunner implements ApplicationRunner {

    private final PlanRepository planRepository;
    private final PlaceRepository placeRepository;
    private final PlanCandidateRepository planCandidateRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Plan> interruptedPlans = planRepository.findByStatus(PlanStatus.AI_GENERATING);
        if (interruptedPlans.isEmpty()) {
            return;
        }

        for (Plan plan : interruptedPlans) {
            placeRepository.deleteNonFinalByPlanId(plan.getId());
            planCandidateRepository.deleteNonFinalByPlanId(plan.getId());
            plan.updateStatus(PlanStatus.DRAFT);
            planRepository.saveAndFlush(plan);
            log.warn("[AI] 서버 종료로 중단된 생성 작업을 DRAFT로 복구했습니다 - planId: {}", plan.getId());
        }
    }
}
