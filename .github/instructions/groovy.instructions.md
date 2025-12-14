---
description: 'Guidelines for building Groovy applications in Moqui Framework'
applyTo: '**/*.groovy, **/*Service.groovy, **/*RestEvents.groovy'
---

# Groovy Development

## General Instructions

- Address code smells proactively during development rather than accumulating technical debt.
- Focus on readability, maintainability, and performance when refactoring identified issues.
- Use IDE / Code editor reported warnings and suggestions to catch common patterns early in development.
- Remember that Groovy is dynamically typed by default; use static typing (`@CompileStatic`) for better performance and IDE support.
- Leverage Groovy's concise syntax while maintaining clarity and preventing common gotchas.

## Best practices

### Static Typing

- Use `@CompileStatic` annotation on classes and methods for:
  - Better performance (no runtime metaclass operations)
  - Enhanced IDE support and refactoring
  - Compile-time type checking
  - Example:
    ```groovy
    @groovy.transform.CompileStatic
    class UserService {
        User getUserById(String userId) {
            // ...
        }
    }
    ```

- Specify types for method parameters and return values even when not using `@CompileStatic`
  ```groovy
  User getUserById(String userId) { // explicit types
      // ...
  }
  
  // Avoid: def getUserById(userId) { // ambiguous
  ```

### Groovy-Specific Features

- **Closures**: Use closures for iteration, callbacks, and functional programming patterns
  ```groovy
  list.each { item -> 
      println item 
  }
  
  list.collect { item -> 
      item.transform() 
  }
  ```

- **Safe Navigation Operator**: Use `?.` to safely navigate potentially null objects
  ```groovy
  String name = user?.profile?.name // Returns null if any intermediate is null
  ```

- **Elvis Operator**: Use `?:` for null-coalescing defaults
  ```groovy
  String displayName = user.name ?: "Unknown"
  ```

- **Spread Operator**: Use `*.` to apply operations to all elements
  ```groovy
  List<String> names = users*.name
  ```

- **GString Interpolation**: Use GStrings for cleaner string formatting
  ```groovy
  String message = "Hello, ${user.name}!"
  int count = 5
  String plural = "Item${count != 1 ? 's' : ''}"
  ```

### Naming Conventions

- Follow Google's Java style guide (applies to Groovy):
  - `UpperCamelCase` for class and interface names
  - `lowerCamelCase` for method and variable names
  - `UPPER_SNAKE_CASE` for constants
  - `lowercase` for package names
- Use nouns for classes (`UserService`) and verbs for methods (`getUserById`)
- Avoid abbreviations and Hungarian notation
- For Moqui services, follow the pattern: `verb` + `noun` (e.g., `createUser`, `findOrder`)

### Immutability

- Favor immutable objects. Make fields `final` where possible
  ```groovy
  @groovy.transform.Immutable
  class User {
      String id
      String name
      String email
  }
  ```

- Use `@Immutable` or `@ImmutableBase` for data classes
- Prefer returning new objects rather than modifying existing ones

### Null Handling

- Avoid returning or accepting `null`
- Use Optional-like patterns for possibly-absent values
  ```groovy
  User getUser(String id) {
      return users.find { it.id == id }?.let { it } ?: null
  }
  ```

- Use safe navigation (`?.`) and Elvis operator (`?:`)
- Apply `@Nullable` and `@NotNull` annotations for clarity

### Collections and Streams

- Use Groovy's collection methods instead of loops
  ```groovy
  // Good
  List<String> names = users.collect { it.name }
  List<User> active = users.findAll { it.isActive }
  
  // Avoid
  List<String> names = []
  for (User user : users) {
      names.add(user.name)
  }
  ```

- Chain operations for clarity
  ```groovy
  result = users
      .findAll { it.status == 'ACTIVE' }
      .collect { it.toDTO() }
      .sort { it.name }
  ```

### Method Size and Complexity

- Keep methods small and focused. Extract helper methods to improve readability
  ```groovy
  // Good: Small, focused methods
  def processOrder(Order order) {
      validateOrder(order)
      calculateTotals(order)
      persistOrder(order)
  }
  
  private void validateOrder(Order order) {
      // validation logic
  }
  ```

