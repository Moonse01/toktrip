import { useState, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { createPlan, generatePlan } from '../api/plans'
import { useAuth } from '../auth/AuthContext'
import NavBar from '../components/NavBar'
import BottomTabBar from '../components/BottomTabBar'

const MAX_MISSION_EXTRA_LENGTH = 2000

export default function PlanNewPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [chatText, setChatText] = useState('')
  const [missionExtra, setMissionExtra] = useState('')
  const [selectedTemplates, setSelectedTemplates] = useState([])
  const [selectedFile, setSelectedFile] = useState(null)
  const [isDragging, setIsDragging] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [toast, setToast] = useState(null)
  const fileInputRef = useRef(null)

  const showToast = (message) => {
    setToast(message)
    setTimeout(() => setToast(null), 3000)
  }

  const templates = ['가성비 식도락', '힐링 여행', '명소 정복', '쇼핑 투어']

  const toggleTemplate = (template) => {
    setSelectedTemplates(prev =>
      prev.includes(template) ? prev.filter(item => item !== template) : [...prev, template]
    )
  }

  const buildMission = () => {
    const parts = []

    if (selectedTemplates.length > 0) {
      parts.push(`[선택한 템플릿]\n${selectedTemplates.map(t => `- ${t}`).join('\n')}`)
    }

    if (missionExtra.trim()) {
      parts.push(`[추가 요청사항]\n${missionExtra.trim()}`)
    }

    return parts.join('\n\n')
  }

  const validateFile = (file) => {
    const name = file.name.toLowerCase()
    if (!name.endsWith('.txt')) {
      showToast('.txt 파일만 업로드할 수 있어요.')
      return false
    }
    if (file.size > 10 * 1024 * 1024) {
      showToast('파일 크기는 10MB 이하여야 해요.')
      return false
    }
    return true
  }

  const handleFileChange = (event) => {
    const file = event.target.files[0]
    if (file && validateFile(file)) setSelectedFile(file)
  }

  const handleDragOver = (event) => {
    event.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = () => setIsDragging(false)

  const handleDrop = (event) => {
    event.preventDefault()
    setIsDragging(false)
    const file = event.dataTransfer.files[0]
    if (file && validateFile(file)) setSelectedFile(file)
  }

  const handleSubmit = async () => {
    if (!selectedFile && !chatText.trim()) {
      showToast('카카오톡 대화 파일(.txt)을 올리거나 대화 내용을 붙여넣어 주세요.')
      return
    }
    // 붙여넣기 대화는 최소한의 분량이 있어야 함 (파일 업로드는 신뢰)
    if (!selectedFile && chatText.trim().length < 15) {
      showToast('붙여넣은 대화가 너무 짧아요. 실제 카카오톡 대화 내용을 붙여넣어 주세요.')
      return
    }
    const mission = buildMission()
    if (!mission.trim()) {
      showToast('여행 요청사항을 입력해 주세요. (기간·예산·꼭 넣을 곳 등)')
      return
    }
    // 요청사항이 너무 짧고 테마 선택도 없으면 막음 ("바보" 같은 무의미 입력 차단)
    if (selectedTemplates.length === 0 && missionExtra.trim().length < 8) {
      showToast('여행 요청사항을 조금 더 구체적으로 적어주세요. (예: 여수 1박 2일, 인당 15만원)')
      return
    }
    setIsLoading(true)
    try {
      const { data } = await createPlan(selectedFile, chatText, mission)
      const planId = data.data?.id
      const uuid = data.data?.uuid
      await generatePlan(planId)
      navigate(`/result/${uuid}`)
    } catch (err) {
      console.error('플랜 생성 실패:', err)
      showToast(err.response?.data?.message || '플랜 생성 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.')
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      <NavBar>
        {user && (
          <button
            onClick={() => navigate('/')}
            className="text-gray-500 hover:text-gray-700 text-sm flex items-center gap-1"
          >
            ← 내 플랜으로
          </button>
        )}
      </NavBar>

      <div className="max-w-2xl mx-auto px-4 md:px-6 pt-6 md:pt-12 pb-16">
        {!user && (
          <button
            onClick={() => navigate('/')}
            className="text-gray-400 hover:text-gray-600 text-sm mb-6 flex items-center gap-1"
          >
            ← 처음으로
          </button>
        )}

        <div>
          <div className="bg-accent-500 md:bg-primary-500 text-white rounded-t-2xl px-5 md:px-6 py-4">
            <p className="font-bold">📂 여행 대화방 파일을 올려주세요</p>
          </div>
          <div className="bg-white rounded-b-2xl shadow-md p-5 md:p-6">
            <input type="file" ref={fileInputRef} accept=".txt" onChange={handleFileChange} className="hidden" />

            <div
              onClick={() => fileInputRef.current.click()}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              className={`border-2 border-dashed rounded-xl p-6 md:p-8 text-center mb-4 cursor-pointer transition-colors ${
                isDragging ? 'border-accent-400 bg-accent-50 md:border-primary-400 md:bg-primary-50'
                : selectedFile ? 'border-teal-400 bg-teal-50'
                : 'border-gray-200 hover:border-accent-300 md:hover:border-primary-300'
              }`}
            >
              {selectedFile ? (
                <div>
                  <p className="text-3xl mb-2">✅</p>
                  <p className="text-green-600 font-semibold text-sm">{selectedFile.name}</p>
                  <p className="text-gray-400 text-xs mt-1">
                    {(selectedFile.size / 1024).toFixed(1)}KB · 클릭하면 다시 선택
                  </p>
                </div>
              ) : (
                <div>
                  <p className="text-3xl mb-2">📁</p>
                  <p className="text-gray-500 text-sm">.txt 파일을 끌어다 놓거나</p>
                  <p className="text-accent-500 md:text-primary-500 font-semibold text-sm">[클릭하여 파일 선택]</p>
                  <p className="text-gray-400 text-xs mt-2">
                    최대 10MB · .txt 파일만 허용 · 개인정보는 분석 후 즉시 삭제됩니다
                  </p>
                </div>
              )}
            </div>

            <div className="flex items-center gap-2 mb-4">
              <div className="flex-1 h-px bg-gray-200" />
              <span className="text-gray-400 text-xs">또는 텍스트 직접 붙여넣기</span>
              <div className="flex-1 h-px bg-gray-200" />
            </div>

            <textarea
              value={chatText}
              onChange={(event) => setChatText(event.target.value)}
              className="w-full border border-gray-200 rounded-xl p-4 text-sm text-gray-700 resize-none focus:outline-none focus:ring-2 focus:ring-primary-200 mb-4"
              rows={4}
              placeholder="카카오톡 대화 내용을 붙여넣기 해주세요 (Ctrl+V)..."
            />

            <div className="mb-2">
              <p className="text-xs text-gray-500 mb-2 font-medium">🎯 여행 테마 (중복 선택 가능)</p>
              <div className="flex gap-2 flex-wrap">
                {templates.map(template => (
                  <button
                    key={template}
                    onClick={() => toggleTemplate(template)}
                    className={`px-3 py-1.5 rounded-full text-sm border transition-all ${
                      selectedTemplates.includes(template)
                        ? 'bg-accent-500 md:bg-primary-500 text-white border-accent-500 md:border-primary-500 shadow-sm'
                        : 'bg-white text-gray-600 border-gray-200 hover:border-accent-300 md:hover:border-primary-300 hover:text-accent-500 md:hover:text-primary-500'
                    }`}
                  >
                    {selectedTemplates.includes(template) ? '✓ ' : ''}{template}
                  </button>
                ))}
              </div>
            </div>

            <p className="mt-3 mb-1 text-xs font-medium text-gray-500">
              ✏️ 여행 요청사항 (필수 — 기간·예산·꼭 넣을 조건)
            </p>
            <textarea
              value={missionExtra}
              onChange={(event) => setMissionExtra(event.target.value)}
              maxLength={MAX_MISSION_EXTRA_LENGTH}
              rows={3}
              className="w-full border border-gray-200 rounded-xl px-4 py-3 text-sm text-gray-700 resize-none focus:outline-none focus:ring-2 focus:ring-primary-200 mb-4"
              placeholder="예: 부산 1박 2일, 광안리 근처 숙소, 인당 15만원 안쪽"
            />

            <button
              onClick={handleSubmit}
              disabled={isLoading}
              className="w-full bg-accent-500 hover:bg-accent-600 disabled:bg-accent-300 md:bg-primary-500 md:hover:bg-primary-600 md:disabled:bg-primary-300 text-white font-bold py-4 rounded-xl text-lg transition-colors"
            >
              {isLoading ? '⏳ 생성 중...' : '🚀 일정 초안 만들기'}
            </button>
          </div>
        </div>
      </div>

      {toast && (
        <div className="fixed bottom-24 left-1/2 -translate-x-1/2 z-50 bg-gray-900 text-white text-sm font-medium px-5 py-3 rounded-xl shadow-lg flex items-center gap-2">
          <span>⚠️</span>
          <span>{toast}</span>
        </div>
      )}
      <BottomTabBar />
    </div>
  )
}
