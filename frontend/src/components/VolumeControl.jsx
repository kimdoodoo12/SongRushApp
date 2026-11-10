import { useTranslation } from '../i18n/index.js'

// The mockup's speaker (D-124): waves when audible, a cross when muted or at
// 0. Decoration only; the button's name is the label.
function SpeakerIcon({ silent }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M4 9h4l5-4v14l-5-4H4z" />
      {silent ? (
        <path d="M16 9l5 6M21 9l-5 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
      ) : (
        <path d="M16 8.5a5 5 0 0 1 0 7" stroke="currentColor" strokeWidth="2" fill="none" />
      )}
    </svg>
  )
}

// The player's own volume (D-099) as the header's .vol (cycle 110): the
// speaker is the mute toggle, then the 0-100 slider. onLevel gets the
// slider's integer value. Below 900px the mockup's CSS hides it, and the
// phone's own volume keys apply (D-124).
function VolumeControl({ volume, onLevel, onToggleMute }) {
  const { t } = useTranslation()
  const silent = volume.muted || volume.v === 0
  return (
    <div className="vol">
      <button type="button" aria-pressed={volume.muted} aria-label={t('volume.mute')} onClick={onToggleMute}>
        <SpeakerIcon silent={silent} />
      </button>
      <input
        type="range"
        min="0"
        max="100"
        step="1"
        value={volume.v}
        aria-label={t('volume.label')}
        aria-valuetext={`${volume.v}%`}
        onChange={(e) => onLevel(Number(e.target.value))}
      />
    </div>
  )
}

export default VolumeControl
