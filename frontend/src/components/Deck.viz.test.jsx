// Cycle 115, attempt 2 (critic Medium): #viz is sized at the deck's real
// size. App applies body.choice-mode in a layout effect that runs after the
// deck's own layout effects, so the deck must measure #viz after it, and
// again when the choice state changes.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useLayoutEffect } from 'react'
import { render, cleanup } from '@testing-library/react'
import Deck from './Deck.jsx'
import * as canvasModule from '../canvas/index.js'

vi.mock('../canvas/index.js', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, sizeViz: vi.fn() }
})
vi.mock('./drawPlayer.js', () => ({ drawPlayer: vi.fn() }))

afterEach(() => {
  cleanup()
  canvasModule.sizeViz.mockClear()
  document.body.classList.remove('choice-mode')
})

// App's order: the deck inside, body.choice-mode toggled by the parent's
// layout effect.
function Host({ choice, idle = false }) {
  useLayoutEffect(() => {
    document.body.classList.toggle('choice-mode', choice)
  }, [choice])
  return (
    <Deck idle={idle} revealed={false} reveal={null} title={null} outcome="none" number={1} missed={false} mood="sunset" choice={choice} />
  )
}

describe('Deck #viz sizing', () => {
  it('measures #viz after the parent applied choice-mode, and again when the choice state changes', () => {
    const seen = []
    canvasModule.sizeViz.mockImplementation((canvas) => {
      expect(canvas.id).toBe('viz')
      seen.push(document.body.classList.contains('choice-mode'))
    })
    const view = render(<Host choice={false} />)
    expect(seen).toEqual([false])
    view.rerender(<Host choice={true} />)
    expect(seen).toEqual([false, true])
    view.rerender(<Host choice={false} />)
    expect(seen).toEqual([false, true, false])
  })

  it('a choice game measured at mount sees choice-mode already applied', () => {
    const seen = []
    canvasModule.sizeViz.mockImplementation(() => seen.push(document.body.classList.contains('choice-mode')))
    render(<Host choice={true} />)
    expect(seen).toEqual([true])
  })

  it('other rerenders do not re-measure', () => {
    const view = render(<Host choice={false} />)
    const before = canvasModule.sizeViz.mock.calls.length
    view.rerender(<Host choice={false} idle={true} />)
    expect(canvasModule.sizeViz.mock.calls.length).toBe(before)
  })
})
