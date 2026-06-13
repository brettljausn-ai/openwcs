# Frontend i18n

The operator/management SPA is translatable into **English, German, French, Spanish, Chinese**.
English is the source language; backend output, server logs and the **login screen stay English**.
A user's chosen language is remembered on their IAM account (`AppUser.language`, default `en`).

## How to translate a string

1. In the component, get a translator scoped to a **namespace** (one per screen / feature area):

   ```tsx
   import { useT } from '../i18n/useT'

   const t = useT('inbound')           // namespace = 'inbound'
   return <h1>{t('title', 'Inbound orders')}</h1>
   ```

   `t(key, english)` returns the translation for the active language, or the **English fallback**
   when the language is English or the key is missing. So wrapping a string never changes the
   English UI, and a missing translation degrades to English (never a raw key).

   - Keys are short, lowerCamelCase, unique within their namespace (`title`, `markForCounting`).
   - The English text is the second argument AND the source of truth. Keep it identical to what was
     there before.
   - Do NOT wrap: data values, codes (SKU/HU/location), log text, or anything the backend owns.

2. Add the translations. For each of `de`, `fr`, `es`, `zh`, create/extend
   `src/i18n/locales/<lang>/<namespace>.ts`:

   ```ts
   // src/i18n/locales/de/inbound.ts
   export default {
     title: 'Wareneingangsaufträge',
     // ...one entry per key used in the 'inbound' namespace
   }
   ```

   There is **no central registry to edit** — `dictionaries.ts` auto-discovers every
   `locales/*/*.ts` file via `import.meta.glob`, so different namespaces can be worked on in
   parallel without merge conflicts. English needs no files.

## Rules

- One namespace per screen/area; keep keys consistent across the four language files.
- House style: no em dashes in any visible copy (any language).
- Keep numbers, units and identifiers out of the translated text; interpolate them in the component.
- The language switcher lives in the top bar (`LanguageSwitcher`), backed by `LanguageContext`
  (`GET`/`PUT /api/iam/me/language`).
