import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 118 (D-126, D-127), revision 2: on stages wider than 620 px a choice
// game puts the deck in the top left with the panel under it (28 % of the
// .now width, capped by the height), and the covers take the whole right
// column, sized so two rows fit the height left after --oh; .inner loses its
// 960 px cap and the deck keeps its size at the reveal. On screens at least
// 1300 px wide the .moodbar sits on the logo row, and choice games at 900 px
// height or less hide the panel's .sub text
// (docs/cycles/118-choice-deck.md, Revision 2).
// - choice.css holds exactly the rule from the spec; comments and whitespace
//   do not matter, a trailing semicolon before } is optional, and
//   "@media(" / "@container(" count the same as "@media (" / "@container (".
//   Quoted strings (the grid-template-areas values) are compared literally,
//   quotes included.
// - main.jsx imports ./choice.css after ./start.css.
// Runs in the node project: it reads the files through import.meta.url.

const CHOICE_CSS = fileURLToPath(new URL('./choice.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED =
  '@media (min-width:1300px){.moodbar{flex-basis:auto}}' +
  '@media (max-height:900px){.choice-mode .now .sub{display:none}}' +
  '.choice-mode{--oh:290px}' +
  '@media (min-width:1300px){.choice-mode{--oh:242px}}' +
  '@container (min-width:621px){' +
  '.choice-mode .inner{max-width:none}' +
  '.choice-mode .now,.choice-mode .now:has(.revealed){' +
  'grid-template-columns:clamp(200px,min(calc(100vh - 400px),28%),340px) minmax(0,1fr);' +
  'grid-template-areas:"deck choices" "panel choices";' +
  'grid-template-rows:auto 1fr;align-items:start;gap:16px 28px}' +
  '.choice-mode .choices.k4,.choice-mode .choices.k6,.choice-mode .choices.k8{' +
  '--tw:calc((100vh - var(--oh)) / 2.75);' +
  'max-width:calc(var(--cols) * var(--tw) + (var(--cols) - 1) * 12px);' +
  'padding-top:0;border-top:0;align-self:start}' +
  '.choice-mode .now .hint{font-size:1.15rem}}'

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Drops comments and whitespace that carries no meaning: runs collapse to one
// space, spaces around { } ; , go, spaces after : and inside ( ) go, and a ;
// right before } goes. A space before : is kept, because ".now :has" and
// ".now:has" are different selectors. The descendant combinator space, the
// spaces around "-" in calc() and the space between value tokens stay.
// Quoted strings are set aside first and put back unchanged, so the text
// inside "deck choices" is compared as written.
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

describe('choice.css', () => {
  it('exists', () => {
    expect(fs.existsSync(CHOICE_CSS), 'frontend/src/choice.css').toBe(true)
  })

  it('holds exactly the one rule from the spec', () => {
    expect(normalize(read(CHOICE_CSS))).toBe(normalize(EXPECTED))
  })
})

describe('load order', () => {
  it('main.jsx imports ./choice.css after ./start.css', () => {
    const main = read(MAIN_JSX)
    const startAt = main.search(/import\s+['"]\.\/start\.css['"]/)
    const choiceAt = main.search(/import\s+['"]\.\/choice\.css['"]/)
    expect(startAt, "import './start.css' in main.jsx").not.toBe(-1)
    expect(choiceAt, "import './choice.css' in main.jsx").not.toBe(-1)
    expect(choiceAt > startAt, './choice.css imported after ./start.css').toBe(true)
  })
})
