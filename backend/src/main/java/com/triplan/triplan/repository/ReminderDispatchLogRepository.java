package com.triplan.triplan.repository;

import com.triplan.triplan.entity.ReminderDispatchLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderDispatchLogRepository extends JpaRepository<ReminderDispatchLog, Long> {

    boolean existsByPlanIdAndKind(Long planId, String kind);
}