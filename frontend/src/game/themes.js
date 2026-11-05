// The room looks (F-012, D-090, D-091), in picker order. Each id has a token
// set under [data-room-theme="<id>"] in index.css; swatch is the picker dot.
export const THEMES = [
  { id: 'play', name: '놀이터', swatch: '#ffe7a8' },
  { id: 'neon', name: '무대', swatch: '#ff3df2' },
  { id: 'sakura', name: '벚꽃', swatch: '#ffd1e0' },
  { id: 'chalk', name: '칠판', swatch: '#24453a' },
  { id: 'star', name: '별밤', swatch: '#0d1033' },
  { id: 'arcade', name: '오락실', swatch: '#ffe14d' },
]

export const DEFAULT_THEME = 'play'

// The own pick for the start screen and solo games.
const STORAGE_KEY = 'songrush.roomTheme'

// A known theme id, or play.
export function themeId(value) {
  return THEMES.some((t) => t.id === value) ? value : DEFAULT_THEME
}

// The stored own pick; play when storage is empty, unknown or blocked.
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
