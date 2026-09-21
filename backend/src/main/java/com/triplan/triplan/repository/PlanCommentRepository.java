package com.triplan.triplan.repository;

import com.triplan.triplan.entity.PlanComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanCommentRepository extends JpaRepository<PlanComment, Long> {
    List<PlanComment> findByPlanIdOrderByCreatedAtDesc(Long planId);
}
