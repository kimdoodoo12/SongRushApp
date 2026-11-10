import { THEMES } from '../game/themes.js'
import { useTranslation } from '../i18n/index.js'

// The room-look picker: one toggle button per room look, the current one
// pressed. disabled (a non-host in the lobby) disables every button. A
// button grows to its whole name, and the row wraps.
export default function ThemePicker({ value, onPick, disabled = false }) {
  const { t } = useTranslation()
  return (
    <div role="group" aria-label={t('theme.label')} className="theme-picker flex flex-wrap items-center gap-2">
      {THEMES.map((theme) => (
        <button
          key={theme.id}
          type="button"
          className="pick"
          aria-pressed={theme.id === value}
          disabled={disabled}
          onClick={() => onPick(theme.id)}
        >
          <span aria-hidden="true" className="pick-swatch" style={{ background: theme.swatch }} />
          <span className="pick-name">{t(`theme.${theme.id}`)}</span>
        </button>
      ))}
    </div>
  )
}
