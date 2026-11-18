// Cycle 154 (D-140): phones get the Flutter mobile web build at /m/. The
// decision runs in main.jsx before React renders; the footer's "mobile
// version" link and the mobile build's "PC version" link store the viewer's
// choice under UI_KEY, and a stored "pc" keeps a phone on this site.
// Cycle 155: a `ui=pc` or `ui=mobile` query (the mobile build's fallback
// link and PC button use `/?ui=pc`) is stored and decides, so the escape
// works even when localStorage is blocked.

export const UI_KEY = 'songrush.ui'

// The short screen side, in CSS px, up to which a coarse-pointer device is a
// phone. Tablets (768 px and up) and desktops stay.
export const PHONE_MAX_SIDE = 600

// The /m/ path matching a React path: / goes to /m/, /r/<code> to
// /m/r/<code>. Any other path (/admin among them) has none: null.
export function mobilePath(pathname) {
  if (pathname === '/') return '/m/'
  const match = /^\/r\/([^/]+)\/?$/.exec(pathname)
  return match === null ? null : `/m/r/${match[1]}`
}

// A phone: a coarse primary pointer and a short screen side of at most
// PHONE_MAX_SIDE px.
export function isPhone(win) {
  if (typeof win.matchMedia !== 'function' || win.screen === undefined) return false
  if (!win.matchMedia('(pointer: coarse)').matches) return false
  return Math.min(win.screen.width, win.screen.height) <= PHONE_MAX_SIDE
}

// The stored choice ('pc', 'mobile'), or null when there is none or the
// storage is blocked (then the device decides).
export function storedUi(win) {
  try {
    return win.localStorage.getItem(UI_KEY)
  } catch (err) {
    console.warn('localStorage read failed', err)
    return null
  }
}

// A blocked storage only loses the choice: the link still navigates.
export function storeUi(win, value) {
  try {
    win.localStorage.setItem(UI_KEY, value)
  } catch (err) {
    console.warn('localStorage write failed', err)
  }
}

// The choice in the URL query's `ui` ('pc' or 'mobile'), or null for none
// or any other value.
export function queryUi(win) {
  const value = new URLSearchParams(win.location.search).get('ui')
  return value === 'pc' || value === 'mobile' ? value : null
}

// The query without its `ui` parameter ('' when nothing is left); unchanged
// when it has none.
function searchWithoutUi(search) {
  const params = new URLSearchParams(search)
  if (!params.has('ui')) return search
  params.delete('ui')
  const rest = params.toString()
  return rest === '' ? '' : `?${rest}`
}

// Where a viewer goes before React renders: the /m/ URL (with the query, less
// any `ui`, and the hash kept) for / or /r/<code>, when the query asks for
// the mobile version, or for a phone that did not choose the PC version (in
// the query or stored); null to stay.
export function redirectTarget(win) {
  const target = mobilePath(win.location.pathname)
  if (target === null) return null
  const asked = queryUi(win)
  if (asked === 'pc') return null
  if (asked !== 'mobile') {
    if (storedUi(win) === 'pc') return null
    if (!isPhone(win)) return null
  }
  return target + searchWithoutUi(win.location.search) + win.location.hash
}

// Stores a `ui` query's choice, then sends a phone to /m/ (replacing this
// history entry, so Back does not bounce it here again). Returns true when it
// navigated, so the caller skips the render.
export function redirectPhone(win) {
  const asked = queryUi(win)
  if (asked !== null) storeUi(win, asked)
  const target = redirectTarget(win)
  if (target === null) return false
  win.location.replace(target)
  return true
}
