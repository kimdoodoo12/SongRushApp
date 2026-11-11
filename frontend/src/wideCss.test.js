import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 117 (D-125): above 1440 px the whole .app is at most 1440 px wide and
// centred (docs/cycles/117-wide-center.md, Change and Acceptance 1).
// - wide.css holds exactly the one rule from the spec; comments and
//   whitespace do not matter, and a trailing semicolon before } is optional.
// - main.jsx imports ./wide.css after ./room.css, so it wins over room.css.
// Runs in the node project: it reads the files through import.meta.url.

const WIDE_CSS = fileURLToPath(new URL('./wide.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

const EXPECTED = '@media (min-width:1441px){.app{max-width:1440px;margin-inline:auto;border-inline:1px solid var(--line)}}'

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Drops comments and whitespace that carries no meaning: runs collapse to one
// space, spaces around { } : ; , ( ) go, and a ; right before } goes. The
// space after a media-query "and" or between value tokens stays.
function normalize(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/\s*([{}:;,])\s*/g, '$1')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/;}/g, '}')
    .replace(/^@media\(/, '@media (')
    .trim()
}

describe('wide.css', () => {
  it('exists', () => {
    expect(fs.existsSync(WIDE_CSS), 'frontend/src/wide.css').toBe(true)
  })

  it('holds exactly the one rule from the spec', () => {
    expect(normalize(read(WIDE_CSS))).toBe(normalize(EXPECTED))
  })
})

describe('load order', () => {
  it('main.jsx imports ./wide.css after ./room.css', () => {
    const main = read(MAIN_JSX)
    const roomAt = main.search(/import\s+['"]\.\/room\.css['"]/)
    const wideAt = main.search(/import\s+['"]\.\/wide\.css['"]/)
    expect(roomAt, "import './room.css' in main.jsx").not.toBe(-1)
    expect(wideAt, "import './wide.css' in main.jsx").not.toBe(-1)
    expect(wideAt > roomAt, './wide.css imported after ./room.css').toBe(true)
  })
})
