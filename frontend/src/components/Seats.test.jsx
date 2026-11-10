// Cycle 070 (F-011): the room seats. Eight seats in roster order, the
// 정답! mark, the score as N점 and the disconnected marker.
// Cycle 110 (D-124, declared): the mockup's markup (ol.seat-grid named 자리,
// li.seat with .av and .sm, .seat.empty, .win and .ok). Chat no longer shows
// in seat bubbles: the bubble tests are replaced by 'no chat in the seats'
// here and by the chat log test in App.layout.test.jsx and App.room.test.jsx.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import Seats from './Seats.jsx'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
// 민수 hosts; the own player is 철수.
const PLAYERS_3 = [player('민수', { host: true, score: 1 }), player('철수', { score: 2 }), player('영희')]
const ME = '철수'

const line = (id, name, body) => ({ id, name, body, text: `${name}: ${body}` })

function props(overrides = {}) {
  return {
    players: PLAYERS_3,
    me: ME,
    chat: [],
    scoreboard: null,
    correct: null,
    roundIndex: 0,
    ...overrides,
  }
}

function seats() {
  return within(screen.getByRole('list', { name: '자리' })).getAllByRole('listitem')
}

function seatOf(name) {
  const seat = seats().find((li) => li.textContent.includes(name))
  expect(seat, `a seat for ${name}`).toBeTruthy()
  return seat
}

function seatText(name) {
  return seatOf(name).textContent
}

describe('seat count and order', () => {
  it('3 roster players give 3 filled seats in roster order and 5 빈 자리 slots', () => {
    render(<Seats {...props()} />)
    const all = seats()
    expect(all).toHaveLength(8)
    expect(all[0].textContent).toContain('민수')
    expect(all[1].textContent).toContain('철수')
    expect(all[2].textContent).toContain('영희')
    for (const seat of all.slice(0, 3)) expect(seat.textContent).not.toContain('빈 자리')
    for (const seat of all.slice(3)) expect(seat.textContent).toContain('빈 자리')
  })

  it('the host and own marks are in the name text', () => {
    render(<Seats {...props()} />)
    const all = seats()
    expect(all[0].textContent).toContain('민수 (방장)')
    expect(all[1].textContent).toContain('철수 (나)')
    expect(all[2].textContent).not.toContain('(방장)')
    expect(all[2].textContent).not.toContain('(나)')
  })

  it('with me null no seat carries (나)', () => {
    render(<Seats {...props({ me: null })} />)
    for (const seat of seats()) expect(seat.textContent).not.toContain('(나)')
  })
})

describe('no chat in the seats (110)', () => {
  it('chat lines never show in a seat, before or after a round change', () => {
    const chat = [line(0, '민수', '첫째말'), line(1, '철수', '둘째말')]
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat })} />)
    for (const seat of seats()) {
      expect(seat.textContent).not.toContain('첫째말')
      expect(seat.textContent).not.toContain('둘째말')
    }
    rerender(<Seats {...props({ chat: [...chat, line(2, '영희', '셋째말')], roundIndex: 1 })} />)
    for (const seat of seats()) expect(seat.textContent).not.toContain('셋째말')
  })
})

describe('mockup markup (110)', () => {
  it('the list is ol.seat-grid with eight li.seat', () => {
    render(<Seats {...props()} />)
    const list = screen.getByRole('list', { name: '자리' })
    expect(list.tagName).toBe('OL')
    expect(list.classList.contains('seat-grid')).toBe(true)
    const all = seats()
    expect(all).toHaveLength(8)
    for (const li of all) {
      expect(li.tagName).toBe('LI')
      expect(li.classList.contains('seat')).toBe(true)
    }
  })

  it('a filled seat has .av with the initial and .sm with b (name and tags) and small (points)', () => {
    render(<Seats {...props()} />)
    const seat = seatOf('민수')
    const av = seat.querySelector('.av')
    expect(av).not.toBeNull()
    expect(av.textContent).toContain('민')
    expect(seat.querySelector('.sm b').textContent).toContain('민수 (방장)')
    expect(seat.querySelector('.sm small').textContent).toContain('1점')
    expect(seatOf('철수').querySelector('.sm b').textContent).toContain('철수 (나)')
  })

  it('empty seats are .seat.empty reading 빈 자리', () => {
    render(<Seats {...props()} />)
    const empty = seats().slice(3)
    expect(empty).toHaveLength(5)
    for (const li of empty) {
      expect(li.classList.contains('empty')).toBe(true)
      expect(li.textContent).toBe('빈 자리')
    }
    for (const li of seats().slice(0, 3)) expect(li.classList.contains('empty')).toBe(false)
  })

  it("the winner's seat is .win with .ok 정답!; a list of names marks each", () => {
    const { rerender } = render(<Seats {...props({ correct: '민수' })} />)
    expect(seatOf('민수').classList.contains('win')).toBe(true)
    expect(seatOf('민수').querySelector('.sm .ok').textContent).toBe('정답!')
    expect(seatOf('철수').classList.contains('win')).toBe(false)
    expect(seatOf('철수').querySelector('.ok')).toBeNull()
    rerender(<Seats {...props({ correct: ['민수', '영희'] })} />)
    expect(seats().filter((li) => li.classList.contains('win')).map((li) => li.querySelector('.sm b').textContent)).toEqual([
      '민수 (방장)',
      '영희',
    ])
  })
})

describe('correct mark', () => {
  it("correct: '민수' marks only 민수's seat with 정답!", () => {
    render(<Seats {...props({ correct: '민수' })} />)
    expect(seatText('민수')).toContain('정답!')
    const others = seats().filter((li) => !li.textContent.includes('민수'))
    expect(others).toHaveLength(7)
    for (const seat of others) expect(seat.textContent).not.toContain('정답!')
  })

  it('correct null marks no seat', () => {
    render(<Seats {...props()} />)
    for (const seat of seats()) expect(seat.textContent).not.toContain('정답!')
  })
})

describe('scores', () => {
  it('a scoreboard score is shown as N점 over the roster score', () => {
    const scoreboard = [
      { name: '민수', score: 4 },
      { name: '철수', score: 5 },
      { name: '영희', score: 6 },
    ]
    render(<Seats {...props({ scoreboard })} />)
    expect(seatText('민수')).toContain('4점')
    expect(seatText('민수')).not.toContain('1점')
    expect(seatText('철수')).toContain('5점')
    expect(seatText('철수')).not.toContain('2점')
    expect(seatText('영희')).toContain('6점')
    for (const seat of seats()) expect(seat.textContent).not.toContain('점수:')
  })
})

describe('disconnected', () => {
  it('a disconnected player\'s seat carries data-connected="false"', () => {
    const players = [player('민수', { host: true }), player('철수'), player('영희', { connected: false })]
    render(<Seats {...props({ players })} />)
    expect(seatOf('영희').getAttribute('data-connected')).toBe('false')
    expect(seatOf('민수').getAttribute('data-connected')).not.toBe('false')
    expect(seatOf('철수').getAttribute('data-connected')).not.toBe('false')
  })
})
