const KAKAO_SHARE_SDK_SRC = 'https://t1.kakaocdn.net/kakao_js_sdk/2.8.1/kakao.min.js';

// 공유 카드 썸네일. 배포 환경에서 VITE_OG_IMAGE_URL(1200x630 권장)을 설정하면 feed 카드로,
// 미설정이면 기존 텍스트 공유로 폴백한다.
const SHARE_THUMBNAIL_URL = import.meta.env.VITE_OG_IMAGE_URL || '';

let sdkPromise;

function loadKakaoShareSdk() {
  if (window.Kakao) {
    return Promise.resolve(window.Kakao);
  }

  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const existing = document.querySelector(`script[src="${KAKAO_SHARE_SDK_SRC}"]`);
      if (existing) {
        existing.addEventListener('load', () => resolve(window.Kakao), { once: true });
        existing.addEventListener('error', () => reject(new Error('카카오 공유 SDK를 불러오지 못했습니다.')), { once: true });
        return;
      }

      const script = document.createElement('script');
      script.src = KAKAO_SHARE_SDK_SRC;
      script.async = true;
      script.onload = () => resolve(window.Kakao);
      script.onerror = () => reject(new Error('카카오 공유 SDK를 불러오지 못했습니다.'));
      document.head.appendChild(script);
    });
  }

  return sdkPromise;
}

function getShareSdkKey() {
  const key = import.meta.env.VITE_KAKAO_JAVASCRIPT_KEY;
  if (!key) {
    throw new Error('카카오 JavaScript 키가 설정되지 않았습니다.');
  }
  return key;
}

async function ensureKakaoReady() {
  const Kakao = await loadKakaoShareSdk();
  if (!Kakao) {
    throw new Error('카카오 SDK를 준비하지 못했습니다.');
  }
  if (!Kakao.isInitialized()) {
    Kakao.init(getShareSdkKey());
  }
  if (!Kakao.Share?.sendDefault) {
    throw new Error('카카오 공유 기능을 준비하지 못했습니다.');
  }
  return Kakao;
}


export function buildVoteInviteText(planTitle, voteUrl, { includeUrl = true } = {}) {
  const title = planTitle || '친구들과 고르는 여행 일정';
  const lines = [
    `${title} 투표를 시작했어요.`,
    'A안과 B안을 비교하고 가고 싶은 장소에 투표해 주세요.',
  ];
  if (includeUrl) lines.push(voteUrl);
  return lines.join('\n');
}

export function buildConfirmedInviteText(planTitle, confirmedUrl, { includeUrl = true } = {}) {
  const title = planTitle || '추천 여행 일정';
  const lines = [
    `${title} 추천 일정이 준비됐어요.`,
    '친구들의 투표 결과와 고정 장소를 반영한 일정을 확인해 주세요.',
  ];
  if (includeUrl) lines.push(confirmedUrl);
  return lines.join('\n');
}

export async function shareVoteRequest({ planTitle, voteUrl }) {
  const Kakao = await ensureKakaoReady();
  const title = planTitle || '친구들과 고르는 여행 일정';
  const link = { mobileWebUrl: voteUrl, webUrl: voteUrl };

  if (!SHARE_THUMBNAIL_URL) {
    Kakao.Share.sendDefault({
      objectType: 'text',
      text: buildVoteInviteText(planTitle, voteUrl),
      link,
      buttonTitle: '투표하러 가기',
    });
    return;
  }

  Kakao.Share.sendDefault({
    objectType: 'feed',
    content: {
      title: `🗳️ ${title}`,
      description: 'A안과 B안을 비교하고 가고 싶은 장소에 투표해 주세요.',
      imageUrl: SHARE_THUMBNAIL_URL,
      link,
    },
    buttons: [{ title: '투표하러 가기', link }],
  });
}

export async function shareConfirmedPlan({ planTitle, confirmedUrl }) {
  const Kakao = await ensureKakaoReady();
  const title = planTitle || '추천 여행 일정';
  const link = { mobileWebUrl: confirmedUrl, webUrl: confirmedUrl };

  if (!SHARE_THUMBNAIL_URL) {
    Kakao.Share.sendDefault({
      objectType: 'text',
      text: buildConfirmedInviteText(planTitle, confirmedUrl),
      link,
      buttonTitle: '추천 일정 보기',
    });
    return;
  }

  Kakao.Share.sendDefault({
    objectType: 'feed',
    content: {
      title: `✈️ ${title}`,
      description: '친구들의 투표와 고정 장소를 반영한 추천 일정을 확인해 보세요.',
      imageUrl: SHARE_THUMBNAIL_URL,
      link,
    },
    buttons: [{ title: '추천 일정 보기', link }],
  });
}
