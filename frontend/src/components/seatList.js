import { createContext } from 'react'

// True while the seats render inside the lobby screen (set by App around
// LobbyScreen). The lobby is not ported to the mockup until cycle 114, and
// its roster rows must stay the only <li> on that screen, so there the seats
// are the same markup with div elements and list roles instead of ol and li.
export const SeatsAsDivs = createContext(false)
