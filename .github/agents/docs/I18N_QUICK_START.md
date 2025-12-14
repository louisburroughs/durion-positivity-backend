# Quick Start: Multi-Language Development Guide

## For Developers: Using the i18n Agent Ecosystem

This guide helps you quickly leverage the internationalization (i18n) agent ecosystem to build multi-language features in Durion ERP.

## 5-Minute Overview

**What's New:**
- `i18n-agent` - Handles translation structure, Vue-i18n config, and locale formatting
- `typescript-agent` - Ensures type-safe translations and component typing
- `quasar-agent`, `vue-agent` - Build modern, type-safe UI components
- `en-US-language-agent`, `es-ES-language-agent`, `fr-FR-language-agent` - Native speaker validation

**Why This Matters:**
- Multi-language from day one
- Type-safe translation keys (catch typos at compile time)
- Professional translations reviewed by native speakers
- Consistent formatting across languages (dates, currency, etc.)

## Workflow: Create a Multi-Language Component in 3 Steps

### Step 1: Get Initial Approval (5 minutes)
```
@architecture_agent Approve building [feature name]:
- Description of what you're building
- Which languages: typically en-US, es-ES, fr-FR
- Which Durion component it belongs to

Example:
@architecture_agent Approve building a customer search interface:
- Real-time search with filters
- Support English (US), Spanish (Spain), French
- Part of durion-crm component
```

**Expected Response**: ✅ Approved or 🔄 Needs clarification

---

### Step 2: Design & Implement (30 minutes)
```
@quasar_agent Design the layout for [feature]

@vue_agent Implement [feature] component with:
- [State management requirements]
- [API integration needs]
- [User interactions]

@typescript_agent Define types for [feature]:
- Data model
- API contracts
- Component props/events
- Translation message keys

@i18n_agent Set up translations for [feature]:
- All text strings (labels, placeholders, messages)
- Date/number/currency formatting
- Error messages
```

**What You Do**: Add `$t()` calls in your Vue component:
```vue
<template>
  <div>
    <h1>{{ $t('feature.title') }}</h1>
    <q-input :placeholder="$t('feature.searchPlaceholder')" />
    <q-btn :label="$t('common.save')" />
  </div>
</template>

<script>
export default {
  methods: {
    showSuccess() {
      this.$q.notify({
        message: this.$t('feature.savedSuccessfully')
      })
    }
  }
}
</script>
```

---

### Step 3: Get Translation Reviews (20 minutes)
```
@en-US-language-agent Please review the English translations
@es-ES-language-agent Por favor, revisa las traducciones al español
@fr-FR-language-agent Veuillez vérifier les traductions en français
```

**What Language Agents Check:**
- ✅ Grammar and spelling
- ✅ Business terminology correctness
- ✅ Tone and formality
- ✅ Date/number/currency formatting
- ✅ Cultural appropriateness

**Expected Response**: ✅ Approved or 📝 Feedback with corrections

---

## Common Patterns

### Pattern 1: Simple Label Translation
```vue
<q-btn :label="$t('common.save')" />

<!-- In locale files -->
"common": {
  "save": "Save",           // en-US
  "save": "Guardar",        // es-ES
  "save": "Enregistrer"     // fr-FR
}
```

### Pattern 2: Dynamic Text with Parameters
```vue
<p>{{ $t('validation.minLength', { min: 8 }) }}</p>

<!-- In locale files -->
"validation": {
  "minLength": "Must be at least {min} characters"
}
```

### Pattern 3: Formatted Numbers and Dates
```vue
<p>Price: {{ $n(product.price, 'currency') }}</p>
<p>Date: {{ $d(product.createdAt, 'long') }}</p>

<!-- Automatically formats based on selected language -->
```

### Pattern 4: Language Selector
```vue
<q-select
  :value="$i18n.locale"
  @input="changeLanguage"
  :options="[
    { label: 'English', value: 'en-US' },
    { label: 'Español', value: 'es-ES' },
    { label: 'Français', value: 'fr-FR' }
  ]"
/>

<script>
methods: {
  changeLanguage(locale) {
    this.$i18n.locale = locale
    localStorage.setItem('userLanguage', locale)
  }
}
</script>
```

## Translation File Structure

**Create these files:**

```
src/i18n/locale/
├── en-US.json
├── es-ES.json
└── fr-FR.json
```

**English Template (en-US.json):**
```json
{
  "common": {
    "save": "Save",
    "cancel": "Cancel",
    "delete": "Delete",
    "yes": "Yes",
    "no": "No"
  },
  "feature_name": {
    "title": "Feature Title",
    "description": "Feature description",
    "label": "Label text"
  },
  "validation": {
    "required": "This field is required",
    "email": "Invalid email address",
    "minLength": "Must be at least {min} characters"
  }
}
```

## Translation Approval Checklist

Before asking agents to review, verify:

- [ ] All user-visible text is in translation files
- [ ] No hardcoded English strings in components
- [ ] Translation keys are consistent (naming convention)
- [ ] Dates use locale-specific formatting
- [ ] Numbers/currency use locale-specific formatting
- [ ] Error messages are professional and helpful
- [ ] Placeholders and tooltips are translated
- [ ] Accessibility text (alt, aria-label) is translated

## Common Mistakes to Avoid

❌ **WRONG**: Hardcoded English in template
```vue
<q-btn label="Save" />
```

