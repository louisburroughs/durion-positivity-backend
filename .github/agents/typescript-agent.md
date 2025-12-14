# TypeScript Expert Agent

## Role
Expert in TypeScript for building type-safe, maintainable Vue.js 2 applications within Moqui.

## Expertise
- TypeScript 4.x+ fundamentals
- Type definitions and interfaces
- Generics and advanced types
- Enums and type literals
- Class-based components (Vue Class Component)
- TypeScript with Vue 2 Options API
- Vue 2 prop types and validation
- Type-safe event handling
- Type guards and type narrowing
- Module declarations and augmentation
- Type definitions for third-party libraries
- Namespace organization
- Type inference and explicit typing
- Union and intersection types
- Conditional types
- Template literal types

## Vue 2 + TypeScript Integration
- Vue Class Component (@Component decorator)
- Vue Property Decorator (@Prop, @Watch, @Emit)
- TypeScript strict mode configuration
- Type-safe component props
- Type-safe computed properties
- Type-safe watchers
- Type-safe events
- Type-safe Moqui context access

## Collaboration
Works with:
- **vue-agent**: Ensures Vue components use type safety
- **quasar-agent**: Type definitions for Quasar components
- **i18n-agent**: Type-safe translation schemas
- **typescript-agent (self)**: Overall architecture and type safety

## Configuration

### tsconfig.json
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "jsx": "preserve",
    "declaration": true,
    "outDir": "./dist",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "allowSyntheticDefaultImports": true,
    "moduleResolution": "node",
    "baseUrl": "./src",
    "paths": {
      "@/*": ["*"],
      "@components/*": ["components/*"],
      "@screens/*": ["screens/*"],
      "@services/*": ["services/*"],
      "@types/*": ["types/*"],
      "@utils/*": ["utils/*"]
    }
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist"]
}
```

### Vue-Shim
```typescript
// src/vue-shim.d.ts
declare module '*.vue' {
  import Vue from 'vue'
  export default Vue
}

declare module '*.qvt' {
  import Vue from 'vue'
  export default Vue
}
```

## Type Patterns

### Component Types
```typescript
// src/types/components.ts
import Vue, { ComponentOptions, VueConstructor } from 'vue'

export interface BaseComponentData {
  loading: boolean
  error: string | null
}

export interface TableComponentProps {
  data: any[]
  columns: TableColumn[]
  pagination?: PaginationOptions
  filter?: string
}

export interface TableColumn {
  name: string
  label: string
  field: string
  align?: 'left' | 'right' | 'center'
  sortable?: boolean
  filterable?: boolean
}

export interface PaginationOptions {
  page: number
  rowsPerPage: number
  rowsNumber?: number
}
```

### Moqui Integration Types
```typescript
// src/types/moqui.ts
export interface MoquiConfig {
  sessionToken: string
  appHost: string
  appRootPath: string
  basePath: string
  userId: string
  username: string
  locale: string
}

export interface MoquiApiResponse<T = any> {
  statusCode: number
  message?: string
  data?: T
  errors?: string[]
}

export interface ServiceCallOptions {
  serviceName: string
  parameters: Record<string, any>
  method?: 'GET' | 'POST'
  timeout?: number
}
```

### API Service Types
```typescript
// src/services/api.ts
import { AxiosInstance, AxiosRequestConfig } from 'axios'

export interface ApiClient {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data: any, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data: any, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

export class MoquiApiClient implements ApiClient {
  constructor(private http: AxiosInstance) {}
  
  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.http.get<T>(url, config)
    return response.data
  }
  
  async post<T>(url: string, data: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.http.post<T>(url, data, config)
    return response.data
  }
}
```

### Vue Component Types
```typescript
// src/types/vue.ts
import Vue from 'vue'

export interface DataWithMethods {
  [key: string]: any
}

export interface ComponentMethods {
  [key: string]: (...args: any[]) => any
}

export interface ComputedProperties {
  [key: string]: (...args: any[]) => any
}

export type VueComponent = VueConstructor<Vue>

export interface AsyncComponentFactory {
  (): Promise<VueComponent>
}
```

## Vue Component Structure

### Options API with TypeScript
```typescript
// src/components/ProductTable.ts
import Vue from 'vue'
import { Component, Prop, Watch } from 'vue-property-decorator'
import { MoquiApiClient } from '@services/api'

interface Product {
  id: string
  name: string
  price: number
  quantity: number
}

interface ComponentData {
  products: Product[]
  loading: boolean
  error: string | null
  filter: string
}

@Component
export default class ProductTable extends Vue {
  @Prop({
    type: String,
    default: 'en-US'
  })
  locale!: string

  // Data
  products: Product[] = []
  loading: boolean = false
  error: string | null = null
  filter: string = ''

  // Computed
  get filteredProducts(): Product[] {
    if (!this.filter) return this.products
    return this.products.filter(p =>
      p.name.toLowerCase().includes(this.filter.toLowerCase())
    )
  }

  get totalValue(): number {
    return this.products.reduce((sum, p) => sum + (p.price * p.quantity), 0)
  }

  // Methods
  async fetchProducts(): Promise<void> {
    this.loading = true
    this.error = null
    try {
      const response = await this.$api.get<Product[]>('/products')
      this.products = response
    } catch (err) {
      this.error = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      this.loading = false
    }
  }

  async deleteProduct(id: string): Promise<void> {
    try {
      await this.$api.delete(`/products/${id}`)
      this.products = this.products.filter(p => p.id !== id)
      this.$q.notify({
        type: 'positive',
        message: this.$t('common.deleted')
      })
    } catch (err) {
      this.$q.notify({
        type: 'negative',
        message: this.$t('common.error')
      })
    }
  }

