package com.triplan.triplan.notification;

import com.triplan.triplan.entity.Plan;
import com.triplan.triplan.entity.PlanParticipant;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.repository.PlanParticipantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 알림 발송 오케스트레이터.
 * EmailNotificationService / WebPushNotificationService 가 설정 누락으로 빈 등록되지 않더라도
 * ObjectProvider 로 안전하게 옵셔널 의존성 처리한다.
 */
@Service
@Slf4j
public class NotificationService {

    private final ObjectProvider<EmailNotificationService> emailProvider;
    private final ObjectProvider<WebPushNotificationService> webPushProvider;
    private final PlanParticipantRepository participantRepository;
    private final String frontendUrl;

    public NotificationService(ObjectProvider<EmailNotificationService> emailProvider,
                               ObjectProvider<WebPushNotificationService> webPushProvider,
                               PlanParticipantRepository participantRepository,
                               @Value("${app.frontend.url:http://localhost:5174}") String frontendUrl) {
        this.emailProvider = emailProvider;
        this.webPushProvider = webPushProvider;
        this.participantRepository = participantRepository;
        this.frontendUrl = frontendUrl;
    }

    /** 최종 일정 확정 직후 발송. */
    @Transactional(readOnly = true)
    public void notifyFinalPlanCreated(Plan plan) {
        String url = frontendUrl + "/confirmed/" + plan.getUuid();
        NotificationMessage msg = new NotificationMessage(
                "여행 일정이 확정됐어요!",
                String.format("'%s' 최종 일정이 만들어졌어요. 지금 확인해 보세요.",
                        nullToEmpty(plan.getTitle())),
                url
        );
        dispatchToPlanMembers(plan, msg);
    }

    /** 출발 하루 전 정오 발송. */
    @Transactional(readOnly = true)
    public void notifyDepartureDMinusOne(Plan plan) {
        String url = frontendUrl + "/confirmed/" + plan.getUuid();
        String dateText = plan.getStartDate() != null
                ? plan.getStartDate().format(DateTimeFormatter.ofPattern("M월 d일"))
                : "내일";
        NotificationMessage msg = new NotificationMessage(
                "내일 출발이에요!",
                String.format("'%s' 여행이 %s에 시작돼요. 짐 챙기는 거 잊지 마세요!",
                        nullToEmpty(plan.getTitle()), dateText),
                url
        );
        dispatchToPlanMembers(plan, msg);
    }

    private void dispatchToPlanMembers(Plan plan, NotificationMessage msg) {
        List<PlanParticipant> participants = participantRepository.findByPlanId(plan.getId());
        List<User> users = participants.stream()
                .map(PlanParticipant::getUser)
                .filter(u -> u != null && u.getId() != null)
                .distinct()
                .toList();
        if (users.isEmpty()) {
            log.info("[Notification] 발송 대상 없음 planId={}", plan.getId());
            return;
        }

        // Web Push: user_id 배치로 한 번에
        webPushProvider.ifAvailable(svc -> svc.sendToUsers(
                users.stream().map(User::getId).toList(), msg));

        // Email: 사용자별
        emailProvider.ifAvailable(svc -> {
            for (User u : users) {
                if (u.getEmail() != null && !u.getEmail().isBlank()) {
                    svc.send(u.getEmail(), msg);
                }
            }
        });

        log.info("[Notification] dispatched planId={} recipients={} title={}",
                plan.getId(), users.size(), msg.title());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}