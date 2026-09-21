package com.triplan.triplan.repository;

import com.triplan.triplan.entity.VoteFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VoteFeedbackRepository extends JpaRepository<VoteFeedback, Long> {
    List<VoteFeedback> findByPlanId(Long planId);
    Optional<VoteFeedback> findByUserIdAndPlaceId(Long userId, Long placeId); // UPSERT용
}