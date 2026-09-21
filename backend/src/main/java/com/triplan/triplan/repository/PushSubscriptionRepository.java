package com.triplan.triplan.repository;

import com.triplan.triplan.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByUser_Id(Long userId);

    List<PushSubscription> findByUser_IdIn(List<Long> userIds);

    void deleteByEndpoint(String endpoint);
}