✅ **RIGHT**: Use translation key
```vue
<q-btn :label="$t('common.save')" />
```

---

❌ **WRONG**: Missing error message translation
```vue
if (!valid) {
  alert('Please fill in all fields')  // ❌ Not translated!
}
```

✅ **RIGHT**: Use translation
```vue
if (!valid) {
  this.$q.notify({
    message: this.$t('validation.allFieldsRequired')
  })
}
```

---

❌ **WRONG**: Hardcoded date formatting
```vue
<p>{{ new Date().toLocaleDateString() }}</p>  // Uses browser locale
```

✅ **RIGHT**: Use i18n formatting
```vue
<p>{{ $d(new Date(), 'short') }}</p>  <!-- Uses configured locale -->
```

---

❌ **WRONG**: Translation key inconsistency
```json
{
  "product.name": "Product Name",
  "product_sku": "SKU",           // ⚠️ Inconsistent naming!
  "productPrice": "Price"         // ⚠️ Inconsistent naming!
}
```

✅ **RIGHT**: Consistent naming convention
```json
{
  "product.name": "Product Name",
  "product.sku": "SKU",
  "product.price": "Price"
}
```

## Agent Request Templates

### Template 1: Set Up i18n for New Feature
```
@i18n_agent Set up translations for [feature name]:

Strings to translate:
- "Save", "Cancel", "Delete" (common buttons)
- "[Feature] Title", "[Feature] Description" (headers)
- "[Field] label", "[Field] placeholder" (form fields)
- "[Error message 1]", "[Error message 2]" (validation)

Supported languages: en-US, es-ES, fr-FR

Create translation structure and configuration.
```

### Template 2: Request Language Review
```
@en-US-language-agent Please review English translations for [feature]:

Translation file: src/i18n/locale/en-US.json
Section: "[feature_name]"

Focus on:
- Business terminology appropriateness
- Tone and formality
- Consistency with other strings
```

### Template 3: Get Translation Advice
```
@i18n_agent How should I structure translations for:
[Describe your feature and what text needs translating]

Specific questions:
- Should I use [term A] or [term B]?
- How to handle [special case]?
```

## Troubleshooting

### 🔴 "Translation key not found" Error

**Problem**: `$t('feature.title')` shows blank or key name
**Solution**:
1. Check key exists in locale file: `src/i18n/locale/en-US.json`
2. Verify spelling matches exactly (case-sensitive!)
3. Restart dev server to reload translations

---

### 🔴 Wrong Language After Changing Selection

**Problem**: Changed language dropdown but page didn't update
**Solution**:
```javascript
// Don't just change the value, update i18n and storage
methods: {
  changeLanguage(locale) {
    this.$i18n.locale = locale
    localStorage.setItem('userLanguage', locale)
    // Consider full page reload for consistent formatting
    // window.location.reload()
  }
}
```

---

### 🔴 Missing Translations in New Language

**Problem**: Added Spanish translations but some keys missing
**Solution**:
1. Use i18n_agent to validate completeness
2. Ensure every key in en-US.json exists in other locales
3. Use automated validation:
```javascript
const validateTranslations = (messages) => {
  const baseKeys = Object.keys(messages['en-US'])
  Object.entries(messages).forEach(([locale, trans]) => {
    baseKeys.forEach(key => {
      if (!(key in trans)) {
        console.warn(`Missing: ${key} in ${locale}`)
      }
    })
  })
}
```

---

### 🔴 Date/Number Format Wrong for Locale

**Problem**: Numbers show as `1,234.56` in Spanish (should be `1.234,56`)
**Solution**: Configure in Vue-i18n:
```javascript
const i18n = new VueI18n({
  numberFormats: {
    'en-US': { decimal: { minimumFractionDigits: 2 } },
    'es-ES': { decimal: { minimumFractionDigits: 2 } },
    'fr-FR': { decimal: { minimumFractionDigits: 2 } }
  },
  dateTimeFormats: {
    'en-US': { short: { year: 'numeric', month: 'short', day: 'numeric' } },
    'es-ES': { short: { year: 'numeric', month: '2-digit', day: '2-digit' } }
  }
})
```

## Next Steps

1. **Choose your first feature** to make multi-language
2. **Get architecture approval** using the template above
3. **Design UI** with `quasar-agent` and `vue_agent`
4. **Create translation files** with `i18n_agent` guidance
5. **Add $t() calls** to your Vue components
6. **Request language reviews** from language agents
7. **Incorporate feedback** and deploy

## Resources

- **i18n-agent**: Comprehensive i18n expertise and Vue-i18n config
- **typescript-agent**: Type-safe translations and component typing
- **quasar-agent**: UI component selection and layout
- **vue-agent**: Component implementation patterns
- **en-US-language-agent**: English review and terminology
- **es-ES-language-agent**: Spanish review and terminology
- **fr-FR-language-agent**: French review and terminology

## Key Files to Create

```
src/i18n/
├── index.js                 # Vue-i18n config
├── locale/
│   ├── en-US.json          # English strings
│   ├── es-ES.json          # Spanish strings
│   └── fr-FR.json          # French strings
└── types/
    └── messages.ts         # TypeScript types for translations
```

---

**Questions?** Ask the agents directly in your prompts:
- "@i18n_agent How do I handle pluralization in translations?"
- "@en-US-language-agent Should I use 'email' or 'e-mail'?"
- "@quasar_agent How do I make the language selector responsive?"
