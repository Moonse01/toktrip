package com.triplan.triplan.controller;

import com.triplan.triplan.dto.ApiResponse;
import com.triplan.triplan.dto.PlanListItemResponse;
import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanCandidate;
import com.triplan.triplan.entity.PlanParticipant;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.PlanCandidateRepository;
import com.triplan.triplan.repository.PlanParticipantRepository;
import com.triplan.triplan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final PlanRepository planRepository;
    private final PlanCandidateRepository planCandidateRepository;
    private final PlanParticipantRepository planParticipantRepository;

    @GetMapping("/me/dashboard")
    public ResponseEntity<Map<String, Object>> getMyDashboard(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "id", user.getId(),
                "nickname", user.getNickname() != null ? user.getNickname() : "",
                "profileImageUrl", user.getProfileImageUrl() != null ? user.getProfileImageUrl() : ""
        ));
    }

    @Transactional(readOnly = true)
    @GetMapping("/me/plans")
    public ResponseEntity<ApiResponse<List<PlanListItemResponse>>> getMyPlans(@AuthenticationPrincipal User user) {
        Long userId = user.getId();

        // owner인 플랜 (HOST)
        Map<Long, PlanListItemResponse> planMap = new LinkedHashMap<>();
        for (Plan plan : planRepository.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            List<PlanCandidate> candidates = planCandidateRepository.findByPlanId(plan.getId());
            planMap.put(plan.getId(), PlanListItemResponse.from(plan, candidates, "HOST"));
        }

        // participant인 플랜 (GUEST) — 이미 owner로 들어간 건 제외
        for (PlanParticipant pp : planParticipantRepository.findByUserIdOrderByJoinedAtDesc(userId)) {
            Plan plan = pp.getPlan();
            if (planMap.containsKey(plan.getId())) continue;
            List<PlanCandidate> candidates = planCandidateRepository.findByPlanId(plan.getId());
            planMap.put(plan.getId(), PlanListItemResponse.from(plan, candidates, "GUEST"));
        }

        return ResponseEntity.ok(ApiResponse.ok(List.copyOf(planMap.values())));
    }
}
