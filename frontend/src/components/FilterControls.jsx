import { ANSWER_OPTIONS, GAME_MODE_OPTIONS, TIER_OPTIONS, TYPE_OPTIONS, YEAR_FROM_OPTIONS, YEAR_TO_OPTIONS } from '../game/filters.js'
import { useTranslation } from '../i18n/index.js'

// One .seg group of toggle buttons (the mockup's lobby control): the
// group's name is its label, the current value is pressed.
function Seg({ label, options, value, disabled, onPick }) {
  const { t } = useTranslation()
  return (
    <div className="opt">
      <span className="ol">{label}</span>
      <div className="seg" role="group" aria-label={label}>
        {options.map(([v, key]) => (
          <button key={v} type="button" aria-pressed={value === v} disabled={disabled} onClick={() => onPick(v)}>
            {t(key)}
          </button>
        ))}
      </div>
    </div>
  )
}

// 곡 종류, 난이도 (F-009), 연도 (F-008), 정답 형식 (cycle 100) and 게임 모드
// (cycle 101), the host's lobby controls, as the mockup's .opt cells (cycle
// 114): .seg groups and the two .years selects. selection is the App-level
// { songCount, type, from, to, tier, answerMode, gameMode }; onChange gets
// the whole new selection. disabled (a guest) disables every control. The
// parent shows the reversed-range warning and disables its start button.
function FilterControls({ selection, onChange, disabled = false }) {
  const { t } = useTranslation()
  function set(key, value) {
    onChange({ ...selection, [key]: value })
  }

  return (
    <>
      <Seg label={t('filter.type')} options={TYPE_OPTIONS} value={selection.type} disabled={disabled} onPick={(v) => set('type', v)} />
      <Seg label={t('filter.tier')} options={TIER_OPTIONS} value={selection.tier} disabled={disabled} onPick={(v) => set('tier', v)} />
      <div className="opt">
        <span className="ol">{t('filter.years')}</span>
        <div className="years">
          <select aria-label={t('filter.from')} value={selection.from} disabled={disabled} onChange={(e) => set('from', e.target.value)}>
            <option value="">{t('filter.fromAny')}</option>
            {YEAR_FROM_OPTIONS.map((y) => (
              <option key={y} value={String(y)}>
                {y}
              </option>
            ))}
          </select>
          <span>~</span>
          <select aria-label={t('filter.to')} value={selection.to} disabled={disabled} onChange={(e) => set('to', e.target.value)}>
            <option value="">{t('filter.toAny')}</option>
            {YEAR_TO_OPTIONS.map((y) => (
              <option key={y} value={String(y)}>
                {y}
              </option>
            ))}
          </select>
        </div>
      </div>
      <Seg
        label={t('answer.label')}
        options={ANSWER_OPTIONS}
        value={selection.answerMode}
        disabled={disabled}
        onPick={(v) => set('answerMode', v)}
      />
      <Seg
        label={t('mode.label')}
        options={GAME_MODE_OPTIONS}
        value={selection.gameMode}
        disabled={disabled}
        onPick={(v) => set('gameMode', v)}
      />
    </>
  )
}

export default FilterControls
