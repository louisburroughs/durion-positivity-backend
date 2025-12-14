# Internationalization & UI Development Agent Ecosystem

## Overview

The Durion ERP application now has a comprehensive agent ecosystem to support multi-language, type-safe, modern UI development. These agents work together in coordinated workflows to deliver enterprise-grade user interfaces with internationalization (i18n) support.

## New Agents Created

### Core Internationalization Agents
1. **i18n-agent** - Internationalization expert (Vue-i18n, translation management, locale formatting)
2. **typescript-agent** - TypeScript expert (type safety, interfaces, component typing)
3. **quasar-agent** - Quasar Framework expert (UI components, responsive design)
4. **vue-agent** - Vue.js 2 expert (component architecture, reactivity, state management)

### Language-Specific Agents
1. **en-US-language-agent** - American English localization expert
2. **es-ES-language-agent** - European Spanish localization expert
3. **fr-FR-language-agent** - French localization expert

## Agent Collaboration Matrix

### 1. UI Component Development
```
architecture_agent (approves scope)
    ↓
quasar_agent (designs layout) + vue_agent (structures component)
    ↓
typescript_agent (defines types)
    ↓
[component with full i18n support]
    ↓
test_agent (validates) + lint_agent (quality checks)
    ↓
docs_agent (documents)
```

### 2. Internationalization Workflow
```
i18n_agent (designs i18n structure)
    ↓
moqui_developer_agent (implements $t() calls)
    ↓
typescript_agent (creates translation types)
    ↓
[Translation files created]
    ↓
i18n_agent (validates completeness)
    ↓
en-US-language-agent (reviews English)
es-ES-language-agent (reviews Spanish)
fr-FR-language-agent (reviews French)
    ↓
[Approved translations with native speaker validation]
```

### 3. Type-Safe Multi-Language Component
```
architecture_agent (approves feature + languages)
    ↓
typescript_agent (defines component + translation types)
    ↓
quasar_agent (selects components) + vue_agent (implements logic)
    ↓
i18n_agent (configures translations)
    ↓
[Implementation ready]
    ↓
moqui_developer_agent (adds $t() calls)
    ↓
language_agents (review translations)
    ↓
[Deployment ready]
```

## Communication Workflows

### Workflow 1: Building a Multi-Language Product Form

**Step 1: Architecture & Design**
```
Request to @architecture_agent:
"Please approve building a product creation form with support for:
- English (US), Spanish (Spain), and French translations
- Responsive design for mobile/tablet/desktop
- Type-safe form validation"

@architecture_agent response:
"Approved. Product creation is within the product_manager domain.
Use existing product entity. Coordinate with quasar_agent for UI,
typescript_agent for types, and i18n_agent for translations."
```

**Step 2: UI & Component Structure**
```
Request to @quasar_agent:
"Design a product form with:
- Text inputs for name and SKU
- Number input for price
- Select for category
- Textarea for description
- Save/Cancel buttons
- Mobile responsive"

Request to @vue_agent:
"Implement the product form component with:
- Form state management
- Validation logic
- API integration for category lookup
- Error handling
- Language switching support"
```

**Step 3: Type Safety**
```
Request to @typescript_agent:
"Create TypeScript interfaces for:
- Product form data
- API request/response types
- Component prop types
- Translation message schema"

@typescript_agent provides:
```typescript
interface ProductFormData {
  name: string
  sku: string
  price: number
  categoryId: string
  description: string
}

type ProductFormMessages = {
  'form.title': string
  'form.namePlaceholder': string
  'form.pricePlaceholder': string
  'validation.required': string
}
```
```

**Step 4: Internationalization**
```
Request to @i18n_agent:
"Set up translations for the product form in:
- English (US)
- Spanish (Spain)
- French (France)

Structure translations for:
- Form labels and placeholders
- Validation messages
- Success/error notifications
- Category options"

@i18n_agent creates:
```
src/i18n/locale/
├── en-US.json
├── es-ES.json
└── fr-FR.json

