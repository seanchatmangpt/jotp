#!/usr/bin/env python3
"""
Convert ctx.sayTable(List.of(Map.of(...))) to ctx.sayTable(new String[][]{{...}}).

Handles multi-line patterns with newlines between List.of and Map.of.
"""

import re
import sys
from pathlib import Path


def convert_list_map_to_multiline(content: str) -> tuple[str, int]:
    """
    Convert sayTable with List.of(Map.of(...)) to proper 2D array format.

    This handles multi-line patterns like:
        ctx.sayTable(
            List.of(
                Map.of("k1", "v1", "k2", "v2", ...)))
    """
    changes = 0

    # Find all sayTable calls that contain List.of
    # We need to find the extent of each sayTable call and process it

    result = []
    i = 0
    content_len = len(content)

    while i < content_len:
        # Check if we're at a ctx.sayTable call
        if i + 10 <= content_len and content[i:i+10] == 'ctx.sayTable':
            # Find the matching closing parenthesis for sayTable
            start = i
            paren_count = 0
            in_parens = False
            j = i + 10  # Start after 'ctx.sayTable'

            # Find opening paren
            while j < content_len and content[j] != '(':
                j += 1

            if j >= content_len:
                result.append(content[i])
                i += 1
                continue

            paren_count = 1
            j += 1  # Skip opening paren

            # Find matching closing paren
            while j < content_len and paren_count > 0:
                if content[j] == '(':
                    paren_count += 1
                elif content[j] == ')':
                    paren_count -= 1
                j += 1

            call_content = content[i:j]

            # Check if this is the List.of(Map.of(...)) pattern
            if 'List.of' in call_content and 'Map.of' in call_content:
                # Extract all string literals from the Map.of call
                # Find Map.of(
                map_of_start = call_content.find('Map.of(')
                if map_of_start >= 0:
                    # Find the matching closing paren for Map.of
                    map_start = map_of_start + 7
                    paren_count = 1
                    k = map_start

                    while k < len(call_content) and paren_count > 0:
                        if call_content[k] == '(':
                            paren_count += 1
                        elif call_content[k] == ')':
                            paren_count -= 1
                        k += 1

                    map_args = call_content[map_start:k-1]

                    # Parse string literals from Map.of arguments
                    # Use regex to find all quoted strings
                    str_literals = re.findall(r'"([^"]*)"', map_args)

                    if len(str_literals) >= 2 and len(str_literals) % 2 == 0:
                        # Convert to proper 2D array format
                        # Map.of("k1", "v1a", "k2", "v2a") means:
                        # Row 1 (headers): k1, k2
                        # Row 2 (values): v1a, v2a

                        num_cols = len(str_literals) // 2
                        headers = str_literals[::2]
                        values = str_literals[1::2]

                        # Build 2D array rows
                        array_rows = []
                        array_rows.append('{' + ', '.join(f'"{h}"' for h in headers) + '}')
                        array_rows.append('{' + ', '.join(f'"{v}"' for v in values) + '}')

                        array_content = '{' + ', '.join(array_rows) + '}'
                        new_call = f'ctx.sayTable(new String[][]{array_content})'
                        result.append(new_call)
                        i = j
                        changes += 1
                        continue

        result.append(content[i])
        i += 1

    return ''.join(result), changes


def fix_file(file_path: Path) -> int:
    """Fix List.of(Map.of()) pattern in a single file."""
    try:
        content = file_path.read_text()

        content, changes = convert_list_map_to_multiline(content)

        if changes > 0:
            file_path.write_text(content)
            print(f'  -> Fixed {changes} occurrences')

        return changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        return 0


def main():
    """Fix all List.of(Map.of()) patterns in test files."""
    test_dir = Path('src/test/java')

    # Find files with both List.of and Map.of in sayTable context
    test_files = []
    for java_file in test_dir.rglob('*Test.java'):
        content = java_file.read_text()
        if 'ctx.sayTable' in content and 'List.of' in content and 'Map.of' in content:
            test_files.append(java_file)

    print(f'Found {len(test_files)} files with ctx.sayTable(List.of(Map.of(...))) pattern')

    total_changes = 0
    for java_file in test_files:
        print(f'Processing: {java_file.relative_to(Path("."))}')
        total_changes += fix_file(java_file)

    print(f'\nTotal conversions: {total_changes}')


if __name__ == '__main__':
    main()
