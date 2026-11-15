import { THEMES } from '../game/themes.js'
import { useTranslation } from '../i18n/index.js'

// The mood picker in the mockup's markup (D-124, cycle 110): .moods, one
// toggle button per mood with its .sw swatch and name, the current one
// pressed. It sets the player's own mood, so it is enabled for everyone
// (D-136, cycle 137). Only the header's .moodbar shows it, on the start
// screen, in the lobby and in a game (cycle 114).
export default function ThemePicker({ value, onPick }) {
  const { t } = useTranslation()
  return (
    <div role="group" aria-label={t('theme.label')} className="moods">
      {THEMES.map((theme) => (
        <button
          key={theme.id}
          type="button"
          aria-pressed={theme.id === value}
          onClick={() => onPick(theme.id)}
        >
          <span aria-hidden="true" className="sw" style={{ background: theme.swatch }} />
          {t(`theme.${theme.id}`)}
        </button>
      ))}
    </div>
  )
}
