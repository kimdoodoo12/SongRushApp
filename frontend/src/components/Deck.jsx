import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from '../i18n/index.js'
import { drawPlayer } from './drawPlayer.js'
import { canvasDpr, sizeViz } from '../canvas/index.js'

// The mockup's deck (D-124): #viz, the player (.disc with its canvas, and
// the .mark), the tone arm and the .cover frame. Cycle 111 ports the mockup's
// drawPlayer (the disc, redrawn on a mood change), showCover and pixelate
// (the frame), and the deck classes of startRound, reveal and finish. #viz is
// drawn by App's frame loop (cycle 115); the deck sizes it, as the mockup's
// sizeViz does on a new game and on resize. The CSS spins the disc only under body.playing (set by
// the game screen).

// The frame's size with no image (the mockup's showCover).
const NO_IMAGE_AR = '0.714'
const NO_IMAGE_PEEK = '39%'
// The pixelate pass: the widths of its steps, one every STEP_MS.
const PIXEL_STEPS = [5, 8, 12, 18, 28, 44, 70]
const STEP_MS = 130

// The mockup's reduce. A missing matchMedia (jsdom) counts as motion allowed.
function reducedMotion() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function nextFrame(callback) {
  if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(callback)
  else setTimeout(callback, 16)
}

// The mockup's flyIn (cycle 112): .cover flies from the correct choice
// tile's frame to its own box. Nothing without Web Animations (jsdom) or
// when either box has no width (the mockup's guard).
function flyIn(tile, cov) {
  if (typeof cov.animate !== 'function') return
  nextFrame(() => {
    const a = tile.getBoundingClientRect()
    const b = cov.getBoundingClientRect()
    if (!a.width || !b.width) return
    const base = getComputedStyle(cov).transform
    // jsdom reports '' where a browser reports 'none'.
    const end = base === 'none' || base === '' ? 'translate(0,0) scale(1)' : base
    const dx = a.left + a.width / 2 - (b.left + b.width / 2)
    const dy = a.top + a.height / 2 - (b.top + b.height / 2)
    const sc = a.width / b.width
    cov.animate(
      [
        { transform: `translate(${dx}px,${dy}px) scale(${sc})`, opacity: 0.6 },
        { transform: end, opacity: 1 },
      ],
      { duration: 750, easing: 'cubic-bezier(.2,.9,.3,1.1)' },
    )
  })
}

// The mockup's play(): restarts .revealed, so its animation runs again, and
// in a choice game flies the cover in from the correct tile (the mockup's
// flyTile, .tile.correct .tframe), unless motion is reduced.
function play(deck, cover) {
  deck.classList.remove('revealed')
  void deck.offsetWidth
  deck.classList.add('revealed')
  const tile = document.querySelector('.tile.correct .tframe')
  if (tile !== null && !reducedMotion()) flyIn(tile, cover)
}

// The mockup's img.onload: --ar from the image's own proportions, clamped,
// and the deck's --peek from it.
function fit(deck, cover, img) {
  const ar = Math.min(0.8, Math.max(0.56, img.naturalWidth / img.naturalHeight))
  cover.style.setProperty('--ar', ar.toFixed(3))
  deck.style.setProperty('--peek', `${Math.round(18 + ar * 30)}%`)
}

// The mockup's pixelate(): canvas.pix over the shot, from coarse to fine,
// then hidden. jobs.current names the running pass; bumping it stops one.
// Skipped under reduced motion; without a 2d context (jsdom) it draws nothing.
function pixelate(img, cv, shot, jobs) {
  if (reducedMotion()) {
    cv.hidden = true
    return
  }
  jobs.current += 1
  const job = jobs.current
  nextFrame(() => {
    if (job !== jobs.current) return
    const b = shot.getBoundingClientRect()
    if (!b.width) return
    cv.width = Math.round(b.width)
    cv.height = Math.round(b.height)
    const c = cv.getContext('2d')
    const t = document.createElement('canvas')
    const tc = t.getContext('2d')
    if (!c || !tc) return
    cv.hidden = false
    let k = 0
    const st = () => {
      if (job !== jobs.current) return
      if (k >= PIXEL_STEPS.length) {
        cv.hidden = true
        return
      }
      const w = PIXEL_STEPS[k]
      const h = Math.max(1, Math.round((w * cv.height) / cv.width))
      t.width = w
      t.height = h
      tc.drawImage(img, 0, 0, w, h)
      c.imageSmoothingEnabled = false
      c.clearRect(0, 0, cv.width, cv.height)
      c.drawImage(t, 0, 0, cv.width, cv.height)
      k += 1
      setTimeout(st, STEP_MS)
    }
    st()
  })
}

