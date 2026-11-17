// Cycle 154 (D-140): the phone redirect's decision. A phone is a coarse
// pointer with a short screen side of at most 600 px; it goes from / to /m/
// and from /r/<code> to /m/r/<code>, unless localStorage songrush.ui is "pc".
// /admin and other paths never move; desktops and tablets stay.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { isPhone, mobilePath, redirectPhone, redirectTarget, storeUi, UI_KEY } from './mobileRedirect.js'

// A fake window: `coarse` pointer, a `width` x `height` screen, the path, and
// a Map-backed localStorage (or one that throws when `blocked`).
function fakeWindow({
  coarse = true,
  width = 390,
  height = 844,
  pathname = '/',
  search = '',
  hash = '',
  ui = null,
  blocked = false,
} = {}) {
  const store = new Map(ui === null ? [] : [[UI_KEY, ui]])
  const storage = blocked
    ? {
        getItem() {
          throw new Error('blocked')
        },
        setItem() {
          throw new Error('blocked')
        },
      }
    : {
        getItem: (k) => (store.has(k) ? store.get(k) : null),
        setItem: (k, v) => store.set(k, String(v)),
      }
  return {
    matchMedia: (query) => ({ matches: query === '(pointer: coarse)' ? coarse : false }),
    screen: { width, height },
    location: { pathname, search, hash, replace: vi.fn() },
    localStorage: storage,
    store,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('mobilePath', () => {
  it('maps / and /r/<code> (with or without the slash) to the /m/ paths', () => {
    expect(mobilePath('/')).toBe('/m/')
    expect(mobilePath('/r/ABCDE')).toBe('/m/r/ABCDE')
    expect(mobilePath('/r/ABCDE/')).toBe('/m/r/ABCDE')
  })

  it('has no match for /admin, /m/ or anything else', () => {
    for (const path of ['/admin', '/admin/', '/m/', '/m/r/ABCDE', '/r/', '/r/A/B', '/nope']) {
      expect(mobilePath(path)).toBe(null)
    }
  })
})

describe('isPhone', () => {
  it('a coarse pointer with a short side of at most 600 px, in either orientation', () => {
    expect(isPhone(fakeWindow({ width: 390, height: 844 }))).toBe(true)
    expect(isPhone(fakeWindow({ width: 844, height: 390 }))).toBe(true)
    expect(isPhone(fakeWindow({ width: 600, height: 960 }))).toBe(true)
  })

  it('not a tablet (short side over 600 px) nor a fine pointer', () => {
    expect(isPhone(fakeWindow({ width: 768, height: 1024 }))).toBe(false)
    expect(isPhone(fakeWindow({ width: 601, height: 960 }))).toBe(false)
    expect(isPhone(fakeWindow({ coarse: false, width: 390, height: 844 }))).toBe(false)
    expect(isPhone(fakeWindow({ coarse: false, width: 1920, height: 1080 }))).toBe(false)
  })

  it('not without matchMedia', () => {
    const win = fakeWindow()
    delete win.matchMedia
    expect(isPhone(win)).toBe(false)
  })
})

describe('redirectTarget', () => {
  it('sends a phone from / and /r/<code> to /m/, keeping the query and hash', () => {
    expect(redirectTarget(fakeWindow({ pathname: '/' }))).toBe('/m/')
    expect(redirectTarget(fakeWindow({ pathname: '/r/ABCDE' }))).toBe('/m/r/ABCDE')
    expect(redirectTarget(fakeWindow({ pathname: '/r/abcde/', search: '?x=1', hash: '#h' }))).toBe(
      '/m/r/abcde?x=1#h',
    )
  })

  it('keeps a phone that chose the PC version', () => {
    expect(redirectTarget(fakeWindow({ ui: 'pc' }))).toBe(null)
    expect(redirectTarget(fakeWindow({ ui: 'mobile' }))).toBe('/m/')
  })

  it('never moves /admin, desktops or tablets', () => {
    expect(redirectTarget(fakeWindow({ pathname: '/admin' }))).toBe(null)
    expect(redirectTarget(fakeWindow({ pathname: '/admin/' }))).toBe(null)
    expect(redirectTarget(fakeWindow({ coarse: false, width: 1920, height: 1080 }))).toBe(null)
    expect(redirectTarget(fakeWindow({ width: 820, height: 1180 }))).toBe(null)
  })

  it('a blocked storage counts as no choice', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(redirectTarget(fakeWindow({ blocked: true }))).toBe('/m/')
  })
})

describe('redirectPhone', () => {
  it('replaces the location and returns true for a phone', () => {
    const win = fakeWindow({ pathname: '/r/QWERT' })
    expect(redirectPhone(win)).toBe(true)
    expect(win.location.replace).toHaveBeenCalledWith('/m/r/QWERT')
  })

  it('does nothing and returns false otherwise', () => {
    const win = fakeWindow({ coarse: false, width: 1920, height: 1080 })
    expect(redirectPhone(win)).toBe(false)
    expect(win.location.replace).not.toHaveBeenCalled()
  })
})

describe('storeUi', () => {
  it('stores the choice, and a blocked storage only warns', () => {
    const win = fakeWindow()
    storeUi(win, 'mobile')
    expect(win.store.get(UI_KEY)).toBe('mobile')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(() => storeUi(fakeWindow({ blocked: true }), 'pc')).not.toThrow()
    expect(warn).toHaveBeenCalled()
  })
})
