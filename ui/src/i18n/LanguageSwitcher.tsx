import Select from '../ui/Select'
import { LANGS, type Lang } from './config'
import { useLanguage } from './LanguageContext'
import { useT } from './useT'

// Top-bar language picker. Each option shows the language in its own script (Deutsch, Français,
// Español, 中文) so a user who can't read the current language can still find theirs. The choice is
// remembered on the user's account (see LanguageContext).
export default function LanguageSwitcher(): JSX.Element {
  const { lang, setLang } = useLanguage()
  const t = useT('common')
  return (
    <label className="lang-switcher">
      <span className="lang-switcher-label">{t('language', 'Language')}</span>
      <Select
        ariaLabel="Language"
        value={lang}
        onChange={(v) => setLang(v as Lang)}
        options={LANGS.map((l) => ({ value: l.code, label: l.native }))}
      />
    </label>
  )
}
