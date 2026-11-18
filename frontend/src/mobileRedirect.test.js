// Cycle 154 (D-140): the phone redirect's decision. A phone is a coarse
// pointer with a short screen side of at most 600 px; it goes from / to /m/
// and from /r/<code> to /m/r/<code>, unless localStorage songrush.ui is "pc".
// /admin and other paths never move; desktops and tablets stay. Cycle 155: a
// ui=pc query keeps any device here and ui=mobile sends any device on / or
// /r/<code> to /m/; either is stored.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { isPhone, mobilePath, queryUi, redirectPhone, redirectTarget, storeUi, UI_KEY } from './mobileRedirect.js'

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

describe('the ui query', () => {
  it('reads pc or mobile, and nothing else', () => {
    expect(queryUi(fakeWindow({ search: '?ui=pc' }))).toBe('pc')
    expect(queryUi(fakeWindow({ search: '?x=1&ui=mobile' }))).toBe('mobile')
    expect(queryUi(fakeWindow({ search: '?ui=desktop' }))).toBe(null)
    expect(queryUi(fakeWindow({ search: '' }))).toBe(null)
  })

  it('ui=pc keeps a phone here, stores pc, and works with a blocked storage', () => {
    const win = fakeWindow({ pathname: '/r/ABCDE', search: '?ui=pc' })
    expect(redirectPhone(win)).toBe(false)
    expect(win.location.replace).not.toHaveBeenCalled()
    expect(win.store.get(UI_KEY)).toBe('pc')
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const blocked = fakeWindow({ search: '?ui=pc', blocked: true })
    expect(redirectPhone(blocked)).toBe(false)
    expect(blocked.location.replace).not.toHaveBeenCalled()
  })

  it('ui=pc overrides a stored mobile', () => {
    const win = fakeWindow({ search: '?ui=pc', ui: 'mobile' })
    expect(redirectPhone(win)).toBe(false)
    expect(win.store.get(UI_KEY)).toBe('pc')
  })

  it('ui=mobile sends any device to /m/, stores mobile, and drops the ui parameter', () => {
    const desktop = fakeWindow({ coarse: false, width: 1920, height: 1080, search: '?ui=mobile', ui: 'pc' })
    expect(redirectPhone(desktop)).toBe(true)
    expect(desktop.location.replace).toHaveBeenCalledWith('/m/')
    expect(desktop.store.get(UI_KEY)).toBe('mobile')
    const room = fakeWindow({ pathname: '/r/ABCDE', search: '?x=1&ui=mobile', hash: '#h' })
    expect(redirectTarget(room)).toBe('/m/r/ABCDE?x=1#h')
  })

  it('ui=mobile on a path with no /m/ match stores the choice but stays', () => {
    const win = fakeWindow({ pathname: '/admin', search: '?ui=mobile' })
    expect(redirectPhone(win)).toBe(false)
    expect(win.store.get(UI_KEY)).toBe('mobile')
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
