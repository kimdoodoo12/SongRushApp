import { ANSWER_OPTIONS, GAME_MODE_OPTIONS, TIER_OPTIONS, TYPE_OPTIONS, YEAR_FROM_OPTIONS, YEAR_TO_OPTIONS, isReversed } from '../game/filters.js'
import { useTranslation } from '../i18n/index.js'

// 곡 종류, 난이도 (F-009), 연도 시작 and 연도 끝 (F-008), 정답 형식
// (cycle 100) and 게임 모드 (cycle 101), the host's lobby controls.
// selection is the App-level { songCount, type, from, to, tier, answerMode,
// gameMode }; onChange gets the whole new selection. The parent disables its
// start button while the range is reversed; the hint is shown here.
function FilterControls({ selection, onChange }) {
  const { t } = useTranslation()
  function set(key, value) {
    onChange({ ...selection, [key]: value })
  }

  return (
    <>
      <label>
        {t('filter.type')}
        <select value={selection.type} onChange={(e) => set('type', e.target.value)}>
          {TYPE_OPTIONS.map(([value, key]) => (
            <option key={value} value={value}>
              {t(key)}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('filter.tier')}
        <select value={selection.tier} onChange={(e) => set('tier', e.target.value)}>
          {TIER_OPTIONS.map(([value, key]) => (
            <option key={value} value={value}>
              {t(key)}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('filter.from')}
        <select value={selection.from} onChange={(e) => set('from', e.target.value)}>
          <option value="">{t('filter.fromAny')}</option>
          {YEAR_FROM_OPTIONS.map((y) => (
            <option key={y} value={String(y)}>
              {y}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('filter.to')}
        <select value={selection.to} onChange={(e) => set('to', e.target.value)}>
          <option value="">{t('filter.toAny')}</option>
          {YEAR_TO_OPTIONS.map((y) => (
            <option key={y} value={String(y)}>
              {y}
            </option>
          ))}
        </select>
      </label>
      {isReversed(selection) && <p className="hint col-span-full text-sm text-bad">{t('filter.reversed')}</p>}
      {/* 정답 형식 (cycle 100, D-111): toggle buttons like the 분위기 picker,
          kept in the selection with the filters (spec 100 A8). */}
      <div className="answer-mode col-span-full flex flex-col gap-1">
        <span className="h-4 truncate text-xs leading-4 text-muted">{t('answer.label')}</span>
        <div role="group" aria-label={t('answer.label')} className="flex flex-wrap items-center gap-2">
          {ANSWER_OPTIONS.map(([value, key]) => (
            <button
              key={value}
              type="button"
              className="pick"
              aria-pressed={selection.answerMode === value}
              onClick={() => set('answerMode', value)}
            >
              <span className="pick-name">{t(key)}</span>
            </button>
          ))}
        </div>
      </div>
      {/* 게임 모드 (cycle 101, D-112): 선착순 or 순위전, the same toggle
          buttons as 정답 형식, kept in the selection with the filters. */}
      <div className="game-mode col-span-full flex flex-col gap-1">
        <span className="h-4 truncate text-xs leading-4 text-muted">{t('mode.label')}</span>
        <div role="group" aria-label={t('mode.label')} className="flex flex-wrap items-center gap-2">
          {GAME_MODE_OPTIONS.map(([value, key]) => (
            <button
              key={value}
              type="button"
              className="pick"
              aria-pressed={selection.gameMode === value}
              onClick={() => set('gameMode', value)}
            >
              <span className="pick-name">{t(key)}</span>
            </button>
          ))}
        </div>
      </div>
    </>
  )
}

export default FilterControls
