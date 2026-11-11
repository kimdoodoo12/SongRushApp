import { useEffect, useId, useRef } from 'react'
import { messageText, useTranslation } from '../i18n/index.js'

const NICKNAME_MAX = 16
const FOCUSABLE = 'button:not(:disabled), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled), textarea:not(:disabled)'

// The nickname dialog (cycle 119, D-127): 방 만들기 and 참가 on the start
// screen open it. mode is 'create' or 'join' (code is the room code then).
// App owns its state (name, busy, error), so it survives the attempt's
// connecting and error. A modal div, as FeedbackDialog is (jsdom has no
// showModal): focus goes to the input on open, Tab cycles inside it, and
// Escape, 취소 or a click outside the card call onCancel. Enter or 확인
// call onConfirm with the trimmed name; a blank name, or busy (an attempt
// connecting), keeps 확인 disabled. error is the attempt's start error
// ({ key, values }), shown as p.warn.
export default function NicknameDialog({ mode, code, name, busy, error, onNameChange, onConfirm, onCancel }) {
  const { t } = useTranslation()
  const titleId = useId()
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const trimmed = name.trim()
  const canConfirm = !busy && trimmed !== ''

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  function handleSubmit(event) {
    event.preventDefault()
    if (!canConfirm) return
    onConfirm(trimmed)
  }

  function handleKeyDown(event) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onCancel()
      return
    }
    if (event.key !== 'Tab' || dialogRef.current === null) return
    const focusable = Array.from(dialogRef.current.querySelectorAll(FOCUSABLE))
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  // Only a click on the backdrop itself, not one that bubbles from the card.
  function handleBackdropClick(event) {
    if (event.target === event.currentTarget) onCancel()
  }

  return (
    <div
      className="nick-backdrop fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-3"
      onClick={handleBackdropClick}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="opts nick-card relative my-auto flex w-full max-w-sm flex-col gap-4"
        onKeyDown={handleKeyDown}
      >
        <h2 id={titleId} className="flex flex-col gap-0.5">
          <span>{t('start.nickname')}</span>
          <span className="nick-sub">{mode === 'join' ? t('start.joinTitle', { code }) : t('start.createRoom')}</span>
        </h2>
        <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
          <label className="flex flex-col gap-1.5">
            <span className="ol">{t('start.nickname')}</span>
            <input
              ref={inputRef}
              type="text"
              className="start-input"
              maxLength={NICKNAME_MAX}
              value={name}
              autoComplete="nickname"
              onChange={(e) => onNameChange(e.target.value)}
            />
          </label>
          {error !== null && <p className="warn">{messageText(t, error)}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn" onClick={onCancel}>
              {t('start.cancel')}
            </button>
            <button type="submit" className="btn primary" disabled={!canConfirm}>
              {t('start.confirm')}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
