import { useCallback } from 'react'
import { lookup } from './dictionaries'
import { useLanguage } from './LanguageContext'

/**
 * Translation hook, scoped to a namespace (usually one screen / feature area).
 *
 *   const t = useT('inbound')
 *   <h1>{t('title', 'Inbound orders')}</h1>
 *
 * The second argument is the English source text AND the fallback: when the active language is
 * English, or a translation for the key is missing, the English is returned verbatim. So adding
 * t(...) around a string never changes the English UI, and a key with no translation yet degrades
 * gracefully to English rather than showing a raw key.
 */
export function useT(ns: string): (key: string, english: string) => string {
  const { lang } = useLanguage()
  return useCallback(
    (key: string, english: string): string =>
      lang === 'en' ? english : lookup(lang, ns, key) ?? english,
    [lang, ns],
  )
}
