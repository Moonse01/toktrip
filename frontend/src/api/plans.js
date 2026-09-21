import instance from './instance'

// S1: 카톡 파일 업로드 + 플랜 생성
export const createPlan = (file, chatText, mission) => {
  const formData = new FormData()
  if (file) formData.append('file', file)
  if (chatText) formData.append('chatLog', chatText)  // ← chatText → chatLog로 키 맞춤
  if (mission) formData.append('mission', mission)
  return instance.post('/api/v1/plans', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// S2: AI 일정 생성 요청 (비동기, 202 반환)
export const generatePlan = (planId) =>
  instance.post(`/api/v1/plans/${planId}/generate`)

// S2: 생성 상태 폴링
export const getPlanStatus = (planId) =>
  instance.get(`/api/v1/plans/${planId}/status`)

// S2: 플랜 단건 조회
export const getPlan = (planUuid) =>
  instance.get(`/api/v1/plans/${planUuid}`)

// S2: A/B안 조회
export const getCandidates = (planUuid) =>
  instance.get(`/api/v1/plans/${planUuid}/candidates`)

// S3: 투표 제출
export const submitVote = (planUuid, voteData, guestUuid) =>
  instance.post(`/api/v1/plans/${planUuid}/votes`, voteData, {
    headers: guestUuid ? { 'X-Guest-UUID': guestUuid } : {},
  })

// S3: 여러 장소 투표 + 순서 선호를 하나의 트랜잭션으로 저장
export const submitVotes = (planUuid, voteData, guestUuid) =>
  instance.post(`/api/v1/plans/${planUuid}/votes/batch`, voteData, {
    headers: guestUuid ? { 'X-Guest-UUID': guestUuid } : {},
  })

// S3: 장소 점수와 분리된 전체 일정 의견 저장
export const submitPlanComment = (planUuid, content, guestUuid) =>
  instance.post(`/api/v1/plans/${planUuid}/votes/comments`, { content }, {
    headers: guestUuid ? { 'X-Guest-UUID': guestUuid } : {},
  })

// S4: 호스트 대시보드 조회
export const getDashboard = (planUuid) =>
  instance.get(`/api/v1/plans/${planUuid}/dashboard`)

// S2: 공유 시작 시 투표 링크 활성화
export const sharePlan = (planUuid) =>
  instance.patch(`/api/v1/plans/${planUuid}/share`)

// S2: 비로그인으로 만든 플랜을 로그인한 호스트에게 귀속
export const claimPlan = (planUuid) =>
  instance.patch(`/api/v1/plans/${planUuid}/claim`)

// S2: 호스트가 A/B안 내부 장소 순서를 조정
export const updatePlaceOrders = (planUuid, orderEntries) =>
  instance.patch(`/api/v1/plans/${planUuid}/places/order`, orderEntries)

// S4: 호스트가 투표 기반 추천 일정을 생성
export const finalizePlan = (planUuid) =>
  instance.post(`/api/v1/plans/${planUuid}/finalize`)

// S5: 이미 생성된 투표 기반 추천 일정을 조회
export const getFinalPlan = (planUuid) =>
  instance.get(`/api/v1/plans/${planUuid}/final`)

// 플랜 제목 수정
export const updatePlanTitle = (planUuid, title) =>
  instance.patch(`/api/v1/plans/${planUuid}/title`, { title })


// 내 플랜 목록 조회
export const getMyPlans = () =>
  instance.get('/api/v1/users/me/plans')

// S2: 호스트가 A/B안의 장소를 삭제
export const deletePlace = (planUuid, candidateId, placeId) =>
  instance.delete(`/api/v1/plans/${planUuid}/candidates/${candidateId}/places/${placeId}`)

// 플랜 삭제
export const deletePlan = (planUuid) =>
  instance.delete(`/api/v1/plans/${planUuid}`)

// 초대받은 플랜을 내 목록에서 제거
export const leavePlan = (planUuid) =>
  instance.delete(`/api/v1/plans/${planUuid}/participation`)
