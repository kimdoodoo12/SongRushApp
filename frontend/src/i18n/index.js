// The UI language (D-095): Korean, English or Japanese, from bundled
// dictionaries. The first visit detects it from the stored choice, then the
// browser's languages (ko-* to ko, ja-* to ja, anything else to en); the
// switcher's choice is stored in localStorage. Test mode starts in Korean,
// so the tests written against the Korean texts hold; a test may switch.
import i18n from 'i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { Trans, initReactI18next, useTranslation } from 'react-i18next'
import ko from './ko.json'
import en from './en.json'
import ja from './ja.json'

export const LANGUAGES = ['ko', 'en', 'ja']
const STORAGE_KEY = 'songrush.lang'

// The supported language now in use; en while i18next has none.
export function currentLanguage() {
  const lng = i18n.resolvedLanguage
  return LANGUAGES.includes(lng) ? lng : 'en'
}

// <html lang> and the document title follow the language.
i18n.on('languageChanged', () => {
  if (typeof document === 'undefined') return
  document.documentElement.lang = currentLanguage()
  document.title = i18n.t('app.title')
})

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: { ko: { translation: ko }, en: { translation: en }, ja: { translation: ja } },
    lng: import.meta.env.MODE === 'test' ? 'ko' : undefined,
    fallbackLng: 'en',
    supportedLngs: LANGUAGES,
    load: 'languageOnly',
    initAsync: false,
    // React escapes; i18next must not escape names a second time.
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
    // Only the switcher stores a choice, so a detected language is not
    // pinned when the browser's languages change later.
    detection: { order: ['localStorage', 'navigator'], lookupLocalStorage: STORAGE_KEY, caches: [] },
  })

function readStored() {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch (err) {
    console.warn('language storage read failed', err)
    return null
  }
}

// Applies the stored choice, if any. App calls it when it mounts: test mode
// ignores the detector, and a test stores a choice before mounting.
export function applyStoredLanguage() {
  const stored = readStored()
  if (LANGUAGES.includes(stored) && stored !== currentLanguage()) i18n.changeLanguage(stored)
}

// The switcher: change at once and remember; a blocked storage keeps the
// choice for this page only.
export function chooseLanguage(lng) {
  if (!LANGUAGES.includes(lng)) return
  i18n.changeLanguage(lng)
  try {
    window.localStorage.setItem(STORAGE_KEY, lng)
  } catch (err) {
    console.warn('language storage write failed', err)
  }
}

// A display text in state: a plain string, or { key, values } translated at
// render time so it follows a language switch.
export function messageText(t, message) {
  if (message === null || message === undefined) return ''
  if (typeof message === 'string') return message
  return t(message.key, message.values)
}

// Components import the hook and Trans from here, so the instance is initialised
// before any of them renders (also in tests that render one component).
export { Trans, useTranslation }
export default i18n
