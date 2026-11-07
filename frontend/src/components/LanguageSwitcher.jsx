import { LANGUAGES, chooseLanguage, currentLanguage, useTranslation } from '../i18n/index.js'

// Each language's name in its own language, so the label never changes.
const NAMES = { ko: '한국어', en: 'English', ja: '日本語' }

// The footer's language switcher (D-095). Fixed boxes, so the room look
// never moves it (cycle 076); the group's name is English in every UI.
export default function LanguageSwitcher() {
  // Re-renders on a language change, so the pressed button follows it.
  useTranslation()
  const current = currentLanguage()
  return (
    <div role="group" aria-label="Language" className="lang-switch flex items-center justify-center gap-2">
      {LANGUAGES.map((lng) => (
        <button
          key={lng}
          type="button"
          lang={lng}
          aria-pressed={lng === current}
          className={`h-7 w-18 truncate px-1 py-0 text-xs leading-4 ${lng === current ? 'go' : ''}`}
          onClick={() => chooseLanguage(lng)}
        >
          {NAMES[lng]}
        </button>
      ))}
    </div>
  )
}
