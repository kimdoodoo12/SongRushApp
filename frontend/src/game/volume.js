// The player's own volume (D-099): { v: 0-100 integer, muted: bool }, kept in
// localStorage on this device. A missing, invalid or blocked value is the
// default; a blocked write changes nothing but the stored copy.
export const VOLUME_KEY = 'songrush.volume'
export const DEFAULT_VOLUME = Object.freeze({ v: 80, muted: false })

function isSetting(value) {
  return (
    value !== null &&
    typeof value === 'object' &&
    Number.isInteger(value.v) &&
    value.v >= 0 &&
    value.v <= 100 &&
    typeof value.muted === 'boolean'
  )
}

// Reads only, so it is safe in a state initializer.
export function loadVolume() {
  let raw
  try {
    raw = window.localStorage.getItem(VOLUME_KEY)
  } catch (err) {
    console.warn('localStorage read failed', err)
    return DEFAULT_VOLUME
  }
  if (raw === null) return DEFAULT_VOLUME
  let value
  try {
    value = JSON.parse(raw)
  } catch (err) {
    console.warn('malformed volume setting', err)
    return DEFAULT_VOLUME
  }
  return isSetting(value) ? { v: value.v, muted: value.muted } : DEFAULT_VOLUME
}

export function saveVolume(setting) {
  try {
    window.localStorage.setItem(VOLUME_KEY, JSON.stringify({ v: setting.v, muted: setting.muted }))
  } catch (err) {
    console.warn('localStorage write failed', err)
  }
}

// A perceptual curve: gain = (v/100)^2; muted is silent whatever v is.
export function gainOf(setting) {
  if (setting.muted) return 0
  const level = setting.v / 100
  return level * level
}

// The slider moved: a value above 0 unmutes; 0 keeps the mute state.
export function withLevel(setting, v) {
  return { v, muted: v > 0 ? false : setting.muted }
}

// The mute button: muting keeps the level, so unmuting restores it; unmuting
// at 0 goes back to the default level.
export function toggleMute(setting) {
  if (!setting.muted) return { v: setting.v, muted: true }
  return { v: setting.v === 0 ? DEFAULT_VOLUME.v : setting.v, muted: false }
}
