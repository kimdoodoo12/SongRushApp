// Shared lobby helpers for the cycle 114 lobby (D-124, the mockup's
// #lobbyView in docs/design/108-ui-design-reference.html): the start button
// is .start-row .go, 곡 수 is the .stepper (- / output / +), 곡 종류, 난이도,
// 정답 형식 and 게임 모드 are .seg groups of toggle buttons, and 연도 is two
// selects in .years. Every test that starts a game or sets a lobby control
// goes through these, so the next markup change touches one file (critic H1
// of 114). Not a test file itself (no .test. in the name).
import { fireEvent, screen, within } from '@testing-library/react'

// The first element matching `selector` outside any [hidden] subtree, or null.
function shown(selector) {
  for (const el of document.querySelectorAll(selector)) {
    if (el.closest('[hidden]') === null) return el
  }
  return null
}

// The host's start button (.start-row .go), or null when there is none.
export function startButton() {
  return shown('.start-row .go')
}

// Clicks the start button; throws when the lobby shows none (a guest, or
// not the lobby).
export function startGame() {
  const button = startButton()
  if (button === null) throw new Error('no start button (.start-row .go) on the page')
  fireEvent.click(button)
}

// The 곡 수 output of the stepper, or null.
export function songCountOutput() {
  return shown('.stepper output')
}

// The 곡 수 the stepper shows, as a number.
export function songCount() {
  const output = songCountOutput()
  if (output === null) throw new Error('no 곡 수 stepper (.stepper output) on the page')
  return Number(output.textContent.trim())
}

// The stepper's [down, up] buttons, in the mockup's order: -, output, +.
export function stepperButtons() {
  const output = songCountOutput()
  if (output === null) throw new Error('no 곡 수 stepper (.stepper output) on the page')
  const buttons = output.closest('.stepper').querySelectorAll('button')
  return [buttons[0], buttons[buttons.length - 1]]
}

// Sets 곡 수 to n with the stepper's - and + buttons, one step per click.
export function setSongCount(n) {
  for (let i = 0; i < 40 && songCount() !== n; i += 1) {
    const [down, up] = stepperButtons()
    const button = songCount() < n ? up : down
    if (button.disabled) break
    fireEvent.click(button)
  }
  const shownCount = songCount()
  if (shownCount !== n) throw new Error(`the stepper stopped at ${shownCount}, not ${n}`)
}

// The .seg group with the accessible name `name` (곡 종류, 난이도, 정답 형식,
// 게임 모드, or their English or Japanese names).
export function segGroup(name) {
  return screen.getByRole('group', { name })
}

// Clicks the button `label` in the group `name`.
export function pickSeg(name, label) {
  fireEvent.click(within(segGroup(name)).getByRole('button', { name: label }))
}

// The labels of the pressed buttons in the group `name`.
export function pressedIn(name) {
  return within(segGroup(name))
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

// 곡 종류: '전체', '오프닝만' or '엔딩만'.
export function setType(label) {
  pickSeg('곡 종류', label)
}

// 난이도: '쉬움', '보통' or '어려움'.
export function setTier(label) {
  pickSeg('난이도', label)
}

// 정답 형식: '채팅' or '객관식'.
export function setAnswerMode(label) {
  pickSeg('정답 형식', label)
}

// 게임 모드: '선착순' or '순위전'.
export function setGameMode(label) {
  pickSeg('게임 모드', label)
}

// 연도 시작: a year as a string, or '' for 처음부터.
export function setYearFrom(value) {
  fireEvent.change(screen.getByLabelText('연도 시작'), { target: { value } })
}

// 연도 끝: a year as a string, or '' for 지금까지.
export function setYearTo(value) {
  fireEvent.change(screen.getByLabelText('연도 끝'), { target: { value } })
}

// Both ends of 연도; an undefined end is left as it is.
export function setYears(from, to) {
  if (from !== undefined) setYearFrom(from)
  if (to !== undefined) setYearTo(to)
}

// The select value of 연도 시작 / 연도 끝.
export function yearFrom() {
  return screen.getByLabelText('연도 시작').value
}

export function yearTo() {
  return screen.getByLabelText('연도 끝').value
}

// The name line (b: the name, (방장), (나)) of every filled seat in the
// .seats section, in seat order: the lobby roster rows of before 114.
export function seatNames() {
  const seats = shown('.seats')
  if (seats === null) return []
  return Array.from(seats.querySelectorAll('li.seat:not(.empty) .sm b')).map((b) => b.textContent)
}

// The room code on the lobby's ticket (.tk-code), or null when no lobby is
// shown.
export function lobbyCode() {
  const code = shown('.ticket .tk-code')
  return code === null ? null : code.textContent
}
