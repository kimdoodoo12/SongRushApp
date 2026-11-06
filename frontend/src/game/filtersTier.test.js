// Cycle 064 (F-009): 난이도 in the pure filter module. toFields adds
// tier:'easy' or tier:'hard' and nothing for 'normal' or a missing key;
// settingsLine appends 쉬움 or 어려움 as the last part and ignores any
// other tier value.
import { describe, it, expect } from 'vitest'
import { toFields, settingsLine, DEFAULT_SELECTION } from './filters.js'

const DEFAULTS = { type: 'all', from: '', to: '' }

describe('DEFAULT_SELECTION', () => {
  it('has tier "normal"', () => {
    expect(DEFAULT_SELECTION.tier).toBe('normal')
  })
})

describe('toFields with tier', () => {
  it('normal adds nothing', () => {
    expect(toFields({ ...DEFAULTS, tier: 'normal' })).toEqual({})
  })

  it('easy adds exactly tier:"easy"', () => {
    expect(toFields({ ...DEFAULTS, tier: 'easy' })).toEqual({ tier: 'easy' })
  })

  it('hard adds exactly tier:"hard"', () => {
    expect(toFields({ ...DEFAULTS, tier: 'hard' })).toEqual({ tier: 'hard' })
  })

  it('tier combines with the other fields', () => {
    expect(toFields({ type: 'OP', from: '2010', to: '2019', tier: 'hard' })).toEqual({
      types: ['OP'],
      yearFrom: 2010,
      yearTo: 2019,
      tier: 'hard',
    })
  })

  it('without a tier key it returns what it did before', () => {
    expect(toFields(DEFAULTS)).toEqual({})
    expect(toFields({ type: 'ED', from: '2000', to: '' })).toEqual({ types: ['ED'], yearFrom: 2000 })
  })

  it('the default selection adds no fields', () => {
    expect(toFields(DEFAULT_SELECTION)).toEqual({})
  })
})

describe('settingsLine with tier', () => {
  it('tier alone: 쉬움 or 어려움', () => {
    expect(settingsLine({ tier: 'easy' })).toBe('쉬움')
    expect(settingsLine({ tier: 'hard' })).toBe('어려움')
  })

  it('tier is the last part after the type and the years', () => {
    expect(settingsLine({ types: ['OP'], yearFrom: 2010, yearTo: 2019, tier: 'easy' })).toBe(
      '오프닝만 · 2010~2019 · 쉬움',
    )
    expect(settingsLine({ types: ['ED'], tier: 'hard' })).toBe('엔딩만 · 어려움')
    expect(settingsLine({ yearTo: 1999, tier: 'hard' })).toBe('~1999 · 어려움')
  })

  it('an unknown or normal tier is ignored', () => {
    expect(settingsLine({ tier: 'extreme' })).toBe('')
    expect(settingsLine({ tier: 'normal' })).toBe('')
    expect(settingsLine({ types: ['OP'], tier: 'extreme' })).toBe('오프닝만')
    expect(settingsLine({ yearFrom: 2010, tier: 42 })).toBe('2010~')
  })

  it('without tier the line is unchanged', () => {
    expect(settingsLine({ types: ['OP'], yearFrom: 2010, yearTo: 2019 })).toBe('오프닝만 · 2010~2019')
  })
})
