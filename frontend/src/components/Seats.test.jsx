// Cycle 070 (F-011): the room seats. Eight seats in roster order, the
// newest chat body of each player as a speech bubble (40 characters plus
// the ellipsis, gone after 6 s or when the round index changes), the 정답!
// mark, the score as N점 and the disconnected marker.
// Timer rule: fake timers for the bubble timeout tests, advanced in act().
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, act, cleanup, within } from '@testing-library/react'
import Seats from './Seats.jsx'

const ELLIPSIS = String.fromCharCode(0x2026)

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

describe('speech bubble', () => {
  it("each seat shows only its own player's newest body, without the name prefix", () => {
    const { rerender } = render(<Seats {...props()} />)
    const chat = [line(0, '민수', '첫째말'), line(1, '철수', '둘째말'), line(2, '민수', '셋째말')]
    rerender(<Seats {...props({ chat })} />)
    expect(seatText('민수')).toContain('셋째말')
    expect(seatText('민수')).not.toContain('첫째말')
    expect(seatText('민수')).not.toContain('둘째말')
    expect(seatText('민수')).not.toContain('민수:')
    expect(seatText('철수')).toContain('둘째말')
    expect(seatText('철수')).not.toContain('셋째말')
    expect(seatText('철수')).not.toContain('철수:')
    expect(seatText('영희')).not.toContain('말')
  })

  it('a 45-character body shows its first 40 characters plus the ellipsis', () => {
    const body = '가'.repeat(40) + '나'.repeat(5)
    expect(body).toHaveLength(45)
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat: [line(0, '민수', body)] })} />)
    const text = seatText('민수')
    expect(text).toContain('가'.repeat(40) + ELLIPSIS)
    expect(text).not.toContain('가'.repeat(41))
    expect(text).not.toContain('나')
  })

  it('a 40-character body is shown whole, without the ellipsis', () => {
    const body = '다'.repeat(40)
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat: [line(0, '민수', body)] })} />)
    expect(seatText('민수')).toContain(body)
    expect(seatText('민수')).not.toContain(ELLIPSIS)
  })
})

describe('bubble timeout', () => {
  it('a bubble is gone 6 s after it appeared', () => {
    vi.useFakeTimers()
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat: [line(0, '민수', '안녕')] })} />)
    expect(seatText('민수')).toContain('안녕')
    act(() => {
      vi.advanceTimersByTime(5900)
    })
    expect(seatText('민수')).toContain('안녕')
    act(() => {
      vi.advanceTimersByTime(100)
    })
    expect(seatText('민수')).not.toContain('안녕')
  })

  it('a newer line from the same player restarts the timer', () => {
    vi.useFakeTimers()
    const first = line(0, '민수', '처음')
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat: [first] })} />)
    act(() => {
      vi.advanceTimersByTime(4000)
    })
    rerender(<Seats {...props({ chat: [first, line(1, '민수', '다음')] })} />)
    expect(seatText('민수')).toContain('다음')
    // 9 s from the start, 5 s after the newer line: still shown.
    act(() => {
      vi.advanceTimersByTime(5000)
    })
    expect(seatText('민수')).toContain('다음')
    expect(seatText('민수')).not.toContain('처음')
    // 10.5 s from the start, 6.5 s after the newer line: gone.
    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(seatText('민수')).not.toContain('다음')
    expect(seatText('민수')).not.toContain('처음')
  })

  it("one player's timer does not hide another player's newer bubble", () => {
    vi.useFakeTimers()
    const first = line(0, '민수', '처음')
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat: [first] })} />)
    act(() => {
      vi.advanceTimersByTime(4000)
    })
    rerender(<Seats {...props({ chat: [first, line(1, '영희', '반가워')] })} />)
    act(() => {
      vi.advanceTimersByTime(2500)
    })
    expect(seatText('민수')).not.toContain('처음')
    expect(seatText('영희')).toContain('반가워')
  })
})

describe('bubble cleared on prepare', () => {
  it('a change of the round index clears every bubble; old lines are not shown again', () => {
    const chat = [line(0, '민수', '하나'), line(1, '철수', '둘')]
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat })} />)
    expect(seatText('민수')).toContain('하나')
    expect(seatText('철수')).toContain('둘')
    rerender(<Seats {...props({ chat, roundIndex: 1 })} />)
    expect(seatText('민수')).not.toContain('하나')
    expect(seatText('철수')).not.toContain('둘')
    // A line after the change shows; the old ones stay hidden.
    rerender(<Seats {...props({ chat: [...chat, line(2, '영희', '셋')], roundIndex: 1 })} />)
    expect(seatText('영희')).toContain('셋')
    expect(seatText('민수')).not.toContain('하나')
    expect(seatText('철수')).not.toContain('둘')
  })

  it('a rerender with the same round index keeps the bubbles', () => {
    const chat = [line(0, '민수', '하나')]
    const { rerender } = render(<Seats {...props()} />)
    rerender(<Seats {...props({ chat })} />)
    rerender(<Seats {...props({ chat, correct: '철수' })} />)
    expect(seatText('민수')).toContain('하나')
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
