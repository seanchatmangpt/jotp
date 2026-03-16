#!/usr/bin/env python3
"""
Remove method-level @DtrTest annotations from test files.

The @DtrTest annotation should only be at the class level (TYPE).
Method-level @DtrTest causes compilation errors.
"""

import re
import sys
from pathlib import Path


def remove_method_level_dtr_test(content: str) -> tuple[str, int]:
    """
    Remove method-level @DtrTest annotations.

    A method-level @DtrTest is identified by:
    1. Line contains just @DtrTest (possibly with whitespace)
    2. Within 10 lines after, we find a method declaration (void/Type name(...))
    3. Before finding the method, we DON'T find a class/interface/enum declaration

    This approach handles cases where @DisplayName or other annotations
    appear between @DtrTest and the method declaration.
    """
    lines = content.split('\n')
    result = []
    i = 0
    changes = 0

    while i < len(lines):
        line = lines[i]

        # Check if this line is a standalone @DtrTest annotation
        if re.match(r'^\s*@DtrTest\s*$', line):
            # Look ahead to see if this is on a method (not a class)
            is_method_annotation = False
            j = i + 1

            # Look ahead up to 10 lines
            while j < len(lines) and j < i + 11:
                next_line = lines[j]

                # Skip empty lines and other annotations
                if not next_line.strip() or next_line.strip().startswith('@'):
                    j += 1
                    continue

                # Check if this is a method declaration
                # Patterns: void method(...), Type method(...), private/protected/public
                if re.search(r'\s+(?:public|private|protected)?\s*(?:static)?\s*\w+\s+\w+\s*\(', next_line):
                    is_method_annotation = True
                    break

                # Check if this is a class/enum/interface declaration (keep the annotation)
                if re.search(r'\s+(class|enum|interface|record)\s+', next_line):
                    is_method_annotation = False
                    break

                # Check if this is a field declaration (keep the annotation)
                if re.search(r'\s+\w+\s+\w+\s*;.*$', next_line) and not '(' in next_line:
                    is_method_annotation = False
                    break

                j += 1

            if is_method_annotation:
                # Skip this line (remove method-level @DtrTest)
                changes += 1
                i += 1
                continue

        result.append(line)
        i += 1

    return '\n'.join(result), changes


def fix_file(file_path: Path) -> int:
    """Fix method-level @DtrTest annotations in a single file."""
    try:
        content = file_path.read_text()
        original_content = content

        content, changes = remove_method_level_dtr_test(content)

        if changes > 0:
            file_path.write_text(content)
            print(f'  -> Removed {changes} method-level @DtrTest annotations')

        return changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        return 0


def main():
    """Remove method-level @DtrTest annotations from all test files."""
    test_dir = Path('src/test/java')

    # Find all Java files with @DtrTest
    test_files = []
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()
        if '@DtrTest' in content:
            test_files.append(java_file)

    print(f'Found {len(test_files)} files with @DtrTest')

    total_changes = 0
    files_changed = 0

    for java_file in test_files:
        print(f'Processing: {java_file.relative_to(Path("."))}')
        changes = fix_file(java_file)
        if changes > 0:
            total_changes += changes
            files_changed += 1

    print(f'\nRemoved {total_changes} method-level @DtrTest annotations from {files_changed} files')


if __name__ == '__main__':
    main()
