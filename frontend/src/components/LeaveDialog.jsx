import { useEffect, useId, useRef } from 'react'
import { useTranslation } from '../i18n/index.js'

const FOCUSABLE = 'button:not(:disabled)'

// The leave-game confirmation (cycle 140, D-138): the game screen's 나가기
// opens it. A modal div with NicknameDialog's look and keys: focus goes to
// 취소 on open, Tab cycles inside it, and Escape, 취소 or a click outside the
// card (pressed and released on the backdrop) call onCancel; 나가기 calls
// onConfirm.
export default function LeaveDialog({ onConfirm, onCancel }) {
  const { t } = useTranslation()
  const titleId = useId()
  const dialogRef = useRef(null)
  const cancelRef = useRef(null)
  // The last mousedown inside the dialog's backdrop began on the backdrop.
  const downOnBackdropRef = useRef(false)

  useEffect(() => {
    cancelRef.current?.focus()
  }, [])

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

  function handleBackdropMouseDown(event) {
    downOnBackdropRef.current = event.target === event.currentTarget
  }

  function handleBackdropClick(event) {
    const downOnBackdrop = downOnBackdropRef.current
    downOnBackdropRef.current = false
    if (downOnBackdrop && event.target === event.currentTarget) onCancel()
  }

  return (
    <div
      className="nick-backdrop fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-3"
      onMouseDown={handleBackdropMouseDown}
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
        <h2 id={titleId}>{t('game.leaveAsk')}</h2>
        <div className="flex justify-end gap-2">
          <button ref={cancelRef} type="button" className="btn" onClick={onCancel}>
            {t('game.leaveCancel')}
          </button>
          <button type="button" className="btn primary" onClick={onConfirm}>
            {t('game.leaveConfirm')}
          </button>
        </div>
      </div>
    </div>
  )
}