// The .cap per mood (the mockup's showCover; no rarity badge, D-123).
function Caption({ mood, title, outcome, number }) {
  if (mood === 'sakura') return <span>{title}</span>
  if (mood === 'pixel') {
    if (outcome === 'me') return 'PLAYER 1 CLEAR!'
    return outcome === 'other' ? 'STAGE CLEAR!' : 'TIME OVER'
  }
  return mood === 'neon' ? `SIGNAL FOUND · CH-0${number}` : title
}

// idle: no song is current (before the first song, after a game).
// revealed: the reveal is showing; reveal is the reveal frame whose cover
// fills the frame (null: the frame is empty). title is its Korean title,
// outcome 'me', 'other' or 'none' (who solved it), number the song number.
export default function Deck({ idle, revealed, reveal, title, outcome, number, missed, mood }) {
  const { t } = useTranslation()
  const deckRef = useRef(null)
  const coverRef = useRef(null)
  const shotRef = useRef(null)
  const imgRef = useRef(null)
  const pixRef = useRef(null)
  const discRef = useRef(null)
  const vizRef = useRef(null)
  const pixJobs = useRef(0)
  // The reveal whose image failed: it then counts as no image.
  const [failedFor, setFailedFor] = useState(null)
  const src = reveal !== null && reveal.coverUrl !== null && failedFor !== reveal ? reveal.coverUrl : null

  useEffect(() => {
    drawPlayer(discRef.current, mood)
  }, [mood])

  useLayoutEffect(() => {
    const viz = vizRef.current
    const size = () => sizeViz(viz, canvasDpr())
    size()
    window.addEventListener('resize', size)
    return () => window.removeEventListener('resize', size)
  }, [])

  useEffect(() => {
    const jobs = pixJobs
    return () => {
      jobs.current += 1
    }
  }, [])

  useLayoutEffect(() => {
    deckRef.current.classList.toggle('idle', idle)
  }, [idle])

  // The mockup's showCover, on each reveal and on a mood change during one
  // (setMood also stops a running pixelate). Off the reveal, .revealed goes.
  useLayoutEffect(() => {
    const deck = deckRef.current
    const cover = coverRef.current
    const img = imgRef.current
    pixJobs.current += 1
    pixRef.current.hidden = true
    if (!revealed) {
      deck.classList.remove('revealed')
      return
    }
    if (src === null) {
      cover.style.setProperty('--ar', NO_IMAGE_AR)
      deck.style.setProperty('--peek', NO_IMAGE_PEEK)
      play(deck, cover)
      return
    }
    if (img.complete && img.naturalWidth) {
      fit(deck, cover, img)
      play(deck, cover)
      if (mood === 'pixel') pixelate(img, pixRef.current, shotRef.current, pixJobs)
    }
  }, [revealed, reveal, src, mood])

  function handleLoad() {
    const img = imgRef.current
    if (!revealed || src === null || !img.naturalWidth) return
    fit(deckRef.current, coverRef.current, img)
    play(deckRef.current, coverRef.current)
    if (mood === 'pixel') pixelate(img, pixRef.current, shotRef.current, pixJobs)
  }

  const shown = reveal !== null
  return (
    <div ref={deckRef} className="deck">
      <canvas ref={vizRef} id="viz" aria-hidden="true" />
      <div className="player">
        <div className="disc">
          <canvas ref={discRef} width="520" height="520" aria-hidden="true" />
        </div>
        <span className="mark" aria-hidden="true">
          ?
        </span>
      </div>
      <div className="arm" aria-hidden="true" />
      <figure ref={coverRef} className="cover" style={{ margin: 0 }}>
        <div ref={shotRef} className={shown && src === null ? 'shot missing' : 'shot'}>
          <img
            ref={imgRef}
            src={src ?? undefined}
            alt={shown ? t('reveal.coverAlt', { title }) : t('reveal.coverAltIdle')}
            onLoad={handleLoad}
            onError={() => setFailedFor(reveal)}
          />
          <div className="noimg">{shown ? title : ''}</div>
          <div className="fog" />
          <canvas ref={pixRef} className="pix" hidden />
        </div>
        <figcaption className="cap">
          {shown && <Caption mood={mood} title={title} outcome={outcome} number={number} />}
        </figcaption>
        <span className="stamp" aria-hidden="true">
          {missed ? t('reveal.stampPass') : t('reveal.stampHit')}
        </span>
        <span className="sfx" aria-hidden="true">
          {t('reveal.sfx')}
        </span>
      </figure>
    </div>
  )
}
