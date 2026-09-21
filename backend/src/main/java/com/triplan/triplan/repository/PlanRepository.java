package com.triplan.triplan.repository;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {
    Optional<Plan> findByUuid(String uuid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Plan p where p.id = :id")
    Optional<Plan> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Plan p where p.uuid = :uuid")
    Optional<Plan> findByUuidForUpdate(@Param("uuid") String uuid);

    List<Plan> findByStatus(PlanStatus status);
    List<Plan> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    List<Plan> findByStatusAndStartDate(PlanStatus status, LocalDate startDate);
}
