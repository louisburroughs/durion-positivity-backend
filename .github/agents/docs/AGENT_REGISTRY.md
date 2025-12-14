# Agent Registry: Internationalization & UI Development

## New Agents Added

This document provides a quick reference for the new agents added to support multi-language, type-safe UI development in Durion ERP.

---

## 🌍 Core Internationalization Agents

### i18n-agent.md
**Purpose**: Expert in Vue.js 2 internationalization (i18n) with Vue-i18n library

**Responsibilities**:
- Design translation message structure and organization
- Configure Vue-i18n for the application
- Manage locale-specific formatting (dates, numbers, currency)
- Coordinate with language-specific agents
- Implement language switching and persistence
- Validate translation completeness across locales

**Key Expertise**:
- Vue-i18n v8.x for Vue 2
- Message formatting and interpolation with parameters
- Pluralization rules per locale
- Date/time/number/currency localization
- Lazy-loading translations
- Translation file organization (.json, .yaml)
- RTL language support preparation

**When to Use**:
- Setting up i18n for your application
- Organizing translation files and structure
- Configuring date/number/currency formatting per locale
- Managing language switching
- Validating translation completeness

**Example Requests**:
```
@i18n_agent How should I structure translations for the product module?
@i18n_agent Set up Vue-i18n configuration for en-US, es-ES, fr-FR
@i18n_agent Design translation keys for a customer management feature
```

---

## 🔒 Type Safety Agents

### typescript-agent.md
**Purpose**: Expert in TypeScript for type-safe Vue.js 2 applications within Moqui

**Responsibilities**:
- Design type-safe component structures
- Create TypeScript interfaces for data models
- Enforce strict TypeScript configuration
- Provide type definitions for services and APIs
- Review component type annotations
- Ensure Moqui integration types properly defined

**Key Expertise**:
- TypeScript 4.x+ fundamentals
- Vue 2 Class Components and Property Decorators
- Generic types and advanced types
- Type guards and type narrowing
- Type-safe event handling
- Module declarations and augmentation
- Vue 2 prop types and validation

**When to Use**:
- Designing component prop types
- Creating type-safe API service layers
- Defining translation message schemas
- Setting up TypeScript configuration for Vue 2
- Type-checking Vue component architecture

**Example Requests**:
```
@typescript_agent Define TypeScript interfaces for a product form component
@typescript_agent Create type-safe translation definitions for i18n
@typescript_agent How do I type Moqui API responses in Vue components?
```

---

## 🎨 UI Component Agents (Enhanced)

### quasar-agent.md
**Purpose**: Expert in Quasar v1 Framework for enterprise UI components

**Responsibilities**:
- Design component layouts using Quasar components
- Implement responsive design with Quasar's breakpoints
- Apply Michelin Design System styling
- Create form components with validation
- Build data tables and dialogs
- Ensure accessibility and RTL support readiness

**Key Expertise**:
- Quasar v1.22.10 components (QTable, QForm, QDialog, etc.)
- Responsive design with breakpoints (xs, sm, md, lg, xl)
- Dark mode and theming
- Michelin Design System integration
- Form validation patterns
- Data tables and dialogs
- Accessibility considerations

**When to Use**:
- Designing UI layouts for components
- Selecting appropriate Quasar components
- Building responsive, professional interfaces
- Creating data tables with pagination/filtering
- Building modal dialogs and forms

**Example Requests**:
```
@quasar_agent Design a customer listing page with search and filters
@quasar_agent Build a responsive product form for mobile/tablet/desktop
@quasar_agent How do I create a data table with pagination in Quasar?
```

---

### vue-agent.md
**Purpose**: Expert in Vue.js 2 component development and state management

**Responsibilities**:
- Design component architecture and composition
- Implement Vue components with proper reactivity
- Manage component state and watchers
- Handle data fetching and API integration
- Implement event handling and validation
- Ensure performance optimization

**Key Expertise**:
- Vue 2.7.14 Options API
- Component lifecycle and reactivity
- Props, events, slots, and mixins
- Computed properties and watchers
- Data fetching patterns with error handling
- Form handling and validation
- State management approaches
- Moqui REST API integration

**When to Use**:
- Implementing Vue component logic
- Managing component state
- Handling API integration
- Building form components
- Creating reusable component patterns

**Example Requests**:
```
@vue_agent Implement a customer search component with filtering
@vue_agent How do I handle API errors in Vue components?
@vue_agent Design state management for a multi-step form wizard
```

---

## 🌐 Language-Specific Agents

Each language agent provides native speaker review and cultural adaptation for their specific language.

### en-US-language-agent.md
**Purpose**: American English localization expert

**Responsibilities**:
- Review translations for accuracy and native fluency
- Ensure cultural appropriateness for US audience
- Maintain consistent business terminology
- Validate US-standard formatting (MM/DD/YYYY, $1,234.56)
- Ensure accessibility in English

**Key Focus**:
- American English grammar and spelling
- US business conventions
- Date formatting (MM/DD/YYYY)
- Currency formatting ($1,234.56)
- 12-hour time format (3:30 PM)
- Professional, accessible tone
- Pluralization and gender-neutral language

**When to Use**:
- Before deploying English content
- Reviewing translation completeness for English
- Ensuring US-specific terminology
- Validating English message tone and clarity

**Example Requests**:
```
@en-US-language-agent Please review these English translations for accuracy
@en-US-language-agent Should we use "email" or "e-mail"?
@en-US-language-agent Validate this error message for tone and clarity
```

---

### es-ES-language-agent.md
**Purpose**: European Spanish localization expert

**Responsibilities**:
- Review translations for accuracy and native fluency
- Ensure cultural appropriateness for Spanish audience
- Maintain Castilian Spanish terminology (not Latin American)
- Validate European Spanish formatting (DD/MM/YYYY, 1.234,56 EUR)
- Ensure accessibility in Spanish

**Key Focus**:
- Castilian Spanish (Spain) terminology
- European Spanish conventions
- Formal "usted" (not casual "tú")
- "Vosotros" form where applicable
- Date formatting (DD/MM/YYYY)
- Currency formatting (1.234,56 EUR)
- 24-hour time format (15:30)
- Professional, formal tone

**When to Use**:
- Before deploying Spanish content
- Reviewing translation completeness for Spanish
- Ensuring Spain-specific terminology
- Validating Spanish message tone and formality

**Example Requests**:
```
@es-ES-language-agent Por favor, revisa estas traducciones al español
@es-ES-language-agent ¿Debemos usar "ordenador" o "computadora"?
@es-ES-language-agent Valida este mensaje de error para tono y claridad
```

---

### fr-FR-language-agent.md
**Purpose**: French localization expert

**Responsibilities**:
- Review translations for accuracy and native fluency
- Ensure cultural appropriateness for French audience
- Maintain metropolitan French terminology
- Validate French formatting (DD/MM/YYYY, 1 234,56 EUR)
- Ensure accessibility in French

**Key Focus**:
- Metropolitan French (France) terminology
- European French conventions
- Formal "vous" (not casual "tu")
- Proper accents and diacritical marks
- Date formatting (DD/MM/YYYY)
- Currency formatting (1 234,56 EUR with space)
- 24-hour time format (15:30)
- Professional, courteous tone
- Correct punctuation spacing (space before : ; ! ?)

**When to Use**:
- Before deploying French content
- Reviewing translation completeness for French
- Ensuring France-specific terminology
- Validating French message tone and formality

**Example Requests**:
```
@fr-FR-language-agent Veuillez vérifier ces traductions en français
@fr-FR-language-agent Quel est le bon spacing avant la ponctuation?
@fr-FR-language-agent Validez ce message d'erreur pour le ton et la clarité
```

---

## 🔄 Agent Interaction Patterns

### Pattern 1: Building a Multi-Language Component

```
1. architecture_agent → Approves feature scope
2. quasar_agent → Designs UI layout
3. vue_agent → Implements component logic
4. typescript_agent → Defines TypeScript types
5. i18n_agent → Designs translation structure
6. moqui_developer_agent → Implements $t() calls
7. en-US-language-agent → Reviews English
8. es-ES-language-agent → Reviews Spanish
9. fr-FR-language-agent → Reviews French
10. test_agent → Validates component
11. docs_agent → Documents component
```

### Pattern 2: Adding a New Language

```
1. Create new language agent (copy existing, customize)
2. i18n_agent → Add to supported locales
3. moqui_developer_agent → Create translation files
4. New-language-agent → Review translations
5. i18n_agent → Validate completeness
6. Deploy with new language support
```