- Reduce nested conditionals by using early returns or guards
  ```groovy
  // Good: Guard clause
  def processUser(User user) {
      if (!user) return
      if (!user.isActive) return
      
      // Process user
  }
  
  // Avoid: Nested ifs
  if (user) {
      if (user.isActive) {
          // Process user
      }
  }
  ```

### Parameter Count

- Keep method parameter lists short (ideally ≤ 3 parameters)
- If a method needs many parameters, consider:
  - Grouping into a value object or Map
  - Using the builder pattern
  ```groovy
  // Avoid: Too many parameters
  def createUser(String name, String email, String phone, String address, String city, String zip) { }
  
  // Good: Use value object
  def createUser(UserDTO user) { }
  
  // Good: Use map
  def createUser(Map<String, String> user) { }
  ```

### Resource Management

- Always close resources (files, streams, connections)
- Use try-with-resources when possible
  ```groovy
  // Good: Automatic resource closing
  try (Reader reader = new FileReader(file)) {
      String content = reader.text
  }
  
  // Or use Groovy's withCloseable
  new FileReader(file).withCloseable { reader ->
      String content = reader.text
  }
  ```

### Equality Checks

- Use `.equals()` for object comparison, not `==` (which checks reference)
  ```groovy
  // Good
  if (user.name.equals(other.name)) { }
  
  // Avoid (in most cases)
  if (user.name == other.name) { // Could work due to Groovy's equals override, but be explicit
  ```

- Use `Objects.equals()` for null-safe comparison
  ```groovy
  import java.util.Objects
  
  if (Objects.equals(user.email, other.email)) { }
  ```

## Moqui-Specific Patterns

### Service Implementation

- Implement Moqui services with clear input/output
  ```groovy
  // src/main/groovy/org/moqui/impl/UserServices.groovy
  
  @groovy.transform.CompileStatic
  class UserServices {
      
      def createUser(Map<String, Object> params) {
          String name = (String) params.name
          String email = (String) params.email
          
          // Validation
          if (!name) throw new IllegalArgumentException("Name is required")
          if (!email) throw new IllegalArgumentException("Email is required")
          
          // Business logic
          Map<String, Object> result = [
              userId: generateId(),
              name: name,
              email: email,
              createdDate: new Date()
          ]
          
          return result
      }
  }
  ```

- Use Moqui's entity and service APIs
  ```groovy
  def getUserById(String userId) {
      // Via ExecutionContext in Moqui
      return ec.entity.find("User").condition("userId", userId).one()
  }
  
  def callService(String serviceName, Map params) {
      return ec.service.sync().name(serviceName).parameters(params).call()
  }
  ```

### Entity Services

- Keep service logic focused on business operations
  ```groovy
  def createOrder(Map<String, Object> orderData) {
      validateOrderData(orderData)
      
      Map order = [
          orderId: ec.getSequencer().getNextSeqId("Order"),
          orderDate: new Date(),
          status: "PENDING"
      ] + orderData
      
      ec.entity.makeValue("Order").setAll(order).create()
      
      return order
  }
  
  private void validateOrderData(Map data) {
      if (!data.customerId) throw new IllegalArgumentException("customerId required")
      if (!data.total || data.total <= 0) throw new IllegalArgumentException("total must be positive")
  }
  ```

### REST Event Handlers

- Implement REST event handlers with proper input/output mapping
  ```groovy
  @groovy.transform.CompileStatic
  class UserRestEvents {
      
      def getUser(ExecutionContext ec) {
          String userId = ec.web.parameters.userId
          
          if (!userId) {
              ec.web.response.setStatus(400)
              return [error: "userId parameter required"]
          }
          
          User user = ec.entity.find("User").condition("userId", userId).one()
          
          if (!user) {
              ec.web.response.setStatus(404)
              return [error: "User not found"]
          }
          
          return [user: user.getMap()]
      }
  }
  ```

### Groovy in Moqui XML Services

