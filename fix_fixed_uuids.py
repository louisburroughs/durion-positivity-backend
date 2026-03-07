import sys
import re

def fix_file(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    counter = 1
    def replacement(match):
        nonlocal counter
        res = f'00000000-0000-0000-0000-{counter:012x}'
        counter += 1
        return res

    # Replace all occurrences of the "0000...0001" string with a unique incrementing one
    new_content = re.sub(r'00000000-0000-0000-0000-000000000001', replacement, content)

    with open(file_path, 'w') as f:
        f.write(new_content)

files = [
    'pos-accounting/src/test/java/com/positivity/accounting/controller/PaymentApplicationControllerIntegrationTest.java',
    'pos-accounting/src/test/java/com/positivity/accounting/integration/AccountingServiceIntegrationTest.java',
    'pos-accounting/src/test/java/com/positivity/accounting/service/DefaultGLMappingServiceTest.java'
]

for file in files:
    fix_file(file)
    print(f"Fixed {file}")
