package com.triplan.triplan.scheduler;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanStatus;
import com.triplan.triplan.entity.ReminderDispatchLog;
import com.triplan.triplan.notification.NotificationService;
import com.triplan.triplan.repository.PlanRepository;
import com.triplan.triplan.repository.ReminderDispatchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * D-1 정오 출발 리마인더.
 *
 * - 매일 KST 12:00 에 실행
 * - 다음 날 출발하는 CONFIRMED 플랜에 대해 전 참여자에게 알림
 * - reminder_dispatch_log 로 동일 (plan, kind) 중복 발송 방지
 */
@Component
@ConditionalOnProperty(name = "triplan.reminder.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ReminderScheduler {

    private static final String KIND_D_MINUS_ONE = "DEPARTURE_D_MINUS_1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PlanRepository planRepository;
    private final ReminderDispatchLogRepository dispatchLogRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public void sendDepartureDMinusOne() {
        LocalDate tomorrow = LocalDate.now(KST).plusDays(1);
        List<Plan> plans = planRepository.findByStatusAndStartDate(PlanStatus.CONFIRMED, tomorrow);
        if (plans.isEmpty()) {
            log.info("[ReminderScheduler] D-1 발송 대상 없음 target={}", tomorrow);
            return;
        }
        log.info("[ReminderScheduler] D-1 발송 시작 target={} count={}", tomorrow, plans.size());

        for (Plan plan : plans) {
            if (dispatchLogRepository.existsByPlanIdAndKind(plan.getId(), KIND_D_MINUS_ONE)) {
                continue;
            }
            try {
                notificationService.notifyDepartureDMinusOne(plan);
                dispatchLogRepository.save(ReminderDispatchLog.builder()
                        .planId(plan.getId())
                        .kind(KIND_D_MINUS_ONE)
                        .build());
            } catch (Exception e) {
                log.warn("[ReminderScheduler] 발송 실패 planId={} cause={}", plan.getId(), e.toString());
            }
        }
    }
}