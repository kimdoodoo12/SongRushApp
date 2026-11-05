// The CSS-only decorations behind the page (F-012): falling petals for
// 벚꽃 and twinkling stars for 별밤. 오락실's scanlines are part of its
// background token. Positions are fixed numbers, so every render matches.
const PETALS = Array.from({ length: 14 }, (_, i) => ({
  left: `${(i * 7.3) % 100}%`,
  animationDuration: `${7 + (i % 5) * 1.6}s`,
  animationDelay: `${-i * 0.9}s`,
}))

const STARS = Array.from({ length: 40 }, (_, i) => ({
  left: `${(i * 37) % 100}%`,
  top: `${(i * 53) % 100}%`,
  animationDelay: `${-(i % 7) * 0.45}s`,
}))

export default function ThemeDecor({ theme }) {
  if (theme !== 'sakura' && theme !== 'star') return null
  const items = theme === 'sakura' ? PETALS : STARS
  const className = theme === 'sakura' ? 'petal' : 'star'
  return (
    <div className="deco" aria-hidden="true">
      {items.map((style, i) => (
        <span key={i} className={className} style={style} />
      ))}
    </div>
  )
}
