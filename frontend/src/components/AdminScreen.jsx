import { useEffect, useId, useRef, useState } from 'react'
import { feedbackAction, loadFeedback } from '../game/admin.js'

// The owner's feedback admin screen (cycle 083, D-097), shown at /admin. Only
// the owner uses it, so its texts are Korean only and not in the i18n files.
// The list loads once (every status); the tabs filter on the client, and an
// action's answer replaces its record, so the item moves tabs in place.

const TABS = [
  { status: 'new', label: '새 문의' },
  { status: 'sent', label: '보낸 문의' },
  { status: 'hidden', label: '숨긴 문의' },
]

const CATEGORY_TEXT = { bug: '버그', idea: '건의', song: '곡 요청', other: '기타' }

const TITLE_MAX = 80

const LIST_ERROR_TEXT = {
  off: '관리자 기능이 꺼져 있습니다',
  network: '서버에 연결하지 못했습니다',
  failed: '목록을 불러오지 못했습니다',
}

const ACTION_ERROR_TEXT = {
  'github-disabled': 'GitHub 토큰이 설정되지 않았습니다',
  'already-sent': '이미 보낸 문의입니다',
  'in-flight': '처리 중인 요청이 있습니다',
  'feedback-failed': '실패했습니다. GitHub에 이미 만들어졌는지 확인한 뒤 다시 시도하세요',
  'bad-feedback': '제목이 올바르지 않습니다 (1-80자)',
  'not-found': '문의를 찾을 수 없습니다',
  network: '서버에 연결하지 못했습니다',
}

// An answer without a known code. A send may still have created the issue
// (a proxy timeout, for example), so it gets the check-GitHub text.
function actionErrorText(action, code) {
  if (Object.hasOwn(ACTION_ERROR_TEXT, code)) return ACTION_ERROR_TEXT[code]
  return action === 'send' ? ACTION_ERROR_TEXT['feedback-failed'] : '요청이 실패했습니다'
}

function localDateTime(iso) {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return String(iso)
  return date.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}

