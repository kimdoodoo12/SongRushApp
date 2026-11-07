import { useEffect, useId, useRef, useState } from 'react'
import { sendFeedback } from '../game/feedback.js'
import { currentLanguage, useTranslation } from '../i18n/index.js'

const CATEGORIES = ['bug', 'idea', 'song', 'other']
const TITLE_MAX = 80
const BODY_MAX = 2000

// The texts for an outcome that keeps the form (the rest replace it).
const ERROR_KEYS = {
  rateLimited: 'feedback.rateLimited',
  bad: 'feedback.bad',
  failed: 'feedback.failed',
  network: 'feedback.network',
}

const FOCUSABLE = 'button:not(:disabled), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled), textarea:not(:disabled)'

// The 문의하기 dialog (cycle 082, D-096): a modal form whose post becomes a
// GitHub issue on the server. Mounted while open; page is 'start', 'lobby' or
// 'game'. onClose closes it and gives focus back to the footer button.
// Escape closes it, and Tab cycles inside it. The result replaces the form on
// success (thanks) or when the feature is off (preparing); any other error
// keeps the inputs and shows a text above the buttons.
export default function FeedbackDialog({ page, onClose }) {
  const { t } = useTranslation()
  const headingId = useId()
  const bodyId = useId()
  const dialogRef = useRef(null)
  const firstFieldRef = useRef(null)
  const closeRef = useRef(null)
  const [category, setCategory] = useState('bug')
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [website, setWebsite] = useState('')
  const [sending, setSending] = useState(false)
  // null while the form shows; 'ok' or 'disabled' once it is replaced.
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const canSubmit = !sending && title.trim() !== '' && body.trim() !== ''

  // Focus goes into the dialog when it opens, and to the close button when
  // the form is replaced (the focused submit button is gone then).
  useEffect(() => {
    if (result === null) firstFieldRef.current?.focus()
    else closeRef.current?.focus()
  }, [result])

  async function handleSubmit(event) {
    event.preventDefault()
    if (!canSubmit) return
    setSending(true)
    setError(null)
    const outcome = await sendFeedback({ category, title, body, lang: currentLanguage(), page, website })
    setSending(false)
    if (outcome === 'ok' || outcome === 'disabled') setResult(outcome)
    else setError(ERROR_KEYS[outcome] ?? 'feedback.failed')
  }

  function handleKeyDown(event) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
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

  const closeButton = (
    <button ref={closeRef} type="button" className="h-10 w-28 truncate px-2 py-0 text-sm leading-5" onClick={onClose}>
      {t('feedback.close')}
    </button>
  )

  return (
    <div className="feedback-backdrop fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-3">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
        className="feedback panel relative my-auto flex max-h-full w-full max-w-md flex-col gap-3 overflow-y-auto p-5"
        onKeyDown={handleKeyDown}
      >
        <h2 id={headingId} className="font-display text-xl leading-7">
          {t('feedback.title')}
        </h2>
        {result === null ? (
          <form
            className="flex flex-col gap-3 [&_label]:flex [&_label]:flex-col [&_label]:gap-1 [&_label]:text-xs [&_label]:font-medium [&_label]:text-muted"
            onSubmit={handleSubmit}
            noValidate
          >
            <label>
              {t('feedback.label.category')}
              <select ref={firstFieldRef} value={category} onChange={(e) => setCategory(e.target.value)}>
                {CATEGORIES.map((id) => (
                  <option key={id} value={id}>
                    {t(`feedback.category.${id}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('feedback.label.title')}
              <input type="text" maxLength={TITLE_MAX} value={title} onChange={(e) => setTitle(e.target.value)} />
            </label>
            {/* The counter sits outside the label, so the field's name stays
                just 내용; its maxLength already tells assistive tech the cap. */}
            <div className="flex flex-col gap-1">
              <div className="flex items-baseline justify-between gap-2 text-xs font-medium text-muted">
                <label htmlFor={bodyId}>{t('feedback.label.body')}</label>
                <span className="tabular-nums" aria-hidden="true">
                  {body.length} / {BODY_MAX}
                </span>
              </div>
              <textarea id={bodyId} rows={6} maxLength={BODY_MAX} value={body} onChange={(e) => setBody(e.target.value)} />
            </div>
            {/* The honeypot: people never see or reach it; a bot that fills
                every field gives itself away. */}
            <div className="feedback-trap" aria-hidden="true">
              <label>
                Website
                <input
                  type="text"
                  name="website"
                  tabIndex={-1}
                  autoComplete="off"
                  value={website}
                  onChange={(e) => setWebsite(e.target.value)}
                />
              </label>
            </div>
            {error !== null && (
              <p className="text-sm font-semibold text-bad" role="alert">
                {t(error)}
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-2">
              {closeButton}
              <button type="submit" className="go h-10 w-28 truncate px-2 py-0 text-sm leading-5" disabled={!canSubmit}>
                {t('feedback.submit')}
              </button>
            </div>
          </form>
        ) : (
          <>
            <p className="text-sm" role="status">
              {t(result === 'ok' ? 'feedback.thanks' : 'feedback.preparing')}
            </p>
            <div className="flex justify-end">{closeButton}</div>
          </>
        )}
      </div>
    </div>
  )
}
