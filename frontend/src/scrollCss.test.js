import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 118, revision 4: above 900 px wide the whole page scrolls instead of
// .stage, and the chat is not sticky: it spans from under the header to the
// bottom of the screen and scrolls away with the page
// (docs/cycles/118-choice-deck.md, Revision 4). Cycle 119 review: the header
// height comes from --hd, measured on .app, instead of fixed 67/114 px
// (docs/cycles/119-nickname-dialog.md, Pre-PR review fixes).
// - scroll.css holds exactly the rules from the spec; comments and whitespace
//   do not matter, a trailing semicolon before } is optional, and "@media("
//   counts the same as "@media (" wherever it appears.
// - main.jsx imports ./scroll.css after ./choice.css.
// Runs in the node project: it reads the files through import.meta.url.

const SCROLL_CSS = fileURLToPath(new URL('./scroll.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED =
  '@media (min-width:901px){' +
  'html,body{height:auto}' +
  '.app{height:auto;min-height:100vh}' +
  '.stage{overflow:visible}' +
  '.chat{align-self:start;height:calc(100vh - var(--hd,67px))}}'

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

  it('holds exactly the rules from the spec', () => {
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
