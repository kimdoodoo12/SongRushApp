import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 109 (F-025): the eight moods' token blocks in index.css. Each
// [data-room-theme="<id>"] block defines exactly the custom properties of
// the default block (the rule whose selector list has .app-shell on its own
// and that defines the look tokens such as --a1), the default equals sunset,
// and no rule names the old ids play, star or arcade
// (docs/cycles/109-eight-moods.md, Acceptance 2).
// Runs in the node project: it reads the file through import.meta.url.

const INDEX_CSS = fileURLToPath(new URL('./index.css', import.meta.url))

const MOODS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const OLD_IDS = ['play', 'star', 'arcade']

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

// The selectors of a rule's selector list, each normalised: no spaces inside
// attribute brackets and double quotes for the value.
function selectors(prelude) {
  if (prelude.startsWith('@')) return []
  return prelude.split(',').map((s) =>
    s
      .trim()
      .replace(/\[\s*data-room-theme\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]*))\s*\]/g, (m, a, b, c) => `[data-room-theme="${a ?? b ?? c}"]`),
  )
}

function customProps(blocks) {
  const out = new Map()
  for (const block of blocks) {
    for (const [name, value] of block.declarations) {
      if (name.startsWith('--')) out.set(name, value)
    }
  }
  return out
}

const BLOCKS = parseBlocks(stripComments(fs.readFileSync(INDEX_CSS, 'utf8')))

function blocksFor(selector) {
  return BLOCKS.filter((b) => selectors(b.prelude).includes(selector))
}

// The default look: the .app-shell rule(s) that define the look tokens.
function defaultBlocks() {
  return blocksFor('.app-shell').filter((b) => b.declarations.some(([name]) => name === '--a1'))
}

function sorted(map) {
  return [...map.keys()].sort()
}

describe('index.css mood token blocks', () => {
  it('has one default token block on .app-shell with the look tokens', () => {
    const blocks = defaultBlocks()
    expect(blocks).toHaveLength(1)
    expect(customProps(blocks).size).toBeGreaterThan(10)
  })

  it.each(MOODS)('the %s block defines exactly the token names of the default block', (id) => {
    const blocks = blocksFor(`[data-room-theme="${id}"]`)
    expect(blocks.length, `no [data-room-theme="${id}"] block`).toBeGreaterThan(0)
    expect(sorted(customProps(blocks))).toEqual(sorted(customProps(defaultBlocks())))
  })

  it('the default look equals sunset, token by token', () => {
    const sunset = customProps(blocksFor('[data-room-theme="sunset"]'))
    const fallback = customProps(defaultBlocks())
    expect(Object.fromEntries(sunset)).toEqual(Object.fromEntries(fallback))
  })

  it('manga is the one light mood; every other mood is dark', () => {
    for (const id of MOODS) {
      const scheme = customProps(blocksFor(`[data-room-theme="${id}"]`)).get('--scheme')
      expect(scheme, id).toBe(id === 'manga' ? 'light' : 'dark')
    }
  })

  it.each(OLD_IDS)('no rule names the old id %s', (id) => {
    const naming = BLOCKS.filter((b) => selectors(b.prelude).some((s) => s.includes(`[data-room-theme="${id}"]`)))
    expect(naming.map((b) => b.prelude)).toEqual([])
  })

  it('the only theme ids any rule names are the eight moods', () => {
    const ids = new Set()
    for (const block of BLOCKS) {
      for (const s of selectors(block.prelude)) {
        for (const m of s.matchAll(/\[data-room-theme="([^"]*)"\]/g)) ids.add(m[1])
      }
    }
    expect([...ids].filter((id) => !MOODS.includes(id))).toEqual([])
  })
})
