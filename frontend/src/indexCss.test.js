import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 110 (D-124): index.css keeps only the Tailwind import and what the
// screens outside the mockup still need (docs/cycles/110-room-port.md,
// Stylesheet 2 and Acceptance 2).
// - No [data-room-theme selector is left.
// - No rule, at any depth, names a class that room.css defines: nothing old
//   may override a mockup rule. Rules that only set custom properties on
//   :root (the interim aliases) name no class and are allowed.
// - The interim aliases until 114: --soft, --good and --good-text point at
//   the mockup tokens, --bad and --warn are set, and every var(--x) that
//   @theme inline maps for Tailwind is defined in index.css or room.css.
// - room.css loads after index.css: imported after it in main.jsx, or
//   @imported by index.css after the Tailwind import.
// Runs in the node project: it reads the files through import.meta.url.

const INDEX_CSS = fileURLToPath(new URL('./index.css', import.meta.url))
const ROOM_CSS = fileURLToPath(new URL('./room.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

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

// Every block at any depth as { prelude, declarations: [[name, value]] }.
// Semicolons and braces inside quotes or parentheses do not split.
function parseBlocks(css) {
  const root = { prelude: '', statements: [], children: [], parent: null }
  let node = root
  let buf = ''
  let quote = null
  let parens = 0
  for (let i = 0; i < css.length; i++) {
    const c = css[i]
    if (quote) {
      buf += c
      if (c === '\\' && i + 1 < css.length) buf += css[++i]
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") {
      quote = c
      buf += c
      continue
    }
    if (c === '(') parens++
    if (c === ')') parens = Math.max(0, parens - 1)
    if (parens > 0 || c === ')') {
      buf += c
      continue
    }
    if (c === '{') {
      const child = { prelude: buf.trim(), statements: [], children: [], parent: node }
      node.children.push(child)
      node = child
      buf = ''
    } else if (c === ';') {
      node.statements.push(buf)
      buf = ''
    } else if (c === '}') {
      if (node === root) throw new Error('unbalanced } at offset ' + i)
      node.statements.push(buf)
      buf = ''
      node = node.parent
    } else {
      buf += c
    }
  }
  if (node !== root) throw new Error('unclosed block: ' + node.prelude)
  const blocks = []
  const visit = (n) => {
    for (const child of n.children) {
      const declarations = []
      for (const raw of child.statements) {
        const statement = raw.trim()
        const colon = statement.indexOf(':')
        if (statement === '' || statement.startsWith('@') || colon === -1) continue
        declarations.push([statement.slice(0, colon).trim(), statement.slice(colon + 1).trim()])
      }
      blocks.push({ prelude: child.prelude.replace(/\s+/g, ' '), declarations })
      visit(child)
    }
  }
  visit(root)
  return blocks
}

// The class names a selector list names; quoted strings are ignored.
function classesOf(prelude) {
  const bare = prelude.replace(/"[^"]*"|'[^']*'/g, '""')
  return Array.from(bare.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g), (m) => m[1])
}

// The style blocks (rules, not at-rules) of a parsed sheet.
function styleRules(blocks) {
  return blocks.filter((b) => !b.prelude.startsWith('@') && !/^(from|to|\d+(\.\d+)?%)(\s*,\s*(from|to|\d+(\.\d+)?%))*$/.test(b.prelude))
}

function indexBlocks() {
  return parseBlocks(stripComments(read(INDEX_CSS)))
}

function roomBlocks() {
  return parseBlocks(stripComments(read(ROOM_CSS)))
}

// Custom properties defined anywhere in a parsed sheet.
function definedProps(blocks) {
  const names = new Set()
  for (const block of blocks) {
    for (const [name] of block.declarations) if (name.startsWith('--')) names.add(name)
  }
  return names
}

// The custom properties set by :root rules of index.css, name -> value.
function rootAliases() {
  const out = new Map()
  for (const block of styleRules(indexBlocks())) {
    const selectors = block.prelude.split(',').map((s) => s.trim())
    if (!selectors.includes(':root')) continue
    for (const [name, value] of block.declarations) if (name.startsWith('--')) out.set(name, value.replace(/\s+/g, ' '))
  }
  return out
}

describe('index.css after the port', () => {
  it('has no [data-room-theme selector', () => {
    expect(stripComments(read(INDEX_CSS))).not.toContain('[data-room-theme')
  })

  it('room.css exists and defines classes (the reference for the next check)', () => {
    expect(fs.existsSync(ROOM_CSS), 'frontend/src/room.css').toBe(true)
    const classes = new Set(styleRules(roomBlocks()).flatMap((b) => classesOf(b.prelude)))
    for (const cls of ['logo', 'stage-top', 'clock', 'timebar', 'seat', 'msg', 'reveal', 'panel', 'btn']) {
      expect(classes.has(cls), `.${cls} in room.css`).toBe(true)
    }
  })

  it('has no rule, at any depth, for a class that room.css defines', () => {
    const roomClasses = new Set(styleRules(roomBlocks()).flatMap((b) => classesOf(b.prelude)))
    const clashes = []
    for (const block of styleRules(indexBlocks())) {
      const shared = classesOf(block.prelude).filter((cls) => roomClasses.has(cls))
      if (shared.length > 0) clashes.push(`${block.prelude} (${shared.map((c) => '.' + c).join(' ')})`)
    }
    expect(clashes).toEqual([])
  })

  it(':root rules in index.css set custom properties only', () => {
    for (const block of styleRules(indexBlocks())) {
      const selectors = block.prelude.split(',').map((s) => s.trim())
      if (!selectors.includes(':root')) continue
      for (const [name] of block.declarations) expect(name.startsWith('--'), `${block.prelude} sets ${name}`).toBe(true)
    }
  })

  it('defines the interim aliases on :root: --soft, --good, --good-text from the mockup tokens; --bad and --warn set', () => {
    const aliases = rootAliases()
    expect(aliases.get('--soft')).toBe('var(--dim)')
    expect(aliases.get('--good')).toBe('var(--ok)')
    expect(aliases.get('--good-text')).toBe('var(--ok)')
    expect(aliases.get('--bad'), '--bad').toBeTruthy()
    expect(aliases.get('--warn'), '--warn').toBeTruthy()
  })

  it('every token that @theme inline reads is defined in index.css or room.css', () => {
    const blocks = indexBlocks()
    const theme = blocks.filter((b) => /^@theme\b/.test(b.prelude))
    const defined = new Set([...definedProps(blocks), ...definedProps(roomBlocks())])
    const missing = []
    for (const block of theme) {
      for (const [name, value] of block.declarations) {
        for (const m of value.matchAll(/var\(\s*(--[\w-]+)/g)) {
          if (!defined.has(m[1])) missing.push(`${name}: ${m[1]}`)
        }
      }
    }
    expect(missing).toEqual([])
  })
})

describe('load order', () => {
  it('room.css loads after index.css', () => {
    const main = read(MAIN_JSX)
    const indexAt = main.search(/import\s+['"]\.\/index\.css['"]/)
    const roomInMain = main.search(/import\s+['"]\.\/room\.css['"]/)
    const css = stripComments(read(INDEX_CSS))
    const tailwindAt = css.search(/@import\s+["']tailwindcss["']/)
    const roomInIndex = css.search(/@import\s+["']\.\/room\.css["']/)
    const viaMain = indexAt !== -1 && roomInMain > indexAt
    const viaIndex = tailwindAt !== -1 && roomInIndex > tailwindAt && roomInMain === -1
    expect(viaMain || viaIndex, 'room.css imported after index.css (main.jsx) or @imported by index.css after tailwindcss').toBe(true)
  })
})
