package com.triplan.triplan.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "spring.mail.host")
@Slf4j
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationService(JavaMailSender mailSender,
                                    @Value("${app.mail.from:TokTrip <noreply@toktrip.local>}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Async
    public void send(String to, NotificationMessage message) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(to);
            mail.setSubject("[TokTrip] " + message.title());
            mail.setText(message.body() + "\n\n바로 가기: " + message.url());
            mailSender.send(mail);
            log.info("[Email] sent to={} subject={}", to, message.title());
        } catch (Exception e) {
            log.warn("[Email] 발송 실패 to={} subject={} cause={}", to, message.title(), e.toString());
        }
    }
}