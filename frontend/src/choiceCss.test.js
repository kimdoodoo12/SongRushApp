import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 118 (D-126): on stages wider than 620 px the choice-mode deck is
// sized to the screen height, so two rows of cover tiles fit on one screen,
// and it keeps that size at the reveal (docs/cycles/118-choice-deck.md,
// Change and Acceptance 1).
// - choice.css holds exactly the one rule from the spec; comments and
//   whitespace do not matter, a trailing semicolon before } is optional, and
//   "@container(" counts the same as "@container (".
// - main.jsx imports ./choice.css after ./start.css.
// Runs in the node project: it reads the files through import.meta.url.

const CHOICE_CSS = fileURLToPath(new URL('./choice.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED =
  '@container (min-width:621px){.choice-mode .now,.choice-mode .now:has(.revealed){grid-template-columns:clamp(180px,calc(100vh - 620px),420px) minmax(0,1fr)}}'

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Drops comments and whitespace that carries no meaning: runs collapse to one
// space, spaces around { } ; , go, spaces after : and inside ( ) go, and a ;
// right before } goes. A space before : is kept, because ".now :has" and
// ".now:has" are different selectors. The descendant combinator space, the
// spaces around "-" in calc() and the space between value tokens stay.
function normalize(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/\s*([{};,])\s*/g, '$1')
    .replace(/:\s+/g, ':')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/;}/g, '}')
    .trim()
    .replace(/^@container\(/, '@container (')
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
