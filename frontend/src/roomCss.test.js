import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 110 (D-124): src/room.css is the mockup's second <style> block,
// rule for rule, with only the demo-only rules left out
// (docs/cycles/110-room-port.md, Stylesheet 1 and Acceptance 1).
// - Comments are stripped and whitespace is normalised on both sides; the
//   reference may have CRLF line endings after checkout.
// - Rules inside @media and @container are compared one by one, each with
//   its condition; @keyframes are compared as whole blocks.
// - A rule is left out when any selector in its list names one of the
//   demo-only classes, at any depth; its enclosing @media or @container
//   keeps its other rules.
// - index.html carries the reference's font <link> byte for byte and starts
//   in the sunset mood (Stylesheet 3 and 4).
// Runs in the node project: it reads the files through import.meta.url.

const REFERENCE = fileURLToPath(new URL('../../docs/design/108-ui-design-reference.html', import.meta.url))
const ROOM_CSS = fileURLToPath(new URL('./room.css', import.meta.url))
const INDEX_HTML = fileURLToPath(new URL('../index.html', import.meta.url))

// The demo-only classes (spec Stylesheet 1).
const DEMO_CLASSES = ['cheat', 'try', 'trymsg', 'vh', 'note', 'rar', 'speed', 'viewas', 'backlobby']
const DEMO_RE = new RegExp(`\\.(?:${DEMO_CLASSES.join('|')})(?![\\w-])`)
// At-rules whose block is compared whole.
const WHOLE_AT = /^@(?:-webkit-)?keyframes\b|^@font-face\b|^@property\b|^@page\b|^@counter-style\b/
// At-rules whose block holds rules, compared rule by rule.
const GROUP_AT = /^@(?:media|container|supports|layer|scope|starting-style)\b/

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Drops comments, leaving quoted strings intact.
function stripComments(css) {
  let out = ''
  let quote = null
  for (let i = 0; i < css.length; i++) {
    const c = css[i]
    if (quote) {
      out += c
      if (c === '\\' && i + 1 < css.length) out += css[++i]
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") {
      quote = c
      out += c
      continue
    }
    if (c === '/' && css[i + 1] === '*') {
      const end = css.indexOf('*/', i + 2)
      if (end === -1) throw new Error('unclosed comment at offset ' + i)
      out += ' '
      i = end + 1
      continue
    }
    out += c
  }
  return out
}

// Splits text on `sep` outside quotes, parentheses and brackets.
function splitTop(text, sep) {
  const parts = []
  let buf = ''
  let quote = null
  let depth = 0
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quote) {
      buf += c
      if (c === '\\' && i + 1 < text.length) buf += text[++i]
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") quote = c
    else if (c === '(' || c === '[') depth++
    else if (c === ')' || c === ']') depth = Math.max(0, depth - 1)
    else if (c === sep && depth === 0) {
      parts.push(buf)
      buf = ''
      continue
    }
    buf += c
  }
  parts.push(buf)
  return parts
}

const ws = (s) => s.replace(/\s+/g, ' ').trim()

function normSelector(prelude) {
  return splitTop(ws(prelude), ',')
    .map((s) => ws(s).replace(/\s*([>+~])\s*/g, '$1'))
    .join(',')
}

function normCondition(prelude) {
  return ws(prelude)
    .replace(/\(\s*/g, '(')
    .replace(/\s*\)/g, ')')
    .replace(/\s*:\s*/g, ':')
}

function normDeclarations(body) {
  return splitTop(body, ';')
    .map((d) => ws(d))
    .filter((d) => d !== '')
    .map((d) => {
      const colon = d.indexOf(':')
      if (colon === -1) return d
      return `${d.slice(0, colon).trim()}:${d.slice(colon + 1).trim()}`
    })
}

function normWhole(body) {
  return ws(body)
    .replace(/\s*([{};:,])\s*/g, '$1')
    .replace(/;}/g, '}')
    .replace(/;$/, '')
}

