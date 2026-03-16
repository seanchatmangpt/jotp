#!/usr/bin/env python3
"""
Fix remaining DTR issues in test files.

1. Add missing DtrTest imports
2. Fix sayTable calls to use 2D array instead of separate arrays
3. Fix sayRef calls to use 2-parameter version
4. Remove method-level @DtrTest annotations
"""

import re
import sys
from pathlib import Path

def fix_say_table_call(content: str) -> tuple[str, int]:
    """
    Fix sayTable calls that use separate arrays instead of 2D array.

    Pattern: ctx.sayTable(headers, rows) -> ctx.sayTable(new String[][]{headers, rows})
    This is a common mistake since the DTR API expects String[][]
    """
    changes = 0

    # Find patterns like: ctx.sayTable(new String[]{"A","B"}, new String[]{"C","D"})
    pattern = r'ctx\.sayTable\s*\(\s*new\s+String\[\]\s*\{([^}]+)\}\s*,\s*new\s+String\[\]\s*\{([^}]+)\}\s*\)'

    def replace_func(match):
        nonlocal changes
        headers = match.group(1)
        rows = match.group(2)
        changes += 1
        # Create a proper 2D array initialization
        return f'ctx.sayTable(new String[][]{{{{{headers}}}, {{{rows}}}}})'

    content = re.sub(pattern, replace_func, content)

    return content, changes


def fix_say_ref_call(content: str) -> tuple[str, int]:
    """
    Fix sayRef calls that use 3-parameter version.

    Pattern: ctx.sayRef(Class, "anchor", "description") -> ctx.sayRef(Class, "anchor")
    """
    changes = 0

    # Find patterns like: ctx.sayRef(ProcTest.class, "anchor", "description")
    pattern = r'ctx\.sayRef\s*\(\s*([^,]+)\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)'

    def replace_func(match):
        nonlocal changes
        cls = match.group(1)
        anchor = match.group(2)
        # description = match.group(3)  # Not supported in current API
        changes += 1
        return f'ctx.sayRef({cls}, "{anchor}")'

    content = re.sub(pattern, replace_func, content)

    return content, changes


def remove_method_level_dtr_test(content: str) -> tuple[str, int]:
    """
    Remove method-level @DtrTest annotations (they should only be at class level).
    """
    changes = 0
    lines = content.split('\n')
    result = []
    i = 0

    while i < len(lines):
        line = lines[i]

        # Check if this is a method-level @DtrTest
        if re.match(r'\s*@\w+Test\s*$', line):
            # Look ahead to see if next line is a method declaration (not class declaration)
            if i + 1 < len(lines):
                next_line = lines[i + 1]
                # If next line is @Test or a method declaration (void return type), skip this @DtrTest
                if '@Test' in next_line or re.search(r'\s+(?:public|private|protected)?\s*\w+\s+\w+\s*\(', next_line):
                    changes += 1
                    i += 1
                    continue
                # If next line looks like a class/enum/interface declaration, keep it
                if re.search(r'\s+(class|enum|interface|@)\s+', next_line):
                    pass

        result.append(line)
        i += 1

    return '\n'.join(result), changes


def add_missing_dtr_import(content: str) -> tuple[str, int]:
    """Add missing DtrTest import if @DtrTest is used but not imported."""
    has_dtr_test_annotation = '@DtrTest' in content
    has_dtr_test_import = 'import io.github.seanchatmangpt.dtr.junit5.DtrTest;' in content

    if has_dtr_test_annotation and not has_dtr_test_import:
        # Find the right place to add the import
        lines = content.split('\n')
        result = []
        added = False

        for i, line in enumerate(lines):
            result.append(line)

            # Add import after package declaration
            if not added and line.startswith('package '):
                # Look for existing DTR imports to add near them
                j = i + 1
                while j < len(lines) and not lines[j].startswith('import ') and not lines[j].startswith('public') and not lines[j].startswith('@'):
                    j += 1

                # Check if there are DTR imports nearby
                if j < len(lines) and 'io.github.seanchatmangpt.dtr' in lines[j]:
                    # Add right before the first DTR import
                    result.append('import io.github.seanchatmangpt.dtr.junit5.DtrTest;')
                else:
                    # Add after the package line
                    result.append('')
                    result.append('import io.github.seanchatmangpt.dtr.junit5.DtrTest;')

                added = True

        if added:
            return '\n'.join(result), 1

    return content, 0


def fix_file(file_path: Path) -> int:
    """Fix all DTR issues in a single file."""
    print(f'Processing: {file_path.relative_to(Path("."))}')

    try:
        content = file_path.read_text()
        total_changes = 0

        # Apply all fixes
        content, changes1 = add_missing_dtr_import(content)
        total_changes += changes1

        content, changes2 = remove_method_level_dtr_test(content)
        total_changes += changes2

        content, changes3 = fix_say_table_call(content)
        total_changes += changes3

        content, changes4 = fix_say_ref_call(content)
        total_changes += changes4

        if total_changes > 0:
            file_path.write_text(content)
            print(f'  -> Fixed {total_changes} issues')
        else:
            print(f'  -> No changes needed')

        return total_changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        return 0


def main():
    """Fix DTR issues in all test files."""
    test_dir = Path('src/test/java')

    # Find all Java files with DTR usage
    test_files = []
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()
        if 'DtrContext' in content or 'DtrTest' in content or 'DtrExtension' in content:
            test_files.append(java_file)

    print(f'Found {len(test_files)} test files with DTR usage')

    total_changes = sum(fix_file(f) for f in test_files)
    print(f'\nTotal changes made: {total_changes}')

    # Run spotless to fix formatting
    print('\nRunning spotless:apply to fix formatting...')
    import subprocess
    result = subprocess.run(['./mvnw', 'spotless:apply'], capture_output=True, text=True)
    if result.returncode == 0:
        print('Spotless formatting completed successfully')
    else:
        print(f'Spotless had issues: {result.stderr}')


if __name__ == '__main__':
    main()
