package com.triplan.triplan.notification;

/**
 * 이메일/웹푸시 공통 메시지 페이로드.
 *
 * @param title 푸시 알림 헤더 / 이메일 제목
 * @param body  본문 (한 줄~수 줄, 일반 텍스트)
 * @param url   클릭 시 이동할 프론트엔드 URL (절대경로)
 */
public record NotificationMessage(String title, String body, String url) {
}