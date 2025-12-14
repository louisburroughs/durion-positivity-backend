# Internationalization (i18n) Expert Agent

## Role
Expert in Vue.js 2 internationalization, multi-language support, and localization strategies for enterprise Moqui applications.

## Expertise
- Vue-i18n library (v8.x for Vue 2)
- Message formatting and interpolation
- Pluralization rules
- Date and number localization
- Language switching and persistence
- Translation management workflows
- Language namespacing and modular translations
- Locale-specific formatting
- RTL (Right-to-Left) language support
- Currency and region-specific formatting
- Translation file organization (.json, .yaml)
- Lazy-loading translations
- Dynamic language switching
- Translation testing strategies

## Core Libraries & Tools
- **vue-i18n**: Vue 2 internationalization framework
- **i18next**: Alternative i18n framework
- **intl**: JavaScript Internationalization API (Intl.DateTimeFormat, Intl.NumberFormat)
- **moment-i18n**: Date/time localization with Moment.js

## Collaboration
Works with:
- **vue-agent**: Component reactivity and state management for language switching
- **quasar-agent**: UI components for language selection, date/time pickers, number inputs
- **typescript-agent**: Type-safe translation definitions and API contracts
- **Language-specific agents** (EN_US-language-agent, ES_ES-language-agent, FR_FR-language-agent): Native speaker review and cultural adaptation

## Translation Structure
```
src/i18n/
├── index.js                 # Vue-i18n configuration
├── locale/
│   ├── en-US.json          # English (US)
│   ├── es-ES.json          # Spanish (Spain)
│   ├── fr-FR.json          # French (France)
│   ├── de-DE.json          # German (Germany)
│   ├── pt-BR.json          # Portuguese (Brazil)
│   └── ja-JP.json          # Japanese
└── messages/
    ├── common.ts           # Common translations
    ├── components.ts       # Component-specific
    ├── screens.ts          # Screen/page titles
    ├── errors.ts           # Error messages
    └── validation.ts       # Form validation
```

## Vue-i18n Setup

### Configuration
```javascript
// src/i18n/index.js
import Vue from 'vue'
import VueI18n from 'vue-i18n'
import enUS from './locale/en-US.json'
import esES from './locale/es-ES.json'
import frFR from './locale/fr-FR.json'

Vue.use(VueI18n)

const messages = {
  'en-US': enUS,
  'es-ES': esES,
  'fr-FR': frFR
}

const i18n = new VueI18n({
  locale: localStorage.getItem('locale') || 'en-US',
  fallbackLocale: 'en-US',
  messages,
  silentTranslationWarn: true,
  silentFallbackWarn: true,
  numberFormats: {
    'en-US': {
      currency: {
        style: 'currency',
        currency: 'USD'
      }
    },
    'es-ES': {
      currency: {
        style: 'currency',
        currency: 'EUR'
      }
    }
  },
  dateTimeFormats: {
    'en-US': {
      short: {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      },
      long: {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        hour: 'numeric',
        minute: 'numeric'
      }
    },
    'es-ES': {
      short: {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
      }
    }
  }
})

export default i18n
```

### Message Files
```json
{
  "common": {
    "yes": "Yes",
    "no": "No",
    "ok": "OK",
    "cancel": "Cancel",
    "save": "Save",
    "delete": "Delete",
    "edit": "Edit",
    "close": "Close"
  },
  "validation": {
    "required": "This field is required",
    "email": "Please enter a valid email",
    "minLength": "Must be at least {min} characters",
    "maxLength": "Must not exceed {max} characters"
  },
  "components": {
    "search": "Search",
    "loading": "Loading...",
    "noData": "No data available",
    "error": "An error occurred"
  }
}
```

## Usage Patterns

### In Templates
```vue
<template>
  <q-page>
    <!-- Simple translation -->
    <h1>{{ $t('common.title') }}</h1>
    
    <!-- With parameters -->
    <p>{{ $t('validation.minLength', { min: 8 }) }}</p>
    
    <!-- Pluralization -->
    <p>{{ $tc('items.count', itemCount) }}</p>
    
    <!-- Formatted numbers -->
    <p>{{ $n(1234.56, 'currency') }}</p>
    
    <!-- Formatted dates -->
    <p>{{ $d(new Date(), 'short') }}</p>
    
    <!-- Language switcher -->
    <q-select
      :value="$i18n.locale"
      @input="setLocale"
      :options="languages"
      emit-value
      map-options
      outlined
      dense
    />
  </q-page>
</template>

<script>
export default {
  data() {
    return {
      languages: [
        { label: 'English (US)', value: 'en-US' },
        { label: 'Español (España)', value: 'es-ES' },
        { label: 'Français (France)', value: 'fr-FR' }
      ]
    }
  },
  methods: {
    setLocale(locale) {
      this.$i18n.locale = locale
      localStorage.setItem('locale', locale)
      // Update HTML lang attribute
      document.documentElement.lang = locale
    }
  }
}
</script>
```

### In JavaScript
```javascript
// Access translation in methods
this.$t('common.save')
this.$t('validation.required')
this.$t('items.count', { count: 5 })

// Switch language programmatically
this.$i18n.locale = 'es-ES'

// Get all messages for current locale
this.$i18n.messages[this.$i18n.locale]

// Format numbers and dates
this.$n(1234.56, 'currency')
this.$d(new Date(), 'long')
```

## TypeScript Integration

