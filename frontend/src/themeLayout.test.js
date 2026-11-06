import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 076: switching the room theme must not move or resize anything, so
// rules scoped to a theme may change only the look, never a layout property.
// Cycle 077 tightens this: no border shorthand at all, no border removal, no
// text or overflow properties, and no custom property that a layout property
// reads through var().

const INDEX_CSS = fileURLToPath(new URL('./index.css', import.meta.url))

// A selector naming a theme, such as [data-room-theme="chalk"] .room.
const THEME_SELECTOR = /\[\s*data-room-theme\s*[~|^$*]?=/

// Replaces every comment with a space, leaving quoted strings intact.
function stripComments(css) {
  let out = ''
  let quote = null
  let i = 0
  while (i < css.length) {
    const c = css[i]
    if (quote) {
      out += c
      if (c === '\\' && i + 1 < css.length) {
        out += css[i + 1]
        i += 2
        continue
      }
      if (c === quote) quote = null
      i++
      continue
    }
    if (c === '"' || c === "'") {
      quote = c
      out += c
      i++
      continue
    }
    if (c === '/' && css[i + 1] === '*') {
      const end = css.indexOf('*/', i + 2)
      if (end === -1) throw new Error('unterminated comment at offset ' + i)
      out += ' '
      i = end + 2
      continue
    }
    out += c
    i++
  }
  return out
}

// Splits the stylesheet into a tree of blocks. Each block keeps its prelude
// (selector list or at-rule) and the statements between its child blocks.
// Braces and semicolons inside strings or parentheses do not count.
function parseTree(css) {
  const text = stripComments(css)
  const root = { prelude: null, parent: null, children: [], statements: [] }
  let node = root
  let buf = ''
  let quote = null
  let parens = 0
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quote) {
      buf += c
      if (c === '\\' && i + 1 < text.length) {
        buf += text[++i]
      } else if (c === quote) {
        quote = null
      }
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
      const child = { prelude: buf.trim(), parent: node, children: [], statements: [] }
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
  root.statements.push(buf)
  return root
}

// Flattens the tree into rules with their declarations. A rule is theme
// scoped when its selector list names a theme or it sits inside one
// (nested rules, or an at-rule nested in a theme rule).
function parseRules(css) {
  const rules = []
  const visit = (node, path, themeScoped) => {
    for (const child of node.children) {
      const isAtRule = child.prelude.startsWith('@')
      const scoped = themeScoped || (!isAtRule && THEME_SELECTOR.test(child.prelude))
      const childPath = [...path, child.prelude.replace(/\s+/g, ' ')]
      const declarations = []
      for (const raw of child.statements) {
        const statement = raw.trim()
        if (statement === '') continue
        if (statement.startsWith('@')) {
          declarations.push({ property: statement.split(/\s/)[0], value: statement })
          continue
        }
        const colon = statement.indexOf(':')
        if (colon === -1) {
          throw new Error('not a declaration in ' + childPath.join(' > ') + ': ' + statement)
        }
        let property = statement.slice(0, colon).trim()
        if (!property.startsWith('--')) property = property.toLowerCase()
        const value = statement.slice(colon + 1).trim()
        declarations.push({ property, value })
      }
      rules.push({ path: childPath, themeScoped: scoped, declarations })
      visit(child, childPath, scoped)
    }
  }
  visit(parseTree(css), [], false)
  return rules
}

const FORBIDDEN_EXACT = new Set([
  'width', 'height', 'block-size', 'inline-size',
  'top', 'right', 'bottom', 'left',
  'font', 'font-size', 'line-height', 'letter-spacing', 'font-weight', 'word-spacing',
  'display', 'position', 'aspect-ratio', 'row-gap', 'column-gap',
  '--bw', '--pad',
])
const FORBIDDEN_PREFIX = /^(padding|margin|gap|min-|max-|inset|grid|flex)/
// Cycle 077: properties that change text runs, overflow or box metrics
// without reading as a size.
const FORBIDDEN_TEXT_AND_BOX = new Set([
  'content', 'text-transform', 'white-space', 'box-sizing', 'float', 'zoom',
  'writing-mode', 'font-feature-settings',
])
const FORBIDDEN_TEXT_AND_BOX_PREFIX = /^(overflow|font-variant)/
const LAYOUT_CUSTOM = /^--.*-(size|width|height|gap|pad)$/
const BORDER_SHORTHAND = /^border(-(top|right|bottom|left|block|inline)(-(start|end))?)?$/
const BORDER_STYLE = /^border(-(top|right|bottom|left|block|inline)(-(start|end))?)?-style$/
const NO_BORDER_STYLE = /(^|[^\w-])(none|hidden)($|[^\w-])/
// A value carries a width when it has a number (0 included), a width
// keyword, or a var() whose name reads as a width.
const BORDER_WIDTH_VALUE =
  /(^|[\s(,])[-+]?(\d|\.\d)|\b(thin|medium|thick)\b|var\(\s*--(bw|[\w-]*-width)\s*[,)]/
const VAR_NAME = /var\(\s*(--[\w-]+)/g

// Drops the arguments of every function except var(), so the numbers in a
// colour such as rgb(0 0 0 / 0.2) do not read as a border width.
function withoutColourFunctions(value) {
  let previous
  let current = value
  do {
    previous = current
    current = current.replace(/([\w-]+)\(([^()]*)\)/g, (match, name) => (name === 'var' ? match : ' '))
  } while (current !== previous)
  return current
}

function readsAsWidth(value) {
  return BORDER_WIDTH_VALUE.test(withoutColourFunctions(value))
}

function removesBorder(value) {
  return NO_BORDER_STYLE.test(withoutColourFunctions(value))
}

// Ignores a vendor prefix, so -webkit-box-sizing counts as box-sizing.
function unprefixed(property) {
  return property.replace(/^-(webkit|moz|ms|o)-/, '')
}

// Why a regular (non-custom) property may not appear in a theme rule,
// whatever its value, or null.
function layoutPropertyReason(property) {
  const p = unprefixed(property)
  if (FORBIDDEN_EXACT.has(p) || FORBIDDEN_PREFIX.test(p)) return 'layout property'
  if (p.startsWith('border') && p.endsWith('-width')) return 'border width'
  if (BORDER_SHORTHAND.test(p)) return 'border shorthand'
  if (FORBIDDEN_TEXT_AND_BOX.has(p) || FORBIDDEN_TEXT_AND_BOX_PREFIX.test(p)) return 'text or box property'
  return null
}

function varNames(value) {
  return [...value.matchAll(VAR_NAME)].map((m) => m[1])
}

// Custom properties that reach the layout through var(), anywhere in the
// stylesheet:
// - layout: used in a layout property, so a theme may not declare them;
// - border: used in a border shorthand, where a theme may still set a colour
//   but not a width or a none/hidden style;
// - style: used in a border style, where a theme may not set none/hidden.
// A custom property whose value feeds one of these names joins the same set.
function layoutVariables(rules) {
  const sets = { layout: new Set(), border: new Set(), style: new Set() }
  const customs = []
  for (const rule of rules) {
    for (const { property, value } of rule.declarations) {
      if (property.startsWith('@')) continue
      if (property.startsWith('--')) {
        customs.push({ property, value })
        continue
      }
      const p = unprefixed(property)
      let target = null
      if (BORDER_SHORTHAND.test(p)) target = sets.border
      else if (BORDER_STYLE.test(p)) target = sets.style
      else if (layoutPropertyReason(p)) target = sets.layout
      if (target) varNames(value).forEach((n) => target.add(n))
    }
  }
  for (const set of Object.values(sets)) {
    let grew = true
    while (grew) {
      grew = false
      for (const { property, value } of customs) {
        if (!set.has(property)) continue
        for (const n of varNames(value)) {
          if (!set.has(n)) {
            set.add(n)
            grew = true
          }
        }
      }
    }
  }
  return sets
}

// Why a declaration may not appear in a theme rule, or null when it may.
function layoutReason(property, value, vars) {
  if (property.startsWith('@')) return 'at-rule statement (cannot be checked for layout)'
  if (property.startsWith('--')) {
    if (FORBIDDEN_EXACT.has(property) || LAYOUT_CUSTOM.test(property)) return 'layout custom property'
    if (vars.layout.has(property)) return 'custom property used by a layout property'
    if (vars.border.has(property) && (readsAsWidth(value) || removesBorder(value))) {
      return 'custom property sets a border width or removes a border'
    }
    if (vars.style.has(property) && removesBorder(value)) return 'custom property removes a border'
    return null
  }
  const reason = layoutPropertyReason(property)
  if (reason) return reason
  if (BORDER_STYLE.test(unprefixed(property)) && removesBorder(value)) return 'border style removes the border'
  return null
}

function themeLayoutViolations(css) {
  const rules = parseRules(css)
  const vars = layoutVariables(rules)
  const violations = []
  for (const rule of rules) {
    if (!rule.themeScoped) continue
    for (const { property, value } of rule.declarations) {
      const reason = layoutReason(property, value, vars)
      if (reason) {
        const text = property.startsWith('@') ? value : property + ': ' + value
        violations.push(rule.path.join(' > ') + ' :: ' + text + '  (' + reason + ')')
      }
    }
  }
  return violations
}

// Every declaration of --bw, with where it sits.
function bwDeclarations(css) {
  const found = []
  for (const rule of parseRules(css)) {
    for (const { property, value } of rule.declarations) {
      if (property === '--bw') {
        found.push({ where: rule.path.join(' > '), value, themeScoped: rule.themeScoped })
      }
    }
  }
  return found
}

describe('theme rules keep the layout (cycles 076, 077)', () => {
  const css = fs.readFileSync(INDEX_CSS, 'utf8')

  it('no rule scoped to [data-room-theme=...] sets a layout property', () => {
    expect(themeLayoutViolations(css)).toEqual([])
  })

  it('--bw is declared exactly once, outside every theme rule', () => {
    const found = bwDeclarations(css)
    const listing = found.map((d) => d.where + ' :: --bw: ' + d.value).join('\n')
    expect(found.length, '--bw declarations:\n' + listing).toBe(1)
    expect(found.filter((d) => d.themeScoped).map((d) => d.where)).toEqual([])
  })

  it('parses the real stylesheet and finds the theme rules (positive control)', () => {
    const rules = parseRules(css)
    expect(rules.some((r) => r.themeScoped)).toBe(true)
    expect(rules.some((r) => r.path.includes('.panel'))).toBe(true)
  })
})

describe('theme layout parser (self-tests)', () => {
  it('finds a theme rule nested in @media and @layer', () => {
    const css = `
      @layer components {
        @media (min-width: 768px) {
          [data-room-theme="chalk"] .room { padding: 26px; color: red; }
        }
      }`
    expect(themeLayoutViolations(css)).toEqual([
      '@layer components > @media (min-width: 768px) > [data-room-theme="chalk"] .room :: padding: 26px  (layout property)',
    ])
  })

  it('ignores comments, including ones holding braces or theme selectors', () => {
    const css = `
      /* [data-room-theme="x"] { padding: 1px } */
      [data-room-theme="x"] .a {
        /* font-size: 40px; } */
        color: red;
      }
      .b { /* { */ padding: 2px; }`
    expect(themeLayoutViolations(css)).toEqual([])
    expect(parseRules(css).map((r) => r.path.join(' > '))).toEqual(['[data-room-theme="x"] .a', '.b'])
  })

  it('checks a selector list when any member names a theme', () => {
    const css = `
      .app-shell,
      [data-room-theme="play"] { --bw: 3px; --r: 20px; }
      .a, .b { font-size: 2px; }`
    expect(themeLayoutViolations(css)).toEqual([
      '.app-shell, [data-room-theme="play"] :: --bw: 3px  (layout custom property)',
    ])
  })

  it('treats rules nested in a theme rule as theme scoped', () => {
    const css = `[data-room-theme="x"] { .seat { margin-top: 1px; } &:hover { color: red; } }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="x"] > .seat :: margin-top: 1px  (layout property)',
    ])
  })

  it('keeps semicolons and braces inside strings and parentheses', () => {
    const css = `[data-room-theme="x"] .a { content: "a;b{c}"; background: url("x;{y}.png"); gap: 1px; }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="x"] .a :: content: "a;b{c}"  (text or box property)',
      '[data-room-theme="x"] .a :: gap: 1px  (layout property)',
    ])
  })

  it('allows look-only properties in theme rules', () => {
    const css = `[data-room-theme="x"] .a {
      border-radius: 0; transform: rotate(1deg); filter: blur(1px);
      color: red; background: var(--bg); background-color: #fff;
      font-family: "Gaegu"; font-size-adjust: 0.55; font-style: italic;
      box-shadow: 0 0 0 1px #000; text-shadow: none; outline-color: red;
      animation: sr-spin 1s; animation-timing-function: steps(12);
      border-color: red; border-style: dashed; border-top-color: red; border-left-style: dotted;
      border-inline-start-color: red; border-style: solid double; opacity: 0.5;
      -webkit-text-stroke: 0.03em currentColor; text-decoration: underline; cursor: pointer;
      --r: 10px; --font-d: "Jua"; --shadow: none; --bw-colour: red;
    }`
    expect(themeLayoutViolations(css)).toEqual([])
  })

  it('rejects every layout property family', () => {
    const props = [
      'padding: 1px', 'padding-inline: 1px', 'margin: 0', 'margin-left: 1px', 'gap: 1px', 'row-gap: 1px',
      'width: 1px', 'height: 1px', 'min-height: 1px', 'max-width: 1px', 'inset: 0', 'inset-inline: 0',
      'top: 0', 'right: 0', 'bottom: 0', 'left: 0',
      'font-size: 1em', 'line-height: 1', 'letter-spacing: 0', 'font-weight: 700', 'word-spacing: 0',
      'border-width: 2px', 'border-top-width: 2px', 'border: 2px solid red', 'border-top: thin dashed red',
      'border-left: 0 solid red', 'border: var(--bw) solid red',
      'display: block', 'grid-template-columns: 1fr', 'flex: 1', 'flex-direction: row', 'position: absolute',
      'aspect-ratio: 1', '--bw: 2px', '--pad: 1px', '--title-size: 2px', '--frame-width: 1px',
      '--seat-height: 1px', '--card-gap: 1px', '--room-pad: 1px', 'font: 700 12px sans-serif',
      'border: rgb(0 0 0) solid 1px', '@apply p-4',
      // Cycle 077: any border shorthand, whatever the value.
      'border: solid var(--line)', 'border-bottom: dashed rgb(0 0 0 / 0.2)', 'border: none',
      'border-right: solid red', 'border-inline: dashed red', 'border-inline-start: dotted red',
      'border-block: solid', 'border-block-end: solid red',
      // Cycle 077: removing the border through its style.
      'border-style: none', 'border-style: solid hidden', 'border-top-style: none',
      'border-inline-end-style: hidden',
      // Cycle 077: text and box properties.
      'content: "x"', 'text-transform: uppercase', 'white-space: nowrap', 'overflow: hidden',
      'overflow-x: auto', 'overflow-wrap: anywhere', 'box-sizing: content-box', 'float: left',
      'zoom: 1.1', 'writing-mode: vertical-rl', 'font-variant: small-caps',
      'font-variant-numeric: tabular-nums', 'font-feature-settings: "tnum"',
      '-webkit-box-sizing: border-box',
    ]
    const css = '[data-room-theme="x"] .a { ' + props.join('; ') + ' }'
    const violations = themeLayoutViolations(css)
    expect(violations).toHaveLength(props.length)
    props.forEach((p, i) => expect(violations[i]).toContain(':: ' + p + '  ('))
  })

  it('rejects a theme value for a custom property that a layout property reads', () => {
    const css = `
      .a { padding: calc(var(--s) * 2); }
      @media (x) { .b { width: var(--w, var(--fallback)); } }
      .c { font-variant-numeric: var(--nums); }
      [data-room-theme="x"] { --s: 3px; --fallback: 1px; --nums: tabular-nums; --colour: red; }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="x"] :: --s: 3px  (custom property used by a layout property)',
      '[data-room-theme="x"] :: --fallback: 1px  (custom property used by a layout property)',
      '[data-room-theme="x"] :: --nums: tabular-nums  (custom property used by a layout property)',
    ])
  })

  it('follows custom properties that feed a layout custom property', () => {
    const css = `
      .a { margin: var(--m); }
      .app-shell { --m: var(--unit); --unit: var(--base); }
      [data-room-theme="x"] .z { --base: 4px; }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="x"] .z :: --base: 4px  (custom property used by a layout property)',
    ])
  })

  it('finds the layout use in a theme rule or after it', () => {
    const css = `
      [data-room-theme="x"] .c { --g: 1px; }
      .b { gap: var(--g); }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="x"] .c :: --g: 1px  (custom property used by a layout property)',
    ])
  })

  it('allows theme values for custom properties used only by look properties', () => {
    const css = `
      .a { color: var(--c); box-shadow: inset 0 0 0 var(--ring) var(--c); border-radius: calc(var(--r) - 6px); }
      .b { font-family: var(--font-d); border-color: var(--edge); }
      [data-room-theme="x"] { --c: red; --ring: 3px; --r: 20px; --font-d: "Jua"; --edge: none; }`
    expect(themeLayoutViolations(css)).toEqual([])
  })

  it('lets a theme colour a border shorthand through var() but not size or remove it', () => {
    const css = `
      .a { border: var(--bw) solid var(--line); }
      .b { border-bottom: var(--thick) var(--kind) var(--edge); }
      .c { border-style: var(--st); }
      [data-room-theme="x"] {
        --line: #2b2340; --edge: rgb(0 0 0 / 0.2); --kind: dashed; --st: double;
      }
      [data-room-theme="y"] {
        --line: 3px; --thick: thin; --kind: none; --st: hidden; --edge: 0;
      }`
    expect(themeLayoutViolations(css)).toEqual([
      '[data-room-theme="y"] :: --line: 3px  (custom property sets a border width or removes a border)',
      '[data-room-theme="y"] :: --thick: thin  (custom property sets a border width or removes a border)',
      '[data-room-theme="y"] :: --kind: none  (custom property sets a border width or removes a border)',
      '[data-room-theme="y"] :: --st: hidden  (custom property removes a border)',
      '[data-room-theme="y"] :: --edge: 0  (custom property sets a border width or removes a border)',
    ])
  })

  it('does not check rules outside themes', () => {
    expect(themeLayoutViolations('.room { padding: 18px; --bw: 2px; } @media (x) { .a { width: 1px } }')).toEqual([])
  })

  it('counts --bw declarations, not var(--bw) uses', () => {
    const css = `
      .app-shell { --bw: 2px; }
      .panel { border: var(--bw) solid red; }
      [data-room-theme="neon"] { --bw: 1px; }`
    expect(bwDeclarations(css)).toEqual([
      { where: '.app-shell', value: '2px', themeScoped: false },
      { where: '[data-room-theme="neon"]', value: '1px', themeScoped: true },
    ])
  })

  it('rejects unbalanced braces', () => {
    expect(() => parseRules('.a { color: red;')).toThrow(/unclosed/)
    expect(() => parseRules('.a { color: red; } }')).toThrow(/unbalanced/)
  })
})
