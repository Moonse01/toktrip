import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { deletePlan } from '../api/plans'

export default function DeletePlanSection({ uuid, status }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const warning = warningByStatus(status)

  const handleDelete = async () => {
    setBusy(true)
    try {
      await deletePlan(uuid)
      navigate('/')
    } catch (err) {
      alert(err.response?.data?.message || '삭제에 실패했어요. 잠시 후 다시 시도해 주세요.')
      setBusy(false)
    }
  }

  return (
    <>
      <div className="mt-10 mb-6 flex flex-col items-center">
        <div className="h-px w-24 bg-gray-200 mb-4" />
        <button
          onClick={() => setOpen(true)}
          className="text-sm text-red-500 hover:text-red-700 underline underline-offset-2"
        >
          🗑️ 이 일정 삭제하기
        </button>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
          <div className="bg-white rounded-2xl p-6 md:p-8 max-w-sm w-full shadow-2xl">
            <div className="text-center">
              <div className="text-4xl mb-3">🗑️</div>
              <h3 className="text-lg font-bold text-gray-900 mb-2">정말 삭제할까요?</h3>
              <p className="text-sm text-gray-600 mb-2">{warning}</p>
              <p className="text-xs text-gray-400 mb-6">삭제하면 후보안, 투표, 의견이 모두 같이 지워지고 되돌릴 수 없어요.</p>

              <button
                onClick={handleDelete}
                disabled={busy}
                className="w-full bg-red-500 hover:bg-red-600 disabled:bg-red-300 text-white font-bold py-3 rounded-xl transition-colors mb-2"
              >
                {busy ? '삭제 중...' : '삭제하기'}
              </button>
              <button
                onClick={() => setOpen(false)}
                disabled={busy}
                className="w-full text-sm text-gray-500 hover:text-gray-700 py-2"
              >
                취소
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function warningByStatus(status) {
  switch (status) {
    case 'VOTING':
      return '게스트들이 투표 중인 플랜입니다.'
    case 'CONFIRMED':
      return '여행이 확정된 플랜입니다.'
    case 'COMPLETED':
      return '완료된 여행 기록을 삭제합니다.'
    default:
      return '이 플랜을 삭제합니다.'
  }
}
