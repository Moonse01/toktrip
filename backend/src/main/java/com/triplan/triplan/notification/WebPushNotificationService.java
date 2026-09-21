package com.triplan.triplan.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triplan.triplan.entity.PushSubscription;
import com.triplan.triplan.repository.PushSubscriptionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.Security;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.webpush.public-key")
@RequiredArgsConstructor
@Slf4j
public class WebPushNotificationService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.webpush.public-key}")
    private String publicKey;

    @Value("${app.webpush.private-key}")
    private String privateKey;

    @Value("${app.webpush.subject:mailto:admin@triplan.local}")
    private String subject;

    private PushService pushService;

    @PostConstruct
    void init() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        this.pushService = new PushService(publicKey, privateKey, subject);
    }

    @Async
    public void sendToUsers(List<Long> userIds, NotificationMessage message) {
        if (userIds == null || userIds.isEmpty()) return;
        List<PushSubscription> subs = subscriptionRepository.findByUser_IdIn(userIds);
        for (PushSubscription sub : subs) {
            sendOne(sub, message);
        }
    }

    private void sendOne(PushSubscription sub, NotificationMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            Subscription.Keys keys = new Subscription.Keys(sub.getP256dh(), sub.getAuthToken());
            Subscription subscription = new Subscription(sub.getEndpoint(), keys);
            Notification notification = new Notification(subscription, payload);
            var response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                log.info("[WebPush] 만료 구독 제거 endpoint={}", sub.getEndpoint());
                subscriptionRepository.delete(sub);
            } else if (status >= 400) {
                log.warn("[WebPush] 발송 실패 status={} endpoint={}", status, sub.getEndpoint());
            } else {
                log.info("[WebPush] sent userId={} status={}", sub.getUser().getId(), status);
            }
        } catch (Exception e) {
            log.warn("[WebPush] 발송 예외 endpoint={} cause={}", sub.getEndpoint(), e.toString());
        }
    }

    public String getPublicKey() {
        return publicKey;
    }
}