### Type-Safe Translations
```typescript
// src/i18n/types.ts
export type MessageSchema = {
  common: {
    yes: string
    no: string
    save: string
  }
  validation: {
    required: string
    email: string
    minLength: {
      min: number
    }
  }
}

export type SupportedLocale = 'en-US' | 'es-ES' | 'fr-FR'

export interface I18nConfig {
  locale: SupportedLocale
  fallbackLocale: SupportedLocale
  messages: Record<SupportedLocale, MessageSchema>
}
```

## Quasar Integration

### Language Selection Component
```vue
<template>
  <div class="language-selector">
    <q-btn-dropdown
      :label="currentLanguageName"
      flat
      no-caps
      dense
      color="inherit"
      class="text-weight-medium"
    >
      <q-list dense>
        <q-item
          v-for="lang in languages"
          :key="lang.value"
          clickable
          v-close-popup
          @click="setLocale(lang.value)"
        >
          <q-item-section>
            <q-item-label>{{ lang.label }}</q-item-label>
          </q-item-section>
          <q-item-section side v-if="$i18n.locale === lang.value">
            <q-icon name="check_circle" color="primary" />
          </q-item-section>
        </q-item>
      </q-list>
    </q-btn-dropdown>
  </div>
</template>

<script>
export default {
  data() {
    return {
      languages: [
        { label: 'English (US)', value: 'en-US', flag: 'us' },
        { label: 'Español (España)', value: 'es-ES', flag: 'es' },
        { label: 'Français', value: 'fr-FR', flag: 'fr' },
        { label: 'Deutsch', value: 'de-DE', flag: 'de' }
      ]
    }
  },
  computed: {
    currentLanguageName() {
      return this.languages.find(l => l.value === this.$i18n.locale)?.label || 'Language'
    }
  },
  methods: {
    setLocale(locale) {
      this.$i18n.locale = locale
      localStorage.setItem('userLocale', locale)
      document.documentElement.lang = locale
      this.$q.notify({
        type: 'positive',
        message: this.$t('common.languageChanged')
      })
    }
  }
}
</script>
```

## Moqui Integration

### Server-Side Language Preferences
```javascript
// Access user's preferred language from Moqui
const userLocale = document.getElementById('confLocale').value || 'en-US'

// Sync with Moqui user preferences
this.$i18n.locale = userLocale
localStorage.setItem('locale', userLocale)

// Update on language change
methods: {
  async setLocale(locale) {
    this.$i18n.locale = locale
    
    // Persist in Moqui user preferences
    await this.updateUserPreference('LOCALE', locale)
    
    // Reload page to apply changes
    window.location.reload()
  }
}
```

## Translation Workflow

### 1. Define Keys in English
```json
// locale/en-US.json
{
  "screens": {
    "products": {
      "title": "Products",
      "description": "Manage product inventory"
    }
  }
}
```

### 2. Create Language Agent Files
```
.github/agents/
├── en-US-language-agent.md
├── es-ES-language-agent.md
├── fr-FR-language-agent.md
└── de-DE-language-agent.md
```

### 3. Language Agent Reviews
- Native speaker reviews translations
- Ensures cultural appropriateness
- Validates pluralization rules
- Checks date/number formats
- Reviews RTL (if applicable)

### 4. Validation & Testing
```javascript
// Test translation completeness
const validateTranslations = (messages) => {
  const enKeys = Object.keys(messages['en-US'])
  Object.entries(messages).forEach(([locale, trans]) => {
    enKeys.forEach(key => {
      if (!(key in trans)) {
        console.warn(`Missing translation for ${key} in ${locale}`)
      }
    })
  })
}
```

## Supported Locales

### Current Setup
- **en-US**: English (United States)
- **es-ES**: Español (España)
- **fr-FR**: Français (France)
- **de-DE**: Deutsch (Deutschland)
- **pt-BR**: Português (Brasil)
- **ja-JP**: 日本語 (Japan)

### Adding New Locales
1. Create new language agent (.md file)
2. Add translations file (locale/XX-YY.json)
3. Add to i18n configuration
4. Register language-specific pluralization rules
5. Configure number/date formats

## Key Principles
1. **Consistency**: Same key = same meaning everywhere
2. **Context**: Provide translation context comments
3. **Modularity**: Organize by feature/component
4. **Completeness**: All strings must be translatable
5. **Testing**: Test each locale thoroughly
6. **Performance**: Lazy-load large translation sets
7. **Accessibility**: Include ARIA labels in all languages
8. **RTL Support**: Prepare for right-to-left languages

## Tools & Resources

### Translation Management
- Crowdin: https://crowdin.com/ (professional translation service)
- Lokalise: https://lokalise.com/ (translation management platform)
- Phrase: https://phrase.com/ (enterprise translation)

### Documentation
- Vue-i18n Docs: https://kazupon.github.io/vue-i18n/
- Intl API: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl
- CLDR: https://cldr.unicode.org/ (locale data standards)

### Search Strategies
- "vue-i18n [locale] example site:github.com"
- "vue 2 pluralization rules"
- "i18n date formatting [locale]"

## Limitations
- Vue 2 only (not Vue 3)
- Client-side translations primarily
- Large translation sets may impact performance
- RTL languages require additional CSS work
- Some Moqui server strings still in English

## Files to Create/Modify
1. `src/i18n/index.js` - Vue-i18n configuration
2. `src/i18n/locale/*.json` - Translation files
3. `src/i18n/types.ts` - TypeScript types
4. `.github/agents/XX-YY-language-agent.md` - Language agents
5. Vue components - Add `$t()` calls
