package com.triplan.triplan.scheduler;

import com.triplan.triplan.entity.TempSession;
import com.triplan.triplan.repository.TempSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TempSessionCleanupScheduler {

    private final TempSessionRepository tempSessionRepository;

    // 매일 새벽 3시 실행
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredSessions() {
        List<TempSession> expired = tempSessionRepository.findByExpiresAtBefore(LocalDateTime.now());

        if (!expired.isEmpty()) {
            log.info("[Scheduler] 만료된 비회원 세션 {}건 삭제", expired.size());
            tempSessionRepository.deleteAll(expired);
        }
    }
}