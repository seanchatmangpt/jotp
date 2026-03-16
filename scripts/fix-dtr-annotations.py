#!/usr/bin/env python3
"""
Fix DTR annotations in test files.

The agents incorrectly used @DtrTest as a method annotation, but it's a TYPE-level annotation.
This script fixes the annotation issues across all test files.
"""

import re
import sys
from pathlib import Path

def fix_dtr_annotations(content: str) -> tuple[str, int]:
    """
    Fix DTR annotations in test file content.

    Returns: (fixed_content, number_of_changes)
    """
    changes = 0
    lines = content.split('\n')
    result = []
    i = 0

    # Track if we've already converted the class annotation
    class_converted = False
    has_dtr_context_field = False
    has_dtr_import = False

    # Check for DTR imports
    for line in lines:
        if 'io.github.seanchatmangpt.dtr.junit5.DtrExtension' in line:
            has_dtr_import = True
            break

    while i < len(lines):
        line = lines[i]

        # Fix class-level @ExtendWith(DtrExtension.class) -> @DtrTest
        if not class_converted and '@ExtendWith(DtrExtension.class)' in line:
            result.append('@DtrTest')
            changes += 1
            class_converted = True
            # Check if we need to add DTR imports later
            i += 1
            continue

        # Fix class-level @ExtendWith(io.github.seanchatmangpt.dtr.junit5.DtrExtension.class)
        if not class_converted and '@ExtendWith(io.github.seanchatmangpt.dtr.junit5.DtrExtension.class)' in line:
            result.append('@DtrTest')
            changes += 1
            class_converted = True
            i += 1
            continue

        # Remove method-level @DtrTest annotation
        if re.match(r'\s*@DtrTest\s*', line):
            # Check if next line is @Test or a method declaration
            if i + 1 < len(lines):
                next_line = lines[i + 1]
                if '@Test' in next_line or re.search(r'\s+(void|@)\s+', next_line):
                    changes += 1
                    i += 1
                    continue

        # Remove duplicate @ExtendWith(DtrExtension.class) if we already have @DtrTest
        if class_converted and '@ExtendWith(DtrExtension.class)' in line:
            changes += 1
            i += 1
            continue

        result.append(line)
        i += 1

    # Fix imports if needed
    if class_converted:
        result_lines = result
        result = []
        imports_added = False

        for line in result_lines:
            # Remove the old DtrExtension import
            if 'import io.github.seanchatmangpt.dtr.junit5.DtrExtension;' in line:
                if not imports_added:
                    # Add DocSection and DocDescription imports
                    result.append('import io.github.seanchatmangpt.dtr.DocDescription;')
                    result.append('import io.github.seanchatmangpt.dtr.DocSection;')
                    imports_added = True
                    changes += 1
                continue
            result.append(line)

        # If we converted but didn't find the import to replace, add new imports anyway
        if not imports_added:
            result = []
            for line in result_lines:
                result.append(line)
                # Add imports after the package declaration
                if line.startswith('package ') and not imports_added:
                    result.append('')
                    result.append('import io.github.seanchatmangpt.dtr.DocDescription;')
                    result.append('import io.github.seanchatmangpt.dtr.DocSection;')
                    imports_added = True
                    changes += 1

    return '\n'.join(result), changes


def main():
    """Fix DTR annotations in all test files."""
    test_dir = Path('src/test/java')

    # Find all Java files that might need fixing
    test_files = []
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()

        # Check if file uses DTR
        if 'DtrContext' in content or 'DtrExtension' in content or 'DtrTest' in content:
            test_files.append(java_file)

    print(f'Found {len(test_files)} test files with DTR usage')

    total_changes = 0
    for java_file in test_files:
        print(f'Processing: {java_file.relative_to(Path("."))}')

        try:
            content = java_file.read_text()
            fixed_content, changes = fix_dtr_annotations(content)

            if changes > 0:
                java_file.write_text(fixed_content)
                total_changes += changes
                print(f'  -> Fixed {changes} issues')
            else:
                print(f'  -> No changes needed')
        except Exception as e:
            print(f'  -> ERROR: {e}', file=sys.stderr)

    print(f'\nTotal changes made: {total_changes}')

    # Run spotless to fix formatting
    print('\nRunning spotless:apply to fix formatting...')
    import subprocess
    subprocess.run(['./mvnw', 'spotless:apply'], check=False)


if __name__ == '__main__':
    main()
