# Inner Classes Usage Rule

## Rule: Avoid Inner Classes for Public APIs

**Principle**: Do not use inner classes (nested classes) for public API components that need to be referenced from other packages or classes. Use standalone classes instead.

## Rationale

Inner classes create several problems:

1. **Type Confusion**: Multiple classes with the same name (e.g., `PackagingResult` vs `DeploymentResults.PackagingResult`)
2. **Import Complexity**: Requires fully qualified names or complex import statements
3. **Compilation Errors**: Type inference failures and incompatible type errors
4. **IDE Issues**: Poor autocomplete and navigation support
5. **Maintenance Overhead**: Harder to refactor and maintain

## Guidelines

### ✅ Use Standalone Classes For:

- Public API classes that are used across packages
- Data transfer objects (DTOs)
- Configuration classes
- Result/Response classes
- Options/Parameters classes

### ✅ Use Inner Classes For:

- Private implementation details within a single class
- Builder patterns (as private static inner classes)
- Callback interfaces used only within the enclosing class
- Enum constants with behavior

### ❌ Avoid Inner Classes For:

- Classes used by multiple other classes
- Classes that might be extended or implemented elsewhere
- Classes that represent domain concepts
- Classes used in method signatures of public APIs

## Implementation Strategy

### Current Issues to Fix:

1. **PackagingResult**: Remove `DeploymentResults.PackagingResult`, use standalone `PackagingResult`
2. **FailoverOptions**: Remove `DeploymentOptions.FailoverOptions`, use standalone `FailoverOptions`
3. **DisasterRecoveryOptions**: Remove `DeploymentOptions.DisasterRecoveryOptions`, use standalone `DisasterRecoveryOptions`

### Refactoring Steps:

1. Extract all inner classes used in public APIs to standalone classes
2. Update all references to use the standalone classes
3. Remove the inner class definitions
4. Ensure consistent naming and package structure

## Examples

### ❌ Bad: Inner Class for Public API

```java
public class DeploymentOptions {
    public static class PackagingOptions {
        // Used by multiple classes - should be standalone
    }
}
```

### ✅ Good: Standalone Class

```java
public class PackagingOptions {
    // Standalone class, easy to import and use
}
```

### ✅ Good: Inner Class for Private Use

```java
public class AgentRegistry {
    private static class RegistryEntry {
        // Only used internally - inner class is fine
    }
}
```

## Enforcement

- All new classes should follow this rule
- Existing inner classes used in public APIs should be refactored to standalone classes
- Code reviews should check for inappropriate use of inner classes
- IDE warnings about type conflicts should be addressed by following this rule

This rule ensures cleaner, more maintainable code with fewer type conflicts and better IDE support.