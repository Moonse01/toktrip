package com.triplan.triplan.repository;

import com.triplan.triplan.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    List<Place> findByCandidateIdOrderByDayNumberAscOrderIndexAsc(Long candidateId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Place p where p.candidate.id in " +
            "(select c.id from PlanCandidate c where c.plan.id = :planId and c.isFinal = false)")
    void deleteNonFinalByPlanId(@Param("planId") Long planId);
}
