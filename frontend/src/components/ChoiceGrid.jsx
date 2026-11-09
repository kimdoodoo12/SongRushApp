import { useState } from 'react'
import { useTranslation } from '../i18n/index.js'

// One choice's cover, decorative (the tile's name is 보기 N), so alt is
// empty. With no URL, or after the image fails, it is the empty placeholder
// of cycle 098. The caller keys this component by URL, so a failure is
// forgotten when the cover changes.
function ChoiceCover({ url }) {
  const [failed, setFailed] = useState(false)
  if (url === null || failed) return <span className="reveal-cover-empty block h-full w-full" />
  return <img src={url} alt="" draggable={false} className="h-full w-full object-contain" onError={() => setFailed(true)} />
}

// The tile's mark: the reveal's correct tile, then the own pick, sent
// (picked) or known wrong (a pick-result, or a reveal naming another tile).
function markOf(position, picked, pickWrong, correctChoice) {
  if (correctChoice === position) return 'correct'
  if (picked !== position) return undefined
  return pickWrong || correctChoice !== null ? 'wrong' : 'picked'
}

// The cover tiles of a choice room's song (cycle 100, D-111): 4 columns of
// 3:4 tiles at every width and for every count, so every tile has the same
// fixed box and the grid one height from prepare to the next prepare.
// enabled is the caller's rule (audible, not watching, no pick, no answer
// yet); onPick gets the 0-based position.
export default function ChoiceGrid({ choices, picked, pickWrong, correctChoice, enabled, onPick }) {
  const { t } = useTranslation()
  return (
    <div className="choice-grid mx-auto grid w-full max-w-md shrink-0 grid-cols-4 gap-2">
      {choices.map((url, i) => (
        <button
          key={i}
          type="button"
          className="choice-tile aspect-[3/4] w-full min-w-0 overflow-hidden p-0"
          aria-label={t('answer.choiceN', { n: i + 1 })}
          data-choice={markOf(i, picked, pickWrong, correctChoice)}
          disabled={!enabled}
          onClick={() => onPick(i)}
        >
          <ChoiceCover key={url ?? 'none'} url={url} />
        </button>
      ))}
    </div>
  )
}
