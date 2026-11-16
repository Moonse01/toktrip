![TokTrip — 친구들이 함께 결정하는 여행 플래너](assets/cover.svg)

# TokTrip

**단톡방 대화를 바탕으로 AI가 여행 일정 후보 두 개를 만들고, 친구들의 장소별 투표와 댓글을 종합해 최종 일정을 추천하는 서비스입니다.**

대학기업협력형 SW아카데미 5기에서 오브젠이 제안한 「AI와 함께하는 통합 여행 플래너」를 2인 팀으로 개발했습니다. 아카데미 교육 기간은 2026.02.02~06.18이며, 팀 저장소의 개발 기록은 2026년 4~6월입니다.

[개인 기여와 의사결정](docs/contribution.md) · [공개 코드 근거](docs/code-evidence.md) · [시스템 구조와 구현 범위](docs/architecture.md)

> 최종 서비스명은 **TokTrip**입니다. 개발 당시 이름인 `triplan`은 패키지·클래스 경로에 남아 있습니다. 이 저장소는 비밀 설정을 제거한 공개용 소스 사본과 서비스 문서를 담고 있습니다. 개인 기여는 비공개 원본 팀 저장소의 변경 이력과 코드를 기준으로 정리했습니다.

## 어떤 문제를 풀었나요?

단톡방에는 날짜·예산·식단 제약과 취향이 함께 쌓입니다. 이를 한 사람이 다시 정리하는 부담을 줄이고, **같이 여행할 사람들이 최종 결정에도 참여하도록** 만들었습니다.

| ① 대화 입력 | ② 후보 비교 | ③ 함께 선택 | ④ 최종 일정 |
| :--- | :--- | :--- | :--- |
| 대화 파일이나 텍스트와 추가 요청 입력 | 지도에서 일정 후보 두 개 비교 | 장소별 투표와 댓글 남기기 | 여행을 만든 사람이 생성을 요청하면 투표와 댓글을 바탕으로 최종 일정 추천 |

호스트는 생성 결과를 확인한 뒤 공유할 때, 참여자는 일정을 보고 선택한 뒤 제출할 때 로그인하도록 흐름을 구성했습니다.

## 개인 기여

투표 API의 중복 방어와 대시보드 집계를 구현했고, React 화면과 API를 연결했습니다. 이후 AI 일정 생성·검증, 카카오 로그인 후 투표 화면 복귀, 투표·댓글 기반 최종 일정 재구성을 개발했습니다.

| 겪은 문제 | 구현과 선택 | 자세히 보기 |
| :--- | :--- | :--- |
| A/B가 비슷하거나 일정 조건·장소가 맞지 않음 | 파싱·후보 생성 분리, B안에 A안 제외 목록 전달, 검증·보정·대체 처리 | [경험](docs/contribution.md#case-ai) · [코드](docs/code-evidence.md#ai) |
| 로그인 이동 중 선택을 잃거나 재투표가 중복 집계될 수 있음 | 브라우저 임시 선택 보존, 로그인 복귀 후 제출, 동일 사용자·장소 투표 갱신 | [경험](docs/contribution.md#case-vote) · [코드](docs/code-evidence.md#vote) |
| 장소별 투표를 받아도 A/B 중 한 안을 통째로 선택 | 장소별 투표와 댓글을 사용해 기존 장소 ID로 최종 일정을 재구성하고, AI 실패 시 규칙 기반 결과로 대체 | [경험](docs/contribution.md#case-final) · [코드](docs/code-evidence.md#final) |

## 기술 구성

| Backend | Frontend | 외부 연동·배포 |
| :--- | :--- | :--- |
| Java 17 · Spring Boot 3.5 · JPA · Security · MySQL | React 19 · Tailwind CSS · Axios · dnd-kit | OpenAI · Kakao · Tmap · Web Push · Docker Compose · Nginx · KT Cloud |

[화면 → API → 서비스 → 저장소 연결과 상세 구조 보기](docs/architecture.md)

## 소스 코드

| 경로 | 내용 |
| :--- | :--- |
| [`backend/`](backend/) | Spring Boot API, AI 생성·검증, 투표, 최종 일정, 알림 구현과 테스트 |
| [`frontend/`](frontend/) | React S1~S5 화면, 인증 복귀, 투표·대시보드·확정 일정 흐름 |
| [`docs/code-evidence.md`](docs/code-evidence.md) | 핵심 코드의 역할과 설계 판단을 함께 읽는 안내서 |

실제 비밀번호와 외부 API 키, 빌드 결과물, IDE 설정, 로그와 기존 비공개 Git 이력은 포함하지 않습니다. 로컬 실행 시 [`backend/src/main/resources/application.properties.example`](backend/src/main/resources/application.properties.example)과 [`frontend/.env.example`](frontend/.env.example)을 참고해 환경 변수를 설정합니다.

## 결과와 현재 상태

- **개발 당시:** KT Cloud 배포·시연 완료, PC·Android·iOS에서 Web Push 수신 직접 확인.
- **현재:** 학교 클라우드 지원이 종료되어 상시 체험 서버는 제공하지 않습니다.
- **검증 기록:** 백엔드 테스트 116개 통과 기록과 2026-09-11 프론트엔드 빌드 성공 기록이 있습니다.
- **남은 과제:** 긴 실제 대화의 조건 보존, 동시 최종 생성 요청의 중복 AI 비용, 프론트 lint 오류. AI 정확도·사용자 만족도 개선률은 측정하지 않았습니다.

---

### 더 살펴보기

- [개인 기여](docs/contribution.md): 문제 → 선택 → 구현 → 한계
- [공개 코드 근거](docs/code-evidence.md): 비공개 원본 접근 없이 읽을 수 있는 실제 구현
- [구조·검증 범위](docs/architecture.md): 전체 흐름과 남은 개선점

**문석용 · [GitHub](https://github.com/Moonse01)**
