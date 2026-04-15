import re
import sys

def fix_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    counter = 1
    def repl(m):
        nonlocal counter
        c_str = f'{counter:032d}'
        # format with dashes: 8-4-4-4-12
        uuid_str = f'{c_str[:8]}-{c_str[8:12]}-{c_str[12:16]}-{c_str[16:20]}-{c_str[20:]}'
        res = f'UUID.fromString("{uuid_str}")'
        counter += 1
        return res

    # The previous script might have already replaced them, so we need to match digits without dashes
    new_content = re.sub(r'UUID\.fromString\("\d{32}"\)', repl, content)
    
    with open(filepath, 'w') as f:
        f.write(new_content)

for arg in sys.argv[1:]:
    fix_file(arg)
