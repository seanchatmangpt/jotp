#!/usr/bin/env python3
"""
Fix all remaining DTR API issues in test files.

1. Change sayBenchmark() to sayTable()
2. Fix sayTable() calls to use 2D array format
3. Add missing imports (List, Map, TimeoutException)
4. Fix Map.of() calls with more than 10 pairs (split into multiple calls)
"""

import re
import sys
from pathlib import Path


def fix_say_benchmark_to_table(content: str) -> tuple[str, int]:
    """Change ctx.sayBenchmark() to ctx.sayTable()."""
    changes = 0

    # Simple replacement of method name
    content, count = re.subn(r'\bctx\.sayBenchmark\s*\(', 'ctx.sayTable(', content)
    changes += count

    return content, changes


def fix_say_table_two_param(content: str) -> tuple[str, int]:
    """
    Fix sayTable calls using the old 2-parameter format.

    Pattern: ctx.sayTable(new String[]{"A","B"}, new String[][]{{"C"},{"D"}})
    To: ctx.sayTable(new String[][]{{"A","B"},{"C"},{"D"}})
    """
    changes = 0

    # Pattern: ctx.sayTable(new String[]{...}, new String[][]{...})
    pattern = r'ctx\.sayTable\s*\(\s*new\s+String\[\]\s*\{([^}]+)\}\s*,\s*new\s+String\[\]\[\]\s*\{([^}]+)\}\s*\)'

    def replace_func(match):
        nonlocal changes
        headers = match.group(1)
        rows = match.group(2)
        changes += 1
        # Reconstruct as proper 2D array with headers as first row
        return f'ctx.sayTable(new String[][]{{{{{headers}}}, {rows}}})'

    content = re.sub(pattern, replace_func, content)

    return content, changes


def add_missing_imports(content: str) -> tuple[str, int]:
    """Add missing imports for List, Map, etc. in DTR test files."""
    lines = content.split('\n')
    result = []
    imports_to_add = []
    i = 0

    # Scan for usage of types without imports
    has_list_usage = 'List.of(' in content or 'List<' in content
    has_map_usage = 'Map.of(' in content or 'Map<' in content
    has_timeout_exception = 'TimeoutException' in content

    has_list_import = any('import java.util.List;' in line or 'import java.util.*;' in line for line in lines)
    has_map_import = any('import java.util.Map;' in line or 'import java.util.*;' in line for line in lines)
    has_timeout_import = any('import java.util.concurrent.TimeoutException;' in line for line in lines)

    if has_list_usage and not has_list_import:
        imports_to_add.append('import java.util.List;')
    if has_map_usage and not has_map_import:
        imports_to_add.append('import java.util.Map;')
    if has_timeout_exception and not has_timeout_import:
        imports_to_add.append('import java.util.concurrent.TimeoutException;')

    if not imports_to_add:
        return content, 0

    # Find the right place to add imports (after package, before class)
    added = False
    for i, line in enumerate(lines):
        result.append(line)

        if not added and line.startswith('package '):
            # Find where the imports block ends
            j = i + 1
            while j < len(lines) and (lines[j].startswith('import ') or not lines[j].strip() or lines[j].startswith('/*') or lines[j].startswith('*')):
                j += 1

            # Insert new imports before the first non-import/blank line
            for imp in imports_to_add:
                result.insert(j, imp)
                j += 1
            added = True

    return '\n'.join(result), len(imports_to_add)


def fix_map_of_over_10_pairs(content: str) -> tuple[str, int]:
    """
    Fix Map.of() calls with more than 10 key-value pairs.

    Java's Map.of() only supports up to 10 pairs. For more, we need to use
    Map.ofEntries() or stream collectors.

    For simplicity in tests, we'll convert to using a stream/collector approach
    or split into multiple calls. Here we convert to the new Java 26 Map.entries()
    approach if available, or use a simpler workaround.
    """
    changes = 0

    # This is complex - for now, we'll just note the files that need manual fixes
    # The actual fix requires understanding the context and may need different approaches

    # Find files with Map.of() calls that have >10 pairs
    # Pattern: Map.of("key1", "value1", "key2", "value2", ... ) with 20+ arguments
    if 'Map.of(' in content:
        # Count pairs - each pair is 2 args
        # This is a simple heuristic check
        for match in re.finditer(r'Map\.of\([^)]+\)', content):
            args = match.group(0)
            # Count commas to estimate arguments
            comma_count = args.count(',')
            if comma_count > 20:  # More than 10 pairs
                changes += 1
                # For now, just flag it - manual fix needed

    return content, changes


def fix_file(file_path: Path) -> int:
    """Fix all DTR API issues in a single file."""
    try:
        content = file_path.read_text()
        total_changes = 0

        # Apply fixes
        content, changes1 = fix_say_benchmark_to_table(content)
        total_changes += changes1

        content, changes2 = fix_say_table_two_param(content)
        total_changes += changes2

        content, changes3 = add_missing_imports(content)
        total_changes += changes3

        if total_changes > 0:
            file_path.write_text(content)
            print(f'  -> Fixed {total_changes} issues')

        return total_changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        return 0


def main():
    """Fix all DTR issues in test files."""
    test_dir = Path('src/test/java')

    # Find all Java test files
    test_files = []
    for java_file in test_dir.rglob('*Test.java'):
        test_files.append(java_file)

    print(f'Found {len(test_files)} test files')

    total_changes = sum(fix_file(f) for f in test_files)
    print(f'\nTotal fixes applied: {total_changes}')

    # Check for Map.of() over 10 pairs issues
    print('\nChecking for Map.of() calls with >10 pairs (may need manual fixes)...')
    for java_file in test_files:
        content = java_file.read_text()
        for match in re.finditer(r'Map\.of\([^)]+\)', content):
            args = match.group(0)
            comma_count = args.count(',')
            if comma_count > 20:  # More than 10 pairs
                print(f'  {java_file.relative_to(Path("."))}:{match.start()}')


if __name__ == '__main__':
    main()
