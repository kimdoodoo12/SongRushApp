import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 081 (D-095): the UI dictionaries. ko, en and ja must carry exactly
// the same keys, every value a non-empty string with the same {{variables}}.
// The keys App.i18n.test.jsx relies on are a contract with the implementer:
// their Korean values stay byte-identical to the texts the locked tests read.
// Keys are nested JSON objects (i18next's default '.' key separator), so the
// key start.createRoom is { "start": { "createRoom": "..." } }.
// Runs in the node project: it reads the files through import.meta.url.

const LANGS = ['ko', 'en', 'ja']

function readDict(lang) {
  const file = fileURLToPath(new URL(`./${lang}.json`, import.meta.url))
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

// { a: { b: 'x' } } -> { 'a.b': 'x' }. Anything but an object or a string is
// reported under its key so the failure names it.
function flatten(obj, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix === '' ? key : `${prefix}.${key}`
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      flatten(value, path, out)
    } else {
      out[path] = value
    }
  }
  return out
}

function variables(text) {
  return [...text.matchAll(/\{\{\s*([^}\s]+)\s*\}\}/g)].map((m) => m[1]).sort()
}

const CONTRACT_KO = {
  'start.createRoom': '방 만들기',
  'start.nickname': '닉네임',
  'stage.hint': '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요',
  'stage.skip': '스킵',
  'stage.skipCancel': '스킵 취소',
  'stage.skipCount': '스킵 {{votes}}/{{needed}}',
  'chat.correctLine': '{{name}} 정답!',
  'reveal.heading': '정답 공개',
  'theme.label': '분위기',
}

// Spec 4: the theme names in all three languages.
const THEME_NAMES = {
  ko: { play: '놀이터', neon: '무대', sakura: '벚꽃', chalk: '칠판', star: '별밤', arcade: '오락실' },
  en: { play: 'Playground', neon: 'Stage', sakura: 'Sakura', chalk: 'Chalkboard', star: 'Starry Night', arcade: 'Arcade' },
  ja: { play: 'あそびば', neon: 'ステージ', sakura: 'さくら', chalk: 'こくばん', star: 'ほしぞら', arcade: 'ゲーセン' },
}

// Every key App.i18n.test.jsx reads.
const CONTRACT_KEYS = [
  ...Object.keys(CONTRACT_KO),
  'reveal.label.ko',
  'reveal.label.en',
  'reveal.label.ja',
  'reveal.label.romaji',
  ...Object.keys(THEME_NAMES.ko).map((id) => `theme.${id}`),
]

describe('i18n dictionaries', () => {
  it('ko, en and ja have exactly the same key set', () => {
    const [ko, en, ja] = LANGS.map((lang) => Object.keys(flatten(readDict(lang))).sort())
    expect(ko.length).toBeGreaterThan(0)
    expect(en).toEqual(ko)
    expect(ja).toEqual(ko)
  })

  it.each(LANGS)('%s has a non-empty string for every key', (lang) => {
    const flat = flatten(readDict(lang))
    const bad = Object.entries(flat)
      .filter(([, value]) => typeof value !== 'string' || value.trim() === '')
      .map(([key]) => key)
    expect(bad).toEqual([])
  })

  it('every key uses the same {{variables}} in ko, en and ja', () => {
    const [ko, en, ja] = LANGS.map((lang) => flatten(readDict(lang)))
    const mismatched = Object.keys(ko).filter((key) => {
      if (typeof ko[key] !== 'string' || typeof en[key] !== 'string' || typeof ja[key] !== 'string') return false
      const want = variables(ko[key]).join(',')
      return variables(en[key]).join(',') !== want || variables(ja[key]).join(',') !== want
    })
    expect(mismatched).toEqual([])
  })

  it.each(LANGS)('%s has every key the App tests rely on', (lang) => {
    const flat = flatten(readDict(lang))
    expect(CONTRACT_KEYS.filter((key) => typeof flat[key] !== 'string')).toEqual([])
  })

  it('the Korean contract values are the texts the locked tests read', () => {
    const ko = flatten(readDict('ko'))
    for (const [key, value] of Object.entries(CONTRACT_KO)) {
      expect(ko[key], key).toBe(value)
    }
  })

  it('the English start screen reads Create room and Nickname', () => {
    const en = flatten(readDict('en'))
    expect(en['start.createRoom']).toBe('Create room')
    expect(en['start.nickname']).toBe('Nickname')
  })

  it.each(LANGS)('%s theme names are the decided ones', (lang) => {
    const flat = flatten(readDict(lang))
    for (const [id, name] of Object.entries(THEME_NAMES[lang])) {
      expect(flat[`theme.${id}`], `theme.${id}`).toBe(name)
    }
  })
})
