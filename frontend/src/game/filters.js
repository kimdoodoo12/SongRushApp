// The draw filters (F-008): the selects' choices, the message fields they
// add to create (solo) and start (lobby), and the applied-conditions line.

// 곡 종류 options: [value, text].
export const TYPE_OPTIONS = [
  ['all', '전체'],
  ['OP', '오프닝만'],
  ['ED', '엔딩만'],
]
export const YEAR_FROM_OPTIONS = [1990, 2000, 2005, 2010, 2015, 2020, 2025]
export const YEAR_TO_OPTIONS = [1999, 2004, 2009, 2014, 2019, 2024]

const TYPE_TEXT = { OP: '오프닝만', ED: '엔딩만' }

// The shared App-level selection: 곡 수 plus the filters, as select values.
export const DEFAULT_SELECTION = { songCount: '10', type: 'all', from: '', to: '' }

// Both years set and the start after the end.
export function isReversed({ from, to }) {
  return from !== '' && to !== '' && Number(from) > Number(to)
}

// Only the non-default fields; {} with every default.
export function toFields({ type, from, to }) {
  const fields = {}
  if (type === 'OP' || type === 'ED') fields.types = [type]
  if (from !== '') fields.yearFrom = Number(from)
  if (to !== '') fields.yearTo = Number(to)
  return fields
}

// settings{types?, yearFrom?, yearTo?} as one line, such as
// '오프닝만 · 2010~2019'; '' when nothing is filtered.
export function settingsLine({ types, yearFrom, yearTo }) {
  const parts = []
  if (Array.isArray(types) && types.length === 1 && TYPE_TEXT[types[0]] !== undefined) {
    parts.push(TYPE_TEXT[types[0]])
  }
  const hasFrom = typeof yearFrom === 'number'
  const hasTo = typeof yearTo === 'number'
  if (hasFrom || hasTo) parts.push(`${hasFrom ? yearFrom : ''}~${hasTo ? yearTo : ''}`)
  return parts.join(' · ')
}
