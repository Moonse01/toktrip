package com.triplan.triplan.repository;

import com.triplan.triplan.entity.PlanReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanReviewRepository extends JpaRepository<PlanReview, Long> {
    List<PlanReview> findByPlanId(Long planId);
}