src/i18n/messages/
├── product-form.ts (TypeScript types)
└── ...
```
```

**Step 5: Translation Reviews**
```
Request to @en-US-language-agent:
"Please review the English translations for the product form.
Verify terminology, formatting, and tone are appropriate
for US business users."

Request to @es-ES-language-agent:
"Por favor, revisa las traducciones al español.
Verifica terminología de negocio y formato de fecha/moneda."

Request to @fr-FR-language-agent:
"Veuillez vérifier les traductions français.
Validez la terminologie et le format date/devise."

Language agents respond with:
- Approval status
- Suggested improvements
- Terminology corrections
- Format validation (dates, numbers, currency)
```

**Step 6: Final Validation**
```
Request to @test_agent:
"Run comprehensive tests on ProductForm component:
- Form validation
- API integration
- Language switching
- Error scenarios
- Mobile responsiveness"

Request to @docs_agent:
"Document the ProductForm component:
- Component API (props, events)
- Usage examples
- Supported languages
- Translation keys"
```

**Result**: Production-ready, multi-language, type-safe product form component!

## Agent Responsibilities Summary

| Agent | Primary Responsibility | Works With | Authority |
|-------|---|---|---|
| **typescript-agent** | Type safety & interfaces | vue-agent, quasar-agent, i18n-agent | Recommends, cannot block |
| **i18n-agent** | Translation structure & coordination | moqui-developer, language-agents | Must approve translations |
| **quasar-agent** | UI components & layout | vue-agent, i18n-agent | Must approve UI design |
| **vue-agent** | Component logic & state | typescript-agent, i18n-agent | Must approve implementation |
| **en-US-language-agent** | English localization review | i18n-agent | Must approve English |
| **es-ES-language-agent** | Spanish localization review | i18n-agent | Must approve Spanish |
| **fr-FR-language-agent** | French localization review | i18n-agent | Must approve French |

## Quick Reference: Agent Invocations

### UI Component Development
```
@quasar_agent Design the layout for a customer management dashboard
@vue_agent Implement the customer data table component
@typescript_agent Define types for customer data and forms
```

### Internationalization
```
@i18n_agent How should I structure translations for multiple features?
@en-US-language-agent Please review these English translations
@es-ES-language-agent Por favor, revisa estas traducciones al español
```

### Language-Specific Questions
```
@en-US-language-agent Is "email" or "e-mail" preferred in US business?
@es-ES-language-agent Should we use "ordenador" (Spain) or "computadora" (LatAm)?
@fr-FR-language-agent What's the correct spacing before punctuation in French?
```

## File Structure

```
.github/agents/
├── i18n-agent.md                    ← NEW: Internationalization expert
├── typescript-agent.md               ← NEW: TypeScript type safety
├── quasar-agent.md                   ← EXISTING: UI components
├── vue-agent.md                      ← EXISTING: Vue.js framework
├── en-US-language-agent.md          ← NEW: English localization
├── es-ES-language-agent.md          ← NEW: Spanish localization
├── fr-FR-language-agent.md          ← NEW: French localization
└── [other agents...]

src/i18n/ (NEW - to be created)
├── index.js                         # Vue-i18n configuration
├── locale/
│   ├── en-US.json
│   ├── es-ES.json
│   └── fr-FR.json
└── messages/
    ├── common.ts
    ├── components.ts
    ├── product-form.ts
    └── ...

src/types/ (NEW - for TypeScript)
├── components.ts                    # Component types
├── moqui.ts                         # Moqui integration types
├── vue.ts                           # Vue-specific types
└── ...
```

## Key Principles

1. **Type Safety First**: All UI code should be TypeScript with strict mode
2. **Internationalization By Default**: Every UI string must be translatable
3. **Native Speaker Review**: All translations reviewed by language experts
4. **Consistent Tone & Formatting**: Each language maintains its professional standards
5. **Component Isolation**: Components are independent and reusable
6. **Single Responsibility**: Each agent focuses on their domain expertise
7. **Collaborative Validation**: Multiple agents review each feature

