package com.triplan.triplan.service;

import com.triplan.triplan.dto.CandidateResponse;
import com.triplan.triplan.dto.PlaceResponse;
import com.triplan.triplan.entity.Place;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;
import com.triplan.triplan.repository.PlaceRepository;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CandidateService {

    private final PlanRepository planRepository;
    private final PlanCandidateRepository planCandidateRepository;
    private final PlaceRepository placeRepository;

    @Transactional(readOnly = true)
    public List<CandidateResponse> getCandidates(String uuid) {
        Plan plan = planRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("플랜을 찾을 수 없습니다: " + uuid));

        List<PlanCandidate> candidates = planCandidateRepository.findByPlanIdAndIsFinalFalse(plan.getId());

        return candidates.stream()
                .map(candidate -> {
                    List<PlaceResponse> places = placeRepository
                            .findByCandidateIdOrderByDayNumberAscOrderIndexAsc(candidate.getId())
                            .stream()
                            .map(PlaceResponse::from)
                            .toList();
                    return CandidateResponse.of(candidate, places);
                })
                .toList();
    }
}