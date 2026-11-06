import { THEMES } from '../game/themes.js'

// The 분위기 picker: one toggle button per room look, the current one
// pressed. disabled (a non-host in the lobby) disables every button.
export default function ThemePicker({ value, onPick, disabled = false }) {
  return (
    <div role="group" aria-label="분위기" className="theme-picker flex flex-wrap items-center gap-2">
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
          {theme.name}
        </button>
      ))}
    </div>
  )
}
