import { useState } from 'react'
import { useTranslation } from '../i18n/index.js'

// One choice's cover in its .tframe, decorative (the tile's name is 보기 N),
// so alt is empty. With no URL, or after the image fails, the frame stays
// empty (cycle 098). The caller keys this component by URL, so a failure is
// forgotten when the cover changes.
function ChoiceCover({ url }) {
  const [failed, setFailed] = useState(false)
  return (
    <span className="tframe">
      {url !== null && !failed && <img src={url} alt="" draggable={false} onError={() => setFailed(true)} />}
    </span>
  )
}

// The tile's mark and whether it is disabled, as the mockup's renderChoices.
// The first matching case applies: the reveal, then the own pick (wrong or
// awaiting its pick-result), then picking not allowed.
function tileState(position, { revealed, picked, pickWrong, correctChoice, enabled }) {
  if (revealed) {
    if (position === correctChoice) return { mark: 'correct', disabled: true }
    return { mark: position === picked ? 'wrong' : 'dim', disabled: true }
  }
  if (picked !== null) {
    if (position !== picked) return { mark: 'locked', disabled: true }
    return { mark: pickWrong ? 'wrong' : null, disabled: true }
  }
  return { mark: null, disabled: !enabled }
}

// The text of a tile's .tmark in the screen's language (cycle 138): room.css
// hard-codes the Korean marks, so choice.css shows data-mark instead.
// Empty for a tile with no correct or wrong mark.
function markText(t, mark) {
  if (mark === 'correct') return t('answer.markCorrect')
  if (mark === 'wrong') return t('answer.markWrong')
  return ''
}

// The cover tiles of a choice room's song (cycle 100, D-111) in the mockup's
// markup (cycle 112, D-124): div.choices.k<k>, each button.tile holding
// .tframe, the number badge .tno and the .tmark (its text is data-mark, shown
// by choice.css over room.css's Korean text). Shown from prepare to the next prepare. enabled is the caller's
// rule (audible, not watching, no pick, no answer yet); revealed is the
// reveal; onPick gets the 0-based position. Before the reveal nothing marks
// the correct tile (A9).
export default function ChoiceGrid({ choices, revealed, picked, pickWrong, correctChoice, enabled, onPick }) {
  const { t } = useTranslation()
  const marks = { revealed, picked, pickWrong, correctChoice, enabled }
  return (
    <div className={`choices k${choices.length}`}>
      {choices.map((url, i) => {
        const { mark, disabled } = tileState(i, marks)
        return (
          <button
            key={i}
            type="button"
            className={mark === null ? 'tile' : `tile ${mark}`}
            aria-label={t('answer.choiceN', { n: i + 1 })}
            disabled={disabled}
            onClick={() => onPick(i)}
          >
            <ChoiceCover key={url ?? 'none'} url={url} />
            <span className="tno">{i + 1}</span>
            <span className="tmark" data-mark={markText(t, mark)} />
          </button>
        )
      })}
    </div>
  )
}