export default function AdminScreen() {
  const baseId = useId()
  // null while loading; then the records, newest first.
  const [items, setItems] = useState(null)
  const [listError, setListError] = useState(null)
  const [tab, setTab] = useState('new')
  // Per item id: the edited title, the running action, the error text.
  const [titles, setTitles] = useState({})
  const [busy, setBusy] = useState({})
  const [errors, setErrors] = useState({})
  // Synchronous guard against a second click before the re-render.
  const busyRef = useRef(new Set())
  // StrictMode runs effects twice; the list loads once.
  const loadedRef = useRef(false)

  useEffect(() => {
    if (loadedRef.current) return
    loadedRef.current = true
    loadFeedback().then((result) => {
      if (result.ok) setItems(result.items)
      else setListError(result.error)
    })
  }, [])

  function setFor(setter, id, value) {
    setter((prev) => {
      const next = { ...prev }
      if (value === undefined) delete next[id]
      else next[id] = value
      return next
    })
  }

  async function runAction(record, action) {
    const id = record.id
    if (busyRef.current.has(id)) return
    busyRef.current.add(id)
    setFor(setBusy, id, true)
    setFor(setErrors, id, undefined)
    const payload = action === 'send' ? { title: titles[id] ?? record.title } : {}
    const result = await feedbackAction(id, action, payload)
    busyRef.current.delete(id)
    setFor(setBusy, id, undefined)
    if (result.ok) {
      setItems((prev) => prev.map((it) => (it.id === id ? result.record : it)))
      setFor(setTitles, id, undefined)
    } else {
      setFor(setErrors, id, actionErrorText(action, result.error))
    }
  }

  function handleTabKey(event, index) {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
    event.preventDefault()
    const step = event.key === 'ArrowRight' ? 1 : TABS.length - 1
    const next = TABS[(index + step) % TABS.length]
    setTab(next.status)
    document.getElementById(`${baseId}-tab-${next.status}`)?.focus()
  }

  const visible = items === null ? [] : items.filter((it) => it.status === tab)

  return (
    <main className="admin mx-auto flex w-full max-w-3xl min-w-0 flex-col gap-4">
      <h1 className="brand text-3xl tracking-tight">문의 관리</h1>
      {listError !== null && (
        <p className="text-sm font-semibold text-bad" role="alert">
          {LIST_ERROR_TEXT[listError] ?? LIST_ERROR_TEXT.failed}
        </p>
      )}
      {listError === null && (
        <>
          <div role="tablist" aria-label="문의 상태" className="flex flex-wrap gap-2">
            {TABS.map(({ status, label }, index) => {
              const selected = tab === status
              const count = items === null ? null : items.filter((it) => it.status === status).length
              return (
                <button
                  key={status}
                  id={`${baseId}-tab-${status}`}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  aria-controls={`${baseId}-panel`}
                  tabIndex={selected ? 0 : -1}
                  className="admin-tab px-3 py-1 text-sm"
                  onClick={() => setTab(status)}
                  onKeyDown={(event) => handleTabKey(event, index)}
                >
                  {label}
                  {count !== null && ` (${count})`}
                </button>
              )
            })}
          </div>
          <div
            id={`${baseId}-panel`}
            role="tabpanel"
            aria-labelledby={`${baseId}-tab-${tab}`}
            className="flex min-w-0 flex-col gap-3"
          >
            {items === null && <p className="text-sm text-muted">불러오는 중…</p>}
            {items !== null && visible.length === 0 && <p className="text-sm text-muted">문의가 없습니다</p>}
            {visible.map((record) => (
              <AdminItem
                key={record.id}
                record={record}
                title={titles[record.id] ?? record.title}
                busy={busy[record.id] === true}
                error={errors[record.id] ?? null}
                onTitle={(value) => setFor(setTitles, record.id, value)}
                onAction={(action) => runAction(record, action)}
              />
            ))}
          </div>
        </>
      )}
    </main>
  )
}

function AdminItem({ record, title, busy, error, onTitle, onAction }) {
  const category = CATEGORY_TEXT[record.category] ?? String(record.category)
  return (
    <article className="panel flex min-w-0 flex-col gap-2 p-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
        <span className="font-semibold text-ink">{category}</span>
        <span>{localDateTime(record.createdAt)}</span>
        <span>
          {record.lang} · {record.page}
        </span>
      </div>
      {record.status === 'new' ? (
        <label className="flex flex-col gap-1 text-xs font-medium text-muted">
          제목
          <input type="text" maxLength={TITLE_MAX} value={title} onChange={(e) => onTitle(e.target.value)} />
        </label>
      ) : (
        <p className="admin-wrap font-semibold">{record.title}</p>
      )}
      <p className="admin-body text-sm">{record.body}</p>
      {error !== null && (
        <p className="admin-wrap text-sm font-semibold text-bad" role="alert">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        {record.status === 'new' && (
          <>
            <button type="button" className="go px-3 py-1 text-sm" disabled={busy} onClick={() => onAction('send')}>
              Issues로 보내기
            </button>
            <button type="button" className="px-3 py-1 text-sm" disabled={busy} onClick={() => onAction('hide')}>
              숨기기
            </button>
          </>
        )}
        {record.status === 'hidden' && (
          <button type="button" className="px-3 py-1 text-sm" disabled={busy} onClick={() => onAction('unhide')}>
            다시 보이기
          </button>
        )}
        {record.status === 'sent' && typeof record.issueUrl === 'string' && /^https:\/\//.test(record.issueUrl) && (
          <a className="admin-link text-sm font-semibold" href={record.issueUrl} target="_blank" rel="noopener noreferrer">
            #{record.issueNumber}
          </a>
        )}
      </div>
    </article>
  )
}
