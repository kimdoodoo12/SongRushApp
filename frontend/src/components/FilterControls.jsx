import { TIER_OPTIONS, TYPE_OPTIONS, YEAR_FROM_OPTIONS, YEAR_TO_OPTIONS, isReversed } from '../game/filters.js'

const REVERSED_HINT = '시작 연도가 끝 연도보다 늦습니다.'

// 곡 종류, 난이도 (F-009), 연도 시작 and 연도 끝 (F-008), for the solo form
// and the host. selection is the App-level { songCount, type, from, to,
// tier }; onChange gets the whole new selection. The parent disables its
// start button while the range is reversed; the hint is shown here.
function FilterControls({ selection, onChange }) {
  function set(key, value) {
    onChange({ ...selection, [key]: value })
  }

  return (
    <>
      <label>
        곡 종류
        <select value={selection.type} onChange={(e) => set('type', e.target.value)}>
          {TYPE_OPTIONS.map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label>
        난이도
        <select value={selection.tier} onChange={(e) => set('tier', e.target.value)}>
          {TIER_OPTIONS.map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label>
        연도 시작
        <select value={selection.from} onChange={(e) => set('from', e.target.value)}>
          <option value="">처음부터</option>
          {YEAR_FROM_OPTIONS.map((y) => (
            <option key={y} value={String(y)}>
              {y}
            </option>
          ))}
        </select>
      </label>
      <label>
        연도 끝
        <select value={selection.to} onChange={(e) => set('to', e.target.value)}>
          <option value="">지금까지</option>
          {YEAR_TO_OPTIONS.map((y) => (
            <option key={y} value={String(y)}>
              {y}
            </option>
          ))}
        </select>
      </label>
      {isReversed(selection) && <p className="hint col-span-full text-sm text-bad">{REVERSED_HINT}</p>}
    </>
  )
}

export default FilterControls
