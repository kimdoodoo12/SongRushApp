import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// frontend/ is the parent of the src folder holding this test.
const ROOT = fileURLToPath(new URL('..', import.meta.url))

const SKIPPED_DIRS = new Set(['node_modules', 'dist'])
const TS_EXTENSIONS = ['.ts', '.tsx', '.mts', '.cts']

// Lists every file under root as a relative path with forward slashes,
// skipping directories named node_modules and dist at any depth.
function walk(root) {
  const files = []
  const visit = (dir, rel) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const entryRel = rel === '' ? entry.name : rel + '/' + entry.name
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRS.has(entry.name)) {
          visit(path.join(dir, entry.name), entryRel)
        }
      } else if (entry.isFile()) {
        files.push(entryRel)
      }
    }
  }
  visit(root, '')
  return files
}

function typeScriptFiles(root) {
  return walk(root).filter((file) => TS_EXTENSIONS.some((ext) => file.endsWith(ext)))
}

describe('no TypeScript in frontend', () => {
  it('has no .ts, .tsx, .mts or .cts file', () => {
    expect(typeScriptFiles(ROOT)).toEqual([])
  })

  it('walks the real frontend root (positive control)', () => {
    const files = walk(ROOT)
    expect(files).toContain('src/App.jsx')
    expect(files).toContain('package.json')
  })

  it('reports a .ts file and skips node_modules (positive control)', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'songrush-no-ts-'))
    try {
      fs.writeFileSync(path.join(tmp, 'a.ts'), 'export {}\n')
      fs.mkdirSync(path.join(tmp, 'node_modules'))
      fs.writeFileSync(path.join(tmp, 'node_modules', 'x.d.ts'), 'export {}\n')
      expect(typeScriptFiles(tmp)).toEqual(['a.ts'])
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  it('package.json has no typescript or @types/* dependency', () => {
    const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'))
    const sections = ['dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies']
    const offending = []
    for (const section of sections) {
      for (const name of Object.keys(pkg[section] ?? {})) {
        if (name === 'typescript' || name.startsWith('@types/')) {
          offending.push(section + ': ' + name)
        }
      }
    }
    expect(offending).toEqual([])
  })
})
