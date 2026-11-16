import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Cycle 117 addendum (D-125): the start screen has no chat, so its .main
// drops the chat column and the card is centred
// (docs/cycles/117-wide-center.md, "Addendum: the start screen").
// - start.css holds exactly the rules from the spec; comments and
//   whitespace do not matter, and a trailing semicolon before } is optional.
// - main.jsx imports ./start.css after ./wide.css.
// Runs in the node project: it reads the files through import.meta.url.

const START_CSS = fileURLToPath(new URL('./start.css', import.meta.url))
const MAIN_JSX = fileURLToPath(new URL('./main.jsx', import.meta.url))

// Cycle 139 (D-137) adds the host's 곡 수 number input, styled as room.css's
// .stepper output with no spin buttons.
const EXPECTED =
  '.main.solo{grid-template-columns:minmax(0,1fr)}.main.solo .inner{margin-inline:auto}.start-screen{margin-inline:auto}' +
  '.stepper input{width:3.2ch;min-width:3.2ch;padding:0;border:0;background:none;color:inherit;text-align:center;' +
  'font-family:var(--f-display);font-size:1.6rem;font-variant-numeric:tabular-nums;appearance:textfield;-moz-appearance:textfield}' +
  '.stepper input::-webkit-outer-spin-button,.stepper input::-webkit-inner-spin-button{-webkit-appearance:none;margin:0}'

function read(path) {
  return fs.readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

// Drops comments and whitespace that carries no meaning: runs collapse to one
// space, spaces around { } : ; , ( ) go, and a ; right before } goes. The
// descendant combinator space (.main.solo .inner) stays.
function normalize(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/\s*([{}:;,])\s*/g, '$1')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/;}/g, '}')
    .trim()
}

describe('start.css', () => {
  it('exists', () => {
    expect(fs.existsSync(START_CSS), 'frontend/src/start.css').toBe(true)
  })

  it('holds exactly the rules from the spec', () => {
    expect(normalize(read(START_CSS))).toBe(normalize(EXPECTED))
  })
})

describe('load order', () => {
  it('main.jsx imports ./start.css after ./wide.css', () => {
    const main = read(MAIN_JSX)
    const wideAt = main.search(/import\s+['"]\.\/wide\.css['"]/)
    const startAt = main.search(/import\s+['"]\.\/start\.css['"]/)
    expect(wideAt, "import './wide.css' in main.jsx").not.toBe(-1)
    expect(startAt, "import './start.css' in main.jsx").not.toBe(-1)
    expect(startAt > wideAt, './start.css imported after ./wide.css').toBe(true)
  })
})
