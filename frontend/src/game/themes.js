// The room looks, the eight moods (D-122, cycle 109), in picker order. Each
// id has a token set under [data-room-theme="<id>"] in index.css; swatch is
// the picker dot, a CSS background. The names are in the dictionaries under
// theme.<id>.
export const THEMES = [
  { id: 'sunset', swatch: 'linear-gradient(135deg, #ff8a4c, #ff5d8f)' },
  { id: 'rain', swatch: 'linear-gradient(135deg, #8cc8ff, #a9b8ff)' },
  { id: 'sakura', swatch: 'linear-gradient(135deg, #ff9ec4, #9fe8d4)' },
  { id: 'neon', swatch: 'linear-gradient(135deg, #ff3fd2, #33f3ff)' },
  { id: 'chalk', swatch: 'linear-gradient(135deg, #2d5146, #ffe27a)' },
  { id: 'pixel', swatch: 'linear-gradient(135deg, #ff5c8a, #ffd23f)' },
  { id: 'matsuri', swatch: 'linear-gradient(135deg, #ff5a4e, #ffc94d)' },
  { id: 'manga', swatch: 'linear-gradient(135deg, #fff, #111)' },
]

export const DEFAULT_THEME = 'sunset'

// The ids before cycle 109, from a stored pick or an old server; mapped as
// the server maps them.
const OLD_THEMES = new Map([
  ['play', 'sunset'],
  ['star', 'rain'],
  ['arcade', 'pixel'],
])

// The own pick for the start screen.
const STORAGE_KEY = 'songrush.roomTheme'

// A known theme id, an old id mapped to its mood, or sunset.
export function themeId(value) {
  if (OLD_THEMES.has(value)) return OLD_THEMES.get(value)
  return THEMES.some((t) => t.id === value) ? value : DEFAULT_THEME
}

// The stored own pick; sunset when storage is empty, unknown or blocked.
export function loadOwnTheme() {
  try {
    return themeId(window.localStorage.getItem(STORAGE_KEY))
  } catch (err) {
    console.warn('theme storage read failed', err)
    return DEFAULT_THEME
  }
}

// Stores the own pick; a blocked storage keeps it for this page only.
export function saveOwnTheme(id) {
  try {
    window.localStorage.setItem(STORAGE_KEY, id)
  } catch (err) {
    console.warn('theme storage write failed', err)
  }
}

// Name colours for avatars and chat markers, picked from the name so a
// player keeps one colour everywhere.
const NAME_COLOURS = ['#ff9fb3', '#8fdcf5', '#a6e6b0', '#ffd08a', '#c9b6ff', '#ffc2a1', '#9ee6dc', '#f5b3e6']

export function nameColour(name) {
  let hash = 0
  for (let i = 0; i < name.length; i += 1) hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  return NAME_COLOURS[hash % NAME_COLOURS.length]
}
