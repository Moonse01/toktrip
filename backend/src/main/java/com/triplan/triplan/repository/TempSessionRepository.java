package com.triplan.triplan.repository;

import com.triplan.triplan.entity.TempSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TempSessionRepository extends JpaRepository<TempSession, Long> {
    Optional<TempSession> findBySessionToken(String sessionToken);
    List<TempSession> findByExpiresAtBefore(LocalDateTime now); // Scheduler용
}