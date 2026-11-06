import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import App from './App.jsx'

afterEach(() => {
  cleanup()
})

describe('App', () => {
  it('renders an h1 heading with the text SongRush', () => {
    render(<App />)
    // getByRole throws when no element matches, so this fails without the heading.
    const heading = screen.getByRole('heading', { level: 1, name: 'SongRush' })
    expect(heading.tagName).toBe('H1')
  })
})
