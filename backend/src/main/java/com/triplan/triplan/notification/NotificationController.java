package com.triplan.triplan.notification;

import com.triplan.triplan.dto.ApiResponse;
import com.triplan.triplan.entity.PushSubscription;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.PushSubscriptionRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final PushSubscriptionRepository subscriptionRepository;
    private final ObjectProvider<WebPushNotificationService> webPushProvider;

    /** 프론트가 VAPID 공개키를 받아가는 엔드포인트 (서비스 워커 구독 시 필요). */
    @GetMapping("/vapid-public-key")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPublicKey() {
        String key = webPushProvider.getIfAvailable() != null
                ? webPushProvider.getObject().getPublicKey()
                : "";
        return ResponseEntity.ok(ApiResponse.ok(Map.of("publicKey", key)));
    }

    @PostMapping("/subscribe")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> subscribe(@AuthenticationPrincipal User user,
                                                       @RequestBody SubscribeRequest req) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        // 1기기=1사용자 가정: 동일 endpoint 가 존재하면 (다른 사용자 포함) 모두 제거 후 새로 저장
        subscriptionRepository.deleteByEndpoint(req.endpoint());
        subscriptionRepository.save(PushSubscription.builder()
                .user(user)
                .endpoint(req.endpoint())
                .p256dh(req.p256dh())
                .authToken(req.auth())
                .build());
        log.info("[Push] 구독 저장 userId={} endpoint={}", user.getId(), req.endpoint());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/unsubscribe")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@AuthenticationPrincipal User user,
                                                         @RequestBody UnsubscribeRequest req) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        subscriptionRepository.deleteByEndpoint(req.endpoint());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    public record SubscribeRequest(@NotBlank String endpoint,
                                   @NotBlank String p256dh,
                                   @NotBlank String auth) {}

    public record UnsubscribeRequest(@NotBlank String endpoint) {}
}