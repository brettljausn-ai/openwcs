// Frontend internationalisation (operator/management SPA only — server logs, the login screen, and
// all backend output stay English). English is the source language, written inline in the code as
// the fallback of every t(key, english) call; German / French / Spanish / Chinese live in the
// translation files under ./locales/<lang>/<namespace>.ts and are looked up by key at render time.
// A user's chosen language is remembered on their IAM account (AppUser.language); default is English.

export type Lang = 'en' | 'de' | 'fr' | 'es' | 'zh'

export const DEFAULT_LANG: Lang = 'en'

/** The selectable languages, with their English name and their own-language label for the switcher. */
export const LANGS: ReadonlyArray<{ code: Lang; english: string; native: string }> = [
  { code: 'en', english: 'English', native: 'English' },
  { code: 'de', english: 'German', native: 'Deutsch' },
  { code: 'fr', english: 'French', native: 'Français' },
  { code: 'es', english: 'Spanish', native: 'Español' },
  { code: 'zh', english: 'Chinese', native: '中文' },
]

export function isLang(s: string | null | undefined): s is Lang {
  return !!s && LANGS.some((l) => l.code === s)
}

/** Where the active language is cached client-side (the IAM account is the source of truth). */
export const LANG_STORAGE_KEY = 'owcs.lang'
