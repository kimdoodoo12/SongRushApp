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
// (docs/cycles/118-choice-deck.md, Revision 2). Cycle 119 review: --oh is
// the measured header height --hd plus 175 px instead of fixed 290/242 px
// (docs/cycles/119-nickname-dialog.md, Pre-PR review fixes).
// Cycle 124 (F-034, D-131): the wrong-pick chat line is red. Three rules
// follow the layout rules: .msg.wrong, and one each for the chalk and manga
// moods (docs/cycles/124-wrong-line-red.md).
// Cycle 138 (issue #66): the tile marks follow the screen's language. room.css
// hard-codes the Korean marks as ::before content and is never edited
// (D-124), so a last rule shows the .tmark's data-mark instead, prefixed with
// html to outrank room.css (docs/cycles/138-marks-in-language.md).
// - choice.css holds exactly the choice layout rules, then the wrong-line
//   rules, then the mark rule; comments and whitespace do not matter, a
//   trailing semicolon before } is optional, and "@media(" / "@container(" count the same as
//   "@media (" / "@container (". Quoted strings (the grid-template-areas
//   values and the data-mood attribute values) are compared literally,
//   quotes included.
// - main.jsx imports ./choice.css after ./start.css.
// Runs in the node project: it reads the files through import.meta.url.

const CHOICE_CSS = fileURLToPath(new URL('./choice.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED =
  '@media (min-width:1300px){.moodbar{flex-basis:auto}}' +
  '@media (max-height:900px){.choice-mode .now .sub{display:none}}' +
  '.choice-mode{--oh:calc(var(--hd,67px) + 175px)}' +
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
  '.choice-mode .now .hint{font-size:1.15rem}}' +
  '.msg.wrong{color:#ff6b7a;font-weight:700;' +
  'border-color:color-mix(in srgb,#ff6b7a 50%,transparent);' +
  'background:color-mix(in srgb,#ff6b7a 8%,transparent)}' +
  '[data-mood="chalk"] .msg.wrong{color:#ffb3c1}' +
  '[data-mood="manga"] .msg.wrong{background:#fff;color:#e0262f;border:2px solid #e0262f}' +
  'html .tile.wrong .tmark::before,html .tile.correct .tmark::before{content:attr(data-mark)}'

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

  it('holds exactly the choice layout rules, the wrong-line rules and the mark rule', () => {
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
