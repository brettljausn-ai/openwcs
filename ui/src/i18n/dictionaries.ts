import type { Lang } from './config'

// Translation registry. Every file at ./locales/<lang>/<namespace>.ts that default-exports a
// `{ key: 'translated text' }` map is auto-discovered here via Vite's import.meta.glob — so a
// translator (or an agent) can add a new namespace file for any language WITHOUT editing a central
// registry, which keeps parallel work conflict-free. English needs no files (it is the inline
// fallback in every t() call).

type NamespaceDict = Record<string, string>
type LangDict = Record<string, NamespaceDict> // namespace -> key -> text

const modules = import.meta.glob('./locales/*/*.ts', { eager: true }) as Record<
  string,
  { default: NamespaceDict }
>

const byLang: Partial<Record<Lang, LangDict>> = {}
for (const path in modules) {
  const m = path.match(/\.\/locales\/([a-z]{2})\/(.+)\.ts$/)
  if (!m) continue
  const lang = m[1] as Lang
  const ns = m[2]
  const dict = (byLang[lang] ??= {})
  dict[ns] = modules[path].default
}

/** The translation for (lang, namespace, key), or undefined when it is missing (caller falls back
 *  to the inline English). */
export function lookup(lang: Lang, ns: string, key: string): string | undefined {
  return byLang[lang]?.[ns]?.[key]
}