- Keep inline Groovy scripts focused and readable
  ```xml
  <service verb="create" noun="User" type="script">
      <in-parameters>
          <parameter name="name" required="true"/>
          <parameter name="email" required="true"/>
      </in-parameters>
      <out-parameters>
          <parameter name="userId"/>
      </out-parameters>
      <script location="org/moqui/impl/UserServices.groovy" method="createUser"/>
  </service>
  ```

- Use scripts for simple logic, methods for complex logic
  ```groovy
  // In XML service definition - simple script
  ec.getLogger().info("Processing user ${name}")
  Map user = [name: name, email: email]
  ec.entity.makeValue("User").setAll(user).create()
  userId = user.userId
  
  // For complex logic, call a service method instead
  ```

## Common Bug Patterns

- **Null Pointer Exceptions** - Always check for null before accessing properties
  ```groovy
  // Avoid
  String name = user.profile.name // NPE if user or profile is null
  
  // Good
  String name = user?.profile?.name
  ```

- **String Comparisons** - Use `.equals()` or compare lowercase for case-insensitive
  ```groovy
  // Good
  if (status.equals("ACTIVE")) { }
  
  // For case-insensitive
  if (status?.toLowerCase() == "active") { }
  ```

- **Collection Modifications During Iteration** - Don't modify collections while iterating
  ```groovy
  // Avoid
  list.each { item ->
      if (shouldRemove(item)) {
          list.remove(item) // Causes problems
      }
  }
  
  // Good
  list.removeAll { item ->
      shouldRemove(item)
  }
  ```

- **Type Coercion Issues** - Be explicit with type conversions
  ```groovy
  // Avoid relying on implicit coercion
  String value = "123"
  Integer number = value as Integer
  
  // Better: explicit conversion with validation
  Integer number = value?.isInteger() ? value as Integer : null
  ```

- **Closure Variable Scope** - Be aware that closures capture variables by reference
  ```groovy
  List<Closure> closures = []
  for (int i = 0; i < 3; i++) {
      closures.add { println i } // All will print 3 (closure captures reference)
  }
  
  // Better: use method or capture with variable
  List<Closure> closures = (0..<3).collect { int i ->
      { println i }
  }
  ```

## Common Code Smells

- **Parameter count** - Keep method parameter lists short. Group into value objects if needed
- **Method size** - Keep methods focused and small. Extract helper methods
- **Cognitive complexity** - Reduce nested conditionals and heavy branching
- **Duplicated code** - Extract repeated logic into reusable methods or utilities
- **Dead code** - Remove unused variables, methods, and assignments
- **Magic numbers** - Replace numeric literals with named constants
  ```groovy
  // Avoid
  if (age > 18) { }
  
  // Good
  static final int ADULT_AGE = 18
  if (age > ADULT_AGE) { }
  ```

- **Over-use of dynamic features** - Use `@CompileStatic` for better performance and type safety
- **Empty catch blocks** - Always handle or log exceptions
  ```groovy
  // Avoid
  try {
      doSomething()
  } catch (Exception e) {
      // Do nothing
  }
  
  // Good
  try {
      doSomething()
  } catch (Exception e) {
      logger.error("Operation failed", e)
      throw new BusinessException("Failed to process", e)
  }
  ```

## Build and Verification

- After adding or modifying code, verify the project continues to build successfully
- Run `./gradlew build` to build the project (Moqui uses Gradle)
- Ensure all tests pass as part of the build
  ```bash
  ./gradlew clean build
  ```

- Use `./gradlew compileGroovy` to check Groovy compilation specifically
  ```bash
  ./gradlew compileGroovy
  ```

- Consider running Groovy linting tools:
  ```bash
  ./gradlew check # Runs all checks including Groovy CodeNarc
  ```

## IDE Support

- Configure your IDE for Groovy development:
  - Enable `@CompileStatic` support
  - Configure code formatting for Groovy style
  - Use IDE inspections for common Groovy issues
  - Enable Groovy documentation generation (JavaDoc/GroovyDoc)

- Use GroovyDoc annotations for public APIs
  ```groovy
  /**
   * Retrieves a user by ID
   * @param userId the unique identifier of the user
   * @return the User object, or null if not found
   */
  User getUserById(String userId) {
      // ...
  }
  ```