### Pattern 3: Reviewing Translations

```
1. Developer → Completes translations
2. i18n_agent → Validates structure and completeness
3. Language-specific agent → Native speaker review
4. Developer → Incorporates feedback
5. language-agent → Approves final translations
6. Deploy to production
```

---

## 📋 Quick Reference by Task

### "I need to build a form"
**Agents to consult** (in order):
1. `architecture_agent` - Approve scope
2. `quasar_agent` - Design form layout
3. `vue_agent` - Implement form logic
4. `typescript_agent` - Define form types

### "I need to add translations"
**Agents to consult**:
1. `i18n_agent` - Design translation structure
2. Add $t() calls to your components
3. `language_agents` - Review translations

### "I need to add a new language"
**Agents to consult**:
1. Create new language agent
2. `i18n_agent` - Add to configuration
3. Create translation files
4. New language agent - Review
5. Deploy

### "I need type safety"
**Agent to consult**:
1. `typescript_agent` - Define types
2. Implement using types in components

---

## 📁 File Locations

```
.github/agents/
├── i18n-agent.md                    # Internationalization expert
├── typescript-agent.md               # TypeScript type safety
├── quasar-agent.md                   # UI components (existing, referenced)
├── vue-agent.md                      # Vue.js framework (existing, referenced)
├── en-US-language-agent.md          # English (US) localization
├── es-ES-language-agent.md          # Spanish (Spain) localization
├── fr-FR-language-agent.md          # French localization
└── [other agents...]

.github/docs/
├── AGENT_COLLABORATION.md           # Full agent architecture (updated)
├── I18N_AND_UI_AGENT_ECOSYSTEM.md  # This ecosystem overview
├── I18N_QUICK_START.md              # Quick start guide for developers
└── [other docs...]
```

---

## 🎯 Quick Invocation Examples

```
# Internationalization & Translation
@i18n_agent Set up translations for a new feature
@i18n_agent How should I structure translation keys?
@i18n_agent Validate translation completeness for Spanish

# Type Safety
@typescript_agent Define types for a product management component
@typescript_agent Create type-safe translation interfaces

# UI Design & Implementation
@quasar_agent Design a responsive dashboard layout
@vue_agent Implement a customer search with filtering
@quasar_agent How do I build a data table with sorting?

# Language Review
@en-US-language-agent Please review English translations
@es-ES-language-agent Por favor, revisa las traducciones al español
@fr-FR-language-agent Veuillez vérifier les traductions en français
```

---

## 📊 Agent Collaboration Matrix

| Feature | quasar_agent | vue_agent | typescript_agent | i18n_agent | language_agents |
|---------|:---:|:---:|:---:|:---:|:---:|
| **UI Layout** | ✅ | - | - | - | - |
| **Component Logic** | - | ✅ | - | - | - |
| **Type Safety** | - | ✅ | ✅ | ✅ | - |
| **Translations** | - | - | - | ✅ | - |
| **Translation Review** | - | - | - | - | ✅ |
| **Responsive Design** | ✅ | ✅ | - | - | - |
| **i18n Integration** | ✅ | ✅ | ✅ | ✅ | - |

---

## 🎓 Learning Path

1. **Start here**: `.github/docs/I18N_QUICK_START.md`
2. **Deep dive**: `.github/docs/I18N_AND_UI_AGENT_ECOSYSTEM.md`
3. **Full reference**: `.github/docs/AGENT_COLLABORATION.md`
4. **Agent expertise**: Individual `.github/agents/*.md` files

---

## 📞 Getting Help

```
# Don't know which agent to use?
@architecture_agent Which agents should I consult for [my task]?

# Question about translations?
@i18n_agent How do I handle [translation challenge]?

# Question about types?
@typescript_agent How do I type [component/data structure]?

# Question about UI design?
@quasar_agent How do I build [UI component]?

# Native speaker review?
@[language]-language-agent Please review these translations
```

---

## ✅ Success Criteria

Your multi-language component is ready when:
- ✅ Designed with `quasar_agent`
- ✅ Implemented with `vue_agent`
- ✅ Typed with `typescript_agent`
- ✅ i18n configured with `i18n_agent`
- ✅ Translations reviewed by language agents
- ✅ Tests pass
- ✅ Documentation complete

---

**Welcome to the Durion ERP internationalization ecosystem!** 🌍
