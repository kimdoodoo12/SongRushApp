import { THEMES } from '../game/themes.js'
import { useTranslation } from '../i18n/index.js'

// The mood picker in the mockup's markup (D-124, cycle 110): .moods, one
// toggle button per mood with its .sw swatch and name, the current one
// pressed. disabled (a guest) disables every button. The game room's header
// shows it; the start screen and the lobby use it too until cycle 114.
export default function ThemePicker({ value, onPick, disabled = false }) {
  const { t } = useTranslation()
  return (
    <div role="group" aria-label={t('theme.label')} className="moods">
      {THEMES.map((theme) => (
        <button
          key={theme.id}
          type="button"
          aria-pressed={theme.id === value}
          disabled={disabled}
          onClick={() => onPick(theme.id)}
        >
          <span aria-hidden="true" className="sw" style={{ background: theme.swatch }} />
          {t(`theme.${theme.id}`)}
        </button>
      ))}
    </div>
  )
}
