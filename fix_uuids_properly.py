import sys
import re

def fix_file(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Add AtomicInteger import if not present
    if 'import java.util.concurrent.atomic.AtomicInteger;' not in content:
        content = re.sub(r'import java.util.UUID;', 'import java.util.UUID;\nimport java.util.concurrent.atomic.AtomicInteger;', content)

    # Add the UUID_COUNTER and nextUuid() method to the class. We'll find the class declaration and add it there.
    if 'private static final AtomicInteger UUID_COUNTER' not in content:
        class_pattern = re.compile(r'(class \w+.*?\{)')
        content = class_pattern.sub(r'\1\n\n    private static final AtomicInteger UUID_COUNTER = new AtomicInteger(1000);\n\n    private UUID nextUuid() {\n        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", UUID_COUNTER.getAndIncrement()));\n    }\n', content, count=1)

    # Replace all occurrences of UUID.fromString("...-000000...") with nextUuid()
    content = re.sub(r'UUID\.fromString\("00000000-0000-0000-0000-[0-9a-f]{12}"\)', 'nextUuid()', content)
    
    # Wait, some places might do .toString() instead of UUID for String fields like applicationRequestId:
    # We should also replace UUID.fromString("...").toString() -> nextUuid().toString()
    
    with open(file_path, 'w') as f:
        f.write(content)

files = [
    'pos-accounting/src/test/java/com/positivity/accounting/controller/PaymentApplicationControllerIntegrationTest.java',
    'pos-accounting/src/test/java/com/positivity/accounting/integration/AccountingServiceIntegrationTest.java',
    'pos-accounting/src/test/java/com/positivity/accounting/service/DefaultGLMappingServiceTest.java'
]

for file in files:
    fix_file(file)
    print(f"Fixed {file}")
