// The draw filters (F-008): the selects' choices, the message fields they
// add to start (lobby), and the applied-conditions line.
import i18n from '../i18n/index.js'

// Song type options: [value, dictionary key].
export const TYPE_OPTIONS = [
  ['all', 'filter.all'],
  ['OP', 'filter.op'],
  ['ED', 'filter.ed'],
]
export const YEAR_FROM_OPTIONS = [1990, 2000, 2005, 2010, 2015, 2020, 2025]
export const YEAR_TO_OPTIONS = [1999, 2004, 2009, 2014, 2019, 2024]

const TYPE_KEY = { OP: 'filter.op', ED: 'filter.ed' }

// Difficulty options (F-009, D-069): [value, dictionary key]. 'normal' is
// the default and adds no field.
export const TIER_OPTIONS = [
  ['easy', 'filter.easy'],
  ['normal', 'filter.normal'],
  ['hard', 'filter.hard'],
]
const TIER_KEY = { easy: 'filter.easy', hard: 'filter.hard' }

// The answer format (cycle 100, D-111): [value, dictionary key]. 'chat' is
// the default and adds no field.
export const ANSWER_OPTIONS = [
  ['chat', 'answer.chat'],
  ['choice', 'answer.choice'],
]

// The shared App-level selection: 곡 수, the filters as select values, and
// the answer format, which travels with the filters (spec 100 A8).
export const DEFAULT_SELECTION = { songCount: '10', type: 'all', from: '', to: '', tier: 'normal', answerMode: 'chat' }

// Both years set and the start after the end.
export function isReversed({ from, to }) {
  return from !== '' && to !== '' && Number(from) > Number(to)
}

// Only the non-default fields; {} with every default.
export function toFields({ type, from, to, tier, answerMode }) {
  const fields = {}
  if (type === 'OP' || type === 'ED') fields.types = [type]
  if (from !== '') fields.yearFrom = Number(from)
  if (to !== '') fields.yearTo = Number(to)
  if (tier === 'easy' || tier === 'hard') fields.tier = tier
  if (answerMode === 'choice') fields.answerMode = 'choice'
  return fields
}

// settings{types?, yearFrom?, yearTo?, tier?, answerMode?} as one line,
// such as '오프닝만 · 2010~2019 · 쉬움 · 객관식'; '' when nothing is
// filtered and the answers are by chat. t is the translate function; the
// default uses the current language.
export function settingsLine({ types, yearFrom, yearTo, tier, answerMode }, t = (key) => i18n.t(key)) {
  const parts = []
  if (Array.isArray(types) && types.length === 1 && Object.hasOwn(TYPE_KEY, types[0])) {
    parts.push(t(TYPE_KEY[types[0]]))
  }
  const hasFrom = typeof yearFrom === 'number'
  const hasTo = typeof yearTo === 'number'
  if (hasFrom || hasTo) parts.push(`${hasFrom ? yearFrom : ''}~${hasTo ? yearTo : ''}`)
  if (Object.hasOwn(TIER_KEY, tier)) parts.push(t(TIER_KEY[tier]))
  if (answerMode === 'choice') parts.push(t('answer.choice'))
  return parts.join(' · ')
}