## Success Checklist for Multi-Language UI Feature

- [ ] Architecture approved by `architecture_agent`
- [ ] UI layout approved by `quasar_agent`
- [ ] Component implementation approved by `vue_agent`
- [ ] TypeScript types reviewed by `typescript_agent`
- [ ] i18n structure approved by `i18n_agent`
- [ ] English translations approved by `en-US-language-agent`
- [ ] Spanish translations approved by `es-ES-language-agent`
- [ ] French translations approved by `fr-FR-language-agent`
- [ ] Tests pass from `test_agent`
- [ ] Code quality passes from `lint_agent`
- [ ] Documentation complete from `docs_agent`
- [ ] Deployed successfully by `dev_deploy_agent`

## Adding New Languages

To add a new language (e.g., German):

1. **Create language agent**:
   - Create `.github/agents/de-DE-language-agent.md`
   - Model it after `en-US-language-agent.md`
   - Include language-specific grammar, conventions, formatting

2. **Add translation files**:
   - Create `src/i18n/locale/de-DE.json`
   - Add German translations for all keys

3. **Update i18n configuration**:
   - Add to Vue-i18n messages in `src/i18n/index.js`
   - Add number/date formats for German

4. **Update language selector**:
   - Add German option to language dropdown component
   - Update supported locales list

5. **Language review workflow**:
   - Submit translations to `de-DE-language-agent`
   - Incorporate feedback
   - Get approval

## Example: Adding German (de-DE)

```
1. @architecture_agent Approve adding German language support
2. Create de-DE-language-agent.md with German expertise
3. @moqui_developer_agent Add German translations to i18n files
4. @de-DE-language-agent Bitte überprüfen Sie die deutschen Übersetzungen
5. Incorporate feedback and get approval
6. @i18n_agent Validate German language completeness
7. Deploy with German support enabled
```

## Coordination Example: Multi-Language Dashboard

```
@architecture_agent
"Approve building a sales dashboard with:
- Key metrics (revenue, orders, customers)
- Charts and visualizations  
- Date range filters
- Multi-language support (EN, ES, FR, DE)"

@quasar_agent
"Design dashboard layout with QCard components,
QLinearProgress for metrics, QChart for visualizations"

@vue_agent
"Implement dashboard component with:
- Date range filter state
- Chart data fetching
- Real-time updates
- Language switching"

@typescript_agent
"Create interfaces for:
- Dashboard metrics
- Chart data
- API responses
- Translation messages"

@i18n_agent
"Organize translations for:
- Dashboard title and labels
- Metric names and units
- Error messages
- Date/currency formatting per locale"

@en-US-language-agent "Review English"
@es-ES-language-agent "Review Spanish"
@fr-FR-language-agent "Review French"
@de-DE-language-agent "Review German"

Result: Fully localized, type-safe dashboard!
```

## Performance Considerations

- **Lazy-load translations**: Load only needed locales
- **Code splitting**: Separate translation files per feature
- **Type safety**: Prevents translation key typos at compile time
- **Caching**: LocalStorage for user's language preference
- **No DOM overhead**: Vue-i18n is lightweight

## Next Steps

1. Create `src/i18n/` directory structure
2. Create initial English translations
3. Have `en-US-language-agent` review English
4. Add Spanish translations
5. Have `es-ES-language-agent` review Spanish
6. Add French translations
7. Have `fr-FR-language-agent` review French
8. Build first multi-language component
9. Document translation workflow

## References

- Vue-i18n Docs: https://kazupon.github.io/vue-i18n/
- TypeScript Handbook: https://www.typescriptlang.org/docs/
- Quasar Documentation: https://quasar.dev/
- Agent Collaboration Framework: `.github/docs/AGENT_COLLABORATION.md`
