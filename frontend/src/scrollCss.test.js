import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 118, revision 3: above 900 px wide the whole page scrolls instead of
// .stage, and the chat column stays in view (sticky, full viewport height)
// (docs/cycles/118-choice-deck.md, Revision 3).
// - scroll.css holds exactly the rule from the spec; comments and whitespace
//   do not matter, a trailing semicolon before } is optional, and "@media("
//   counts the same as "@media (".
// - main.jsx imports ./scroll.css after ./choice.css.
// Runs in the node project: it reads the files through import.meta.url.

const SCROLL_CSS = fileURLToPath(new URL('./scroll.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED =
  '@media (min-width:901px){' +
  'html,body{height:auto}' +
  '.app{height:auto;min-height:100vh}' +
  '.stage{overflow:visible}' +
  '.chat{position:sticky;top:0;height:100vh;align-self:start}}'

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Same normalization as choiceCss.test.js: comments go, whitespace runs
// collapse, spaces around { } ; , go, spaces after : and inside ( ) go, and a
// ; right before } goes. A space before : is kept (".a :b" differs from
// ".a:b"). Quoted strings are compared as written.
function normalize(css) {
  const strings = []
  const out = css
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/"[^"]*"|'[^']*'/g, (str) => {
      strings.push(str)
      return `@@STR${strings.length - 1}@@`
    })
    .replace(/\s+/g, ' ')
    .replace(/\s*([{};,])\s*/g, '$1')
    .replace(/:\s+/g, ':')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/;}/g, '}')
    .trim()
    .replace(/@(media|container)\(/g, '@$1 (')
  return out.replace(/@@STR(\d+)@@/g, (whole, i) => strings[Number(i)])
}

describe('scroll.css', () => {
  it('exists', () => {
    expect(fs.existsSync(SCROLL_CSS), 'frontend/src/scroll.css').toBe(true)
  })

  it('holds exactly the one rule from the spec', () => {
    expect(normalize(read(SCROLL_CSS))).toBe(normalize(EXPECTED))
  })
})

describe('load order', () => {
  it('main.jsx imports ./scroll.css after ./choice.css', () => {
    const main = read(MAIN_JSX)
    const choiceAt = main.search(/import\s+['"]\.\/choice\.css['"]/)
    const scrollAt = main.search(/import\s+['"]\.\/scroll\.css['"]/)
    expect(choiceAt, "import './choice.css' in main.jsx").not.toBe(-1)
    expect(scrollAt, "import './scroll.css' in main.jsx").not.toBe(-1)
    expect(scrollAt > choiceAt, './scroll.css imported after ./choice.css').toBe(true)
  })
})
