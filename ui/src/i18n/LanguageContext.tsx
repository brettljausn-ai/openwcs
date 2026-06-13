import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { DEFAULT_LANG, isLang, LANG_STORAGE_KEY, type Lang } from './config'

// Holds the active UI language. The IAM account is the source of truth (AppUser.language, read once
// after login from GET /api/iam/me/language and written back on change); localStorage is only a fast
// cache so the chosen language paints immediately on the next load, before the server round-trip.

interface LanguageContextValue {
  lang: Lang
  setLang: (lang: Lang) => void
}

const LanguageContext = createContext<LanguageContextValue>({
  lang: DEFAULT_LANG,
  setLang: () => {},
})

export function LanguageProvider({ children }: { children: ReactNode }): JSX.Element {
  const [lang, setLangState] = useState<Lang>(() => {
    const cached = localStorage.getItem(LANG_STORAGE_KEY)
    return isLang(cached) ? cached : DEFAULT_LANG
  })

  // Pull the saved preference from the account once (overrides the cache when it differs).
  useEffect(() => {
    let cancelled = false
    fetch('/api/iam/me/language')
      .then((r) => (r.ok ? r.json() : null))
      .then((body: { language?: string } | null) => {
        if (cancelled || !body || !isLang(body.language)) return
        setLangState(body.language)
        localStorage.setItem(LANG_STORAGE_KEY, body.language)
      })
      .catch(() => {
        /* keep the cached / default language when the preference can't be read */
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const setLang = (next: Lang): void => {
    setLangState(next)
    localStorage.setItem(LANG_STORAGE_KEY, next)
    // Persist to the account, best-effort: a failed save still changes the language for this session.
    fetch('/api/iam/me/language', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: next }),
    }).catch(() => {})
  }

  return <LanguageContext.Provider value={{ lang, setLang }}>{children}</LanguageContext.Provider>
}

export function useLanguage(): LanguageContextValue {
  return useContext(LanguageContext)
}
