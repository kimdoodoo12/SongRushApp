import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { redirectPhone } from './mobileRedirect.js'
import './index.css'
import './room.css'
import './wide.css'
import './start.css'
import './choice.css'
import './scroll.css'

// A phone goes to the mobile build at /m/ before anything renders (D-140).
if (!redirectPhone(window)) {
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