// The index of the } that closes the { at `open`.
function closing(css, open) {
  let depth = 0
  let quote = null
  for (let i = open; i < css.length; i++) {
    const c = css[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") quote = c
    else if (c === '{') depth++
    else if (c === '}') {
      depth--
      if (depth === 0) return i
    }
  }
  throw new Error('unclosed block at offset ' + open)
}

// The rule list of a stylesheet: { context, kind, selector, text }, where
// text is the normalised rule with its context, and selector is the rule's
// selector list (null for whole at-rule blocks and statements).
function parseRules(css, context = []) {
  const items = []
  let i = 0
  let start = 0
  let quote = null
  let parens = 0
  while (i < css.length) {
    const c = css[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
      i++
      continue
    }
    if (c === '"' || c === "'") quote = c
    else if (c === '(') parens++
    else if (c === ')') parens = Math.max(0, parens - 1)
    else if (parens === 0 && c === ';') {
      const statement = ws(css.slice(start, i))
      if (statement !== '') items.push(item(context, 'statement', null, normCondition(statement)))
      start = i + 1
    } else if (parens === 0 && c === '{') {
      const prelude = ws(css.slice(start, i))
      const end = closing(css, i)
      const body = css.slice(i + 1, end)
      if (WHOLE_AT.test(prelude)) {
        items.push(item(context, 'at', null, `${normCondition(prelude)}{${normWhole(body)}}`))
      } else if (GROUP_AT.test(prelude)) {
        items.push(...parseRules(body, [...context, normCondition(prelude)]))
      } else if (prelude.startsWith('@')) {
        throw new Error('unexpected at-rule: ' + prelude)
      } else {
        const selector = normSelector(prelude)
        items.push(item(context, 'rule', selector, `${selector}{${normDeclarations(body).join(';')}}`))
      }
      i = end + 1
      start = i
      continue
    } else if (c === '}') {
      throw new Error('unbalanced } at offset ' + i)
    }
    i++
  }
  if (ws(css.slice(start)) !== '') throw new Error('trailing text: ' + ws(css.slice(start)).slice(0, 80))
  return items
}

function item(context, kind, selector, body) {
  const text = context.length === 0 ? body : `${context.join(' >> ')} >> ${body}`
  return { context, kind, selector, text }
}

function isDemo(rule) {
  return rule.kind === 'rule' && DEMO_RE.test(rule.selector.replace(/"[^"]*"|'[^']*'/g, '""'))
}

// The reference's second <style> block.
function referenceCss() {
  const html = read(REFERENCE)
  const blocks = Array.from(html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/gi), (m) => m[1])
  expect(blocks.length, 'the reference has at least two <style> blocks').toBeGreaterThanOrEqual(2)
  return blocks[1]
}

const REF_RULES = parseRules(stripComments(referenceCss()))
const REF_KEPT = REF_RULES.filter((r) => !isDemo(r))
const REF_DEMO = REF_RULES.filter(isDemo)

describe('the reference parse (sanity)', () => {
  it('finds hundreds of rules, nested @media and @container rules and whole @keyframes', () => {
    expect(REF_RULES.length).toBeGreaterThan(300)
    expect(REF_RULES.some((r) => r.context.some((c) => c.startsWith('@media')))).toBe(true)
    expect(REF_RULES.some((r) => r.context.some((c) => c.startsWith('@container')))).toBe(true)
    expect(REF_RULES.some((r) => r.kind === 'at' && r.text.startsWith('@keyframes glitch{'))).toBe(true)
    expect(REF_RULES.filter((r) => r.kind === 'statement')).toEqual([])
  })

  it('every demo-only class names at least one reference rule, and the nested .cheat rule is among them', () => {
    for (const cls of DEMO_CLASSES) {
      const re = new RegExp(`\\.${cls}(?![\\w-])`)
      expect(REF_DEMO.some((r) => re.test(r.selector)), `.${cls}`).toBe(true)
    }
    expect(REF_DEMO.some((r) => r.context.includes('@media (max-width:900px)') && r.selector === '.cheat')).toBe(true)
    expect(REF_DEMO.some((r) => r.selector === '[data-mood="manga"] .cheat')).toBe(true)
    // The 900px block keeps its other rules, the hidden volume among them (D-124).
    expect(REF_KEPT.some((r) => r.context.includes('@media (max-width:900px)') && r.selector === '.vol')).toBe(true)
  })
})

describe('room.css', () => {
  it('exists', () => {
    expect(fs.existsSync(ROOM_CSS), 'frontend/src/room.css').toBe(true)
  })

  it("has exactly the reference's rules, less the demo-only ones, in the same order", () => {
    const room = parseRules(stripComments(read(ROOM_CSS)))
    expect(room.map((r) => r.text)).toEqual(REF_KEPT.map((r) => r.text))
  })

  it('names no demo-only class in any rule', () => {
    const room = parseRules(stripComments(read(ROOM_CSS)))
    expect(room.filter(isDemo).map((r) => r.text)).toEqual([])
  })
})

describe('index.html', () => {
  it("contains the reference's Google Fonts stylesheet <link> exactly", () => {
    const ref = read(REFERENCE)
    const links = ref.match(/<link rel="stylesheet"[^>]*>/g)
    expect(links, 'the reference has one stylesheet link').toHaveLength(1)
    expect(links[0]).toContain('https://fonts.googleapis.com/css2?')
    expect(read(INDEX_HTML)).toContain(links[0])
  })

  it('starts in the sunset mood: <html data-mood="sunset">', () => {
    const html = read(INDEX_HTML)
    expect(html).toMatch(/<html\b[^>]*\sdata-mood="sunset"[^>]*>/)
  })
})
