import { useTranslation } from '../i18n/index.js'

// The speaker: waves when audible, a cross when muted or at 0. Decoration
// only; the button's name is the label.
function SpeakerIcon({ silent }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5">
      <path d="M2 6h3l4-3v10l-4-3H2z" fill="currentColor" stroke="none" />
      {silent ? (
        <path d="M11 6l4 4M15 6l-4 4" strokeLinecap="round" />
      ) : (
        <path d="M11 5.5a3.5 3.5 0 0 1 0 5M12.5 3.5a6 6 0 0 1 0 9" strokeLinecap="round" />
      )}
    </svg>
  )
}

// The player's own volume (D-099): a mute toggle and a 0-100 slider, in fixed
// boxes, the same in every look. onLevel gets the slider's integer value.
function VolumeControl({ volume, onLevel, onToggleMute }) {
  const { t } = useTranslation()
  const silent = volume.muted || volume.v === 0
  return (
    <div className="volume flex h-8 items-center gap-2">
      <button
        type="button"
        className={`volume-mute flex h-8 w-8 shrink-0 items-center justify-center p-0 ${volume.muted ? 'skip-on' : ''}`}
        aria-pressed={volume.muted}
        aria-label={t('volume.mute')}
        onClick={onToggleMute}
      >
        <SpeakerIcon silent={silent} />
      </button>
      <input
        type="range"
        className="volume-slider m-0 h-8 w-40 shrink-0"
        min="0"
        max="100"
        step="1"
        value={volume.v}
        aria-label={t('volume.label')}
        aria-valuetext={`${volume.v}%`}
        onChange={(e) => onLevel(Number(e.target.value))}
      />
      <span className="volume-value h-5 w-10 shrink-0 text-right text-xs leading-5 tabular-nums text-muted" aria-hidden="true">
        {`${volume.v}%`}
      </span>
    </div>
  )
}

export default VolumeControl
