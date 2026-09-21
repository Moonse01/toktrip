package com.triplan.triplan.repository;

import com.triplan.triplan.entity.PlanCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlanCandidateRepository extends JpaRepository<PlanCandidate, Long> {
    List<PlanCandidate> findByPlanId(Long planId);
    List<PlanCandidate> findByPlanIdAndIsFinalFalse(Long planId);
    List<PlanCandidate> findByPlanIdAndIsFinalTrue(Long planId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlanCandidate c where c.plan.id = :planId and c.isFinal = false")
    void deleteNonFinalByPlanId(@Param("planId") Long planId);
}
