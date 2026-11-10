import { useTranslation } from '../i18n/index.js'

// The mockup's deck (D-124): #viz, the player (.disc with its canvas, and
// the .mark), the tone arm and the .cover frame. Cycle 110 places the tree
// only: the disc canvas is drawn by the drawPlayer port and the cover is
// filled by the showCover port in cycle 111, and #viz is drawn in cycle 115.
// Until then the cover frame stays empty and the deck is never .revealed.
// The CSS spins the disc only under body.playing (set by the game screen).
// idle: no song is current (before the first song, between games).
export default function Deck({ idle, missed }) {
  const { t } = useTranslation()
  return (
    <div className={idle ? 'deck idle' : 'deck'}>
      <canvas id="viz" aria-hidden="true" />
      <div className="player">
        <div className="disc">
          <canvas width="520" height="520" aria-hidden="true" />
        </div>
        <span className="mark" aria-hidden="true">
          ?
        </span>
      </div>
      <div className="arm" aria-hidden="true" />
      <figure className="cover" style={{ margin: 0 }}>
        <div className="shot">
          <div className="noimg" />
          <div className="fog" />
          <canvas className="pix" hidden />
        </div>
        <figcaption className="cap" />
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
