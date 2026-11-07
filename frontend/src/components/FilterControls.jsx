import { TIER_OPTIONS, TYPE_OPTIONS, YEAR_FROM_OPTIONS, YEAR_TO_OPTIONS, isReversed } from '../game/filters.js'
import { useTranslation } from '../i18n/index.js'

// 곡 종류, 난이도 (F-009), 연도 시작 and 연도 끝 (F-008), the host's lobby
// controls. selection is the App-level { songCount, type, from, to,
// tier }; onChange gets the whole new selection. The parent disables its
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
    </>
  )
}

export default FilterControls
