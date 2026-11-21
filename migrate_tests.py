#!/usr/bin/env python3
import os
import re

def migrate_test_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # 1. Replace @Before with @BeforeEach and @After with @AfterEach safely
    content = re.sub(r'@Before(?![Ee]ach)', '@BeforeEach', content)
    content = re.sub(r'@After(?![Ee]ach)', '@AfterEach', content)
    
    # Also fix the corruption caused by previous failed runs
    content = content.replace('@BeforeEachEach', '@BeforeEach')
    content = content.replace('@AfterEachEach', '@AfterEach')

    # 2. Replace assertThrows<T> { ... } with assertThrows(T::class.java) { ... }
    # This regex looks for assertThrows< followed by anything until >
    # and replaces it with assertThrows( followed by the captured type + ::class.java)
    content = re.sub(r'assertThrows<([^>]+)>', r'assertThrows(\1::class.java)', content)

    # 3. Remove any existing junit imports to avoid duplicates or incorrect ones
    lines = content.splitlines()
    new_lines = [line for line in lines if 'import org.junit' not in line]
    content = '\n'.join(new_lines)

    # 4. Re-insert the correct JUnit 5 imports after the package declaration
    import_block = (
        '\n'
        'import org.junit.jupiter.api.Test\n'
        'import org.junit.jupiter.api.BeforeEach\n'
        'import org.junit.jupiter.api.AfterEach\n'
        'import org.junit.jupiter.api.Nested\n'
        'import org.junit.jupiter.api.io.TempDir\n'
        'import org.junit.jupiter.api.Assertions.*\n'
    )

    if 'package ' in content:
        package_idx = content.find('package ')
        newline_idx = content.find('\n', package_idx)
        if newline_idx != -1:
            new_content = content[:newline_idx+1] + import_block + content[newline_idx+1:]
        else:
            new_content = content + import_block
    else:
        new_content = import_block + content

    with open(filepath, 'w') as f:
        f.write(new_content)

def main():
    root_dir = '/opt/data/projects/droidhelp/app/src/test/java/com/bighelp/droidhelp'
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.kt'):
                migrate_test_file(os.path.join(root, file))

if __name__ == '__main__':
    main()
