// Cycle 061 (F-008): the pure filter module. toFields turns the selects'
// values (곡 종류 'all' | 'OP' | 'ED'; 연도 시작 / 연도 끝 '' or a year string)
// into the non-default message fields; settingsLine formats the server's
// settings{types?, yearFrom?, yearTo?} as the one line the game screen shows.
import { describe, it, expect } from 'vitest'
import { toFields, settingsLine } from './filters.js'

const DEFAULTS = { type: 'all', from: '', to: '' }

describe('toFields', () => {
  it('returns {} with all defaults', () => {
    expect(toFields(DEFAULTS)).toEqual({})
  })

  it('오프닝만 adds exactly types:["OP"]', () => {
    expect(toFields({ ...DEFAULTS, type: 'OP' })).toEqual({ types: ['OP'] })
  })

  it('엔딩만 adds exactly types:["ED"]', () => {
    expect(toFields({ ...DEFAULTS, type: 'ED' })).toEqual({ types: ['ED'] })
  })

  it('a start year adds exactly yearFrom as a number', () => {
    const fields = toFields({ ...DEFAULTS, from: '2010' })
    expect(fields).toEqual({ yearFrom: 2010 })
    expect(typeof fields.yearFrom).toBe('number')
  })

  it('an end year adds exactly yearTo as a number', () => {
    const fields = toFields({ ...DEFAULTS, to: '2019' })
    expect(fields).toEqual({ yearTo: 2019 })
    expect(typeof fields.yearTo).toBe('number')
  })

  it('both years add yearFrom and yearTo', () => {
    expect(toFields({ ...DEFAULTS, from: '2000', to: '2009' })).toEqual({ yearFrom: 2000, yearTo: 2009 })
  })

  it('a combined choice adds every field', () => {
    expect(toFields({ type: 'ED', from: '2010', to: '2019' })).toEqual({
      types: ['ED'],
      yearFrom: 2010,
      yearTo: 2019,
    })
  })

  it('a type with one year adds just those two fields', () => {
    expect(toFields({ type: 'OP', from: '', to: '2004' })).toEqual({ types: ['OP'], yearTo: 2004 })
  })
})

describe('settingsLine', () => {
  it('types only: 오프닝만 or 엔딩만', () => {
    expect(settingsLine({ types: ['OP'] })).toBe('오프닝만')
    expect(settingsLine({ types: ['ED'] })).toBe('엔딩만')
  })

  it('from only: yyyy~', () => {
    expect(settingsLine({ yearFrom: 2010 })).toBe('2010~')
  })

  it('to only: ~yyyy', () => {
    expect(settingsLine({ yearTo: 2019 })).toBe('~2019')
  })

  it('both years: yyyy~yyyy', () => {
    expect(settingsLine({ yearFrom: 2010, yearTo: 2019 })).toBe('2010~2019')
  })

  it('everything: the type, then the years, joined with " · "', () => {
    expect(settingsLine({ types: ['OP'], yearFrom: 2010, yearTo: 2019 })).toBe('오프닝만 · 2010~2019')
    expect(settingsLine({ types: ['ED'], yearFrom: 2000, yearTo: 2009 })).toBe('엔딩만 · 2000~2009')
  })

  it('a type with one year', () => {
    expect(settingsLine({ types: ['ED'], yearFrom: 2020 })).toBe('엔딩만 · 2020~')
    expect(settingsLine({ types: ['OP'], yearTo: 1999 })).toBe('오프닝만 · ~1999')
  })
})
