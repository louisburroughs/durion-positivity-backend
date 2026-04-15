import re
import sys

def fix_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    counter = 1
    def repl(m):
        nonlocal counter
        c_str = f'{counter:032d}'
        uuid_str = f'{c_str[:8]}-{c_str[8:12]}-{c_str[12:16]}-{c_str[16:20]}-{c_str[20:]}'
        res = f'UUID.fromString("{uuid_str}")'
        counter += 1
        return res

    # Replace EXACTLY the user's manual blind replacement
    new_content = re.sub(r'UUID\.fromString\("00000000-0000-0000-0000-000000000001"\)', repl, content)
    
    with open(filepath, 'w') as f:
        f.write(new_content)

for arg in sys.argv[1:]:
    fix_file(arg)