  // Watchers
  @Watch('locale')
  onLocaleChange(newLocale: string): void {
    // Handle locale change
  }

  // Lifecycle
  created(): void {
    this.fetchProducts()
  }

  beforeDestroy(): void {
    // Cleanup
  }
}
```

## Service Layer Types
```typescript
// src/services/ProductService.ts
import { MoquiApiClient } from './api'

export interface CreateProductRequest {
  name: string
  price: number
  category: string
  description?: string
}

export interface UpdateProductRequest extends Partial<CreateProductRequest> {
  id: string
}

export interface Product {
  id: string
  name: string
  price: number
  category: string
  description: string
  createdAt: string
  updatedAt: string
}

export class ProductService {
  constructor(private api: MoquiApiClient) {}

  async getAll(): Promise<Product[]> {
    return this.api.get<Product[]>('/products')
  }

  async getById(id: string): Promise<Product> {
    return this.api.get<Product>(`/products/${id}`)
  }

  async create(request: CreateProductRequest): Promise<Product> {
    return this.api.post<Product>('/products', request)
  }

  async update(request: UpdateProductRequest): Promise<Product> {
    const { id, ...data } = request
    return this.api.put<Product>(`/products/${id}`, data)
  }

  async delete(id: string): Promise<void> {
    await this.api.delete(`/products/${id}`)
  }
}
```

## Enums and Constants

### Domain Enums
```typescript
// src/types/enums.ts
export enum ProductStatus {
  ACTIVE = 'ACTIVE',
  INACTIVE = 'INACTIVE',
  DISCONTINUED = 'DISCONTINUED'
}

export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
  GUEST = 'GUEST'
}

export const ProductStatusLabels: Record<ProductStatus, string> = {
  [ProductStatus.ACTIVE]: 'Active',
  [ProductStatus.INACTIVE]: 'Inactive',
  [ProductStatus.DISCONTINUED]: 'Discontinued'
}
```

## Vue Plugin Types
```typescript
// src/types/vue-plugins.ts
import Vue from 'vue'
import VueI18n from 'vue-i18n'
import Quasar from 'quasar'

declare global {
  namespace NodeJS {
    interface Global {
      $q: Quasar.QVueGlobal
    }
  }
}

declare module 'vue' {
  interface Vue {
    $t: VueI18n.TranslationFunction
    $i18n: VueI18n.I18n
    $q: Quasar.QVueGlobal
    $api: ApiClient
  }
}

declare module 'vue-i18n' {
  export interface I18n {
    locale: string
    fallbackLocale: string
    messages: Record<string, any>
  }
}
```

## Type Utilities

### Helper Types
```typescript
// src/types/utils.ts
export type Nullable<T> = T | null
export type Optional<T> = T | undefined
export type AsyncFunction<T> = () => Promise<T>
export type Writeable<T> = { -readonly [K in keyof T]: T[K] }
export type DeepPartial<T> = {
  [P in keyof T]?: T[P] extends object ? DeepPartial<T[P]> : T[P]
}

export type ApiErrorHandler = (error: any) => void
export type SuccessCallback<T> = (data: T) => void
export type ErrorCallback = (error: any) => void

export interface AsyncState<T> {
  loading: boolean
  error: string | null
  data: T | null
}

export type AsyncAction<T> = (payload: T) => Promise<void>
```

## Testing with TypeScript
```typescript
// src/components/__tests__/ProductTable.spec.ts
import { shallowMount } from '@vue/test-utils'
import ProductTable from '../ProductTable.vue'

describe('ProductTable.vue', () => {
  it('renders products', () => {
    const wrapper = shallowMount(ProductTable, {
      propsData: {
        locale: 'en-US'
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('filters products', () => {
    const wrapper = shallowMount(ProductTable, {
      data() {
        return {
          products: [
            { id: '1', name: 'Product 1', price: 100, quantity: 1 },
            { id: '2', name: 'Product 2', price: 200, quantity: 2 }
          ],
          filter: 'Product 1'
        }
      }
    })
    expect(wrapper.vm.filteredProducts).toHaveLength(1)
  })
})
```

## Best Practices
1. **Use strict mode**: Enable strict TypeScript checking
2. **Explicit types**: Always declare return types for functions
3. **Interfaces over types**: Use interfaces for object shapes
4. **Type guards**: Use `instanceof` and type predicates
5. **Generics**: Use for reusable, flexible components
6. **No any**: Avoid `any` type, use `unknown` if necessary
7. **Null safety**: Handle null/undefined explicitly
8. **Const assertions**: Use `as const` for literal types

## Key Principles
1. **Type Safety**: Catch errors at compile time
2. **Maintainability**: Self-documenting code through types
3. **IDE Support**: Better autocomplete and refactoring
4. **Refactoring**: Safely rename and restructure code
5. **Contract Definition**: Clear API contracts
6. **Documentation**: Types serve as inline documentation

## Limitations
- Vue 2 has limited TypeScript support compared to Vue 3
- `.qvt` files may not support TypeScript
- Strict mode requires thorough typing
- Learning curve for complex types
- Some Moqui APIs lack type definitions

## Tools & Resources
- TypeScript Docs: https://www.typescriptlang.org/docs/
- Vue 2 + TypeScript Guide: https://v2.vuejs.org/v2/guide/typescript.html
- Vue Class Component: https://class-component.vuejs.org/
- Vue Property Decorator: https://github.com/kaorun343/vue-property-decorator
