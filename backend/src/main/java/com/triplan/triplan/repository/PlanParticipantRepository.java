package com.triplan.triplan.repository;

import com.triplan.triplan.entity.PlanParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanParticipantRepository extends JpaRepository<PlanParticipant, Long> {
    List<PlanParticipant> findByPlanId(Long planId);
    long countByPlanId(Long planId);
    boolean existsByPlanIdAndUserId(Long planId, Long userId);
    Optional<PlanParticipant> findByPlanIdAndUserId(Long planId, Long userId);
    List<PlanParticipant> findByUserIdOrderByJoinedAtDesc(Long userId);
}
