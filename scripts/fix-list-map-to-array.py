#!/usr/bin/env python3
"""
Convert ctx.sayTable(List.of(Map.of(...))) to ctx.sayTable(new String[][]{{...}}).

The List.of(Map.of()) pattern doesn't work with sayTable() which expects String[][].
This script converts the pattern properly.
"""

import re
import sys
from pathlib import Path


def convert_list_map_to_array(content: str) -> tuple[str, int]:
    """
    Convert sayTable(List.of(Map.of(...))) to sayTable(new String[][]{{...}}).

    Pattern: ctx.sayTable(List.of(Map.of("k1", "v1", "k2", "v2", ...)))

    The Map.of() creates a single row with alternating key-value pairs.
    We need to convert this to a proper 2D array format.
    """
    changes = 0

    # Pattern to match: ctx.sayTable(List.of(Map.of(...)))
    # This is complex because we need to handle nested parentheses
    pattern = r'ctx\.sayTable\s*\(\s*List\.of\s*\(\s*Map\.of\s*\(([^)]+)\)\s*\)\s*\)'

    def replace_func(match):
        nonlocal changes
        args = match.group(1)

        # Parse the key-value pairs from Map.of()
        # Split by comma, but we need to handle quoted strings properly
        # For simplicity, we'll use a basic approach - split on ", " pattern

        # Get all the string literals from the arguments
        str_literals = re.findall(r'"([^"]*)"', args)

        if len(str_literals) % 2 != 0:
            # Odd number of strings - something is wrong, return original
            return match.group(0)

        # The first row contains the keys (headers)
        # Subsequent rows contain the values for each column
        # Actually, looking at the pattern, it seems like:
        # Row 1: [headers]
        # Row 2: [values for header 1]
        # Row 3: [values for header 2]
        # etc.

        # Actually, looking at the data more carefully:
        # Map.of("k1", "v1a", "k2", "v2a", "k3", "v3a") means:
        # Column 1: k1=v1a
        # Column 2: k2=v2a
        # Column 3: k3=v3a

        # So for sayTable, we want:
        # Row 1 (headers): k1, k2, k3
        # Row 2 (values): v1a, v2a, v3a

        headers = []
        values = []

        for i in range(0, len(str_literals), 2):
            headers.append(str_literals[i])
            values.append(str_literals[i + 1])

        # Build the 2D array representation
        rows = []
        rows.append('{' + ', '.join(f'"{h}"' for h in headers) + '}')
        rows.append('{' + ', '.join(f'"{v}"' for v in values) + '}')

        array_content = '{' + ', '.join(rows) + '}'
        changes += 1
        return f'ctx.sayTable(new String[][]{array_content})'

    # Use a more sophisticated approach - find and replace each match
    # We need to handle nested parentheses properly
    result = []
    i = 0

    while i < len(content):
        # Check if we're at a sayTable call
        if content[i:i+10] == 'ctx.sayTable':
            # Find the end of the call
            start = i
            paren_count = 0
            in_parens = False
            j = i

            while j < len(content):
                if content[j] == '(':
                    paren_count += 1
                    in_parens = True
                elif content[j] == ')':
                    paren_count -= 1
                    if in_parens and paren_count == 0:
                        break
                j += 1

            call_content = content[i:j+1]

            # Check if it's the List.of(Map.of(...)) pattern
            if 'List.of(Map.of(' in call_content:
                # Extract the Map.of arguments
                map_start = call_content.find('Map.of(') + 7
                map_end = map_start
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
                str_literals = re.findall(r'"([^"]*)"', map_args)

                if len(str_literals) >= 2 and len(str_literals) % 2 == 0:
                    # Build the 2D array
                    num_cols = len(str_literals) // 2
                    headers = str_literals[::2]
                    rows = [str_literals[i::2] for i in range(1, len(str_literals), 2)]

                    # Transpose rows to get proper table format
                    # Actually, looking at it, the format is:
                    # Map.of("k1", "v1a", "k2", "v2a") means row 1 is headers, row 2 is values

                    array_rows = []
                    array_rows.append('{' + ', '.join(f'"{h}"' for h in headers) + '}')

                    # Transpose: take value 1 from each pair, then value 2, etc.
                    # Actually this is a single row of values under the headers
                    for col_idx in range(num_cols):
                        array_rows.append('{' + f'"{rows[0][col_idx]}"' + '}')

                    # Wait, that's not right either. Let me look at actual data...
                    # Map.of("A", "B", "C", "D") means:
                    # Key A has value B, Key C has value D
                    # For a table, we want:
                    # Row 1: A, C (headers)
                    # Row 2: B, D (values)

                    num_headers = len(headers)
                    num_values = len(rows[0])

                    # Actually each key-value pair represents one column
                    # So we need to build rows by columns
                    array_rows = []
                    array_rows.append('{' + ', '.join(f'"{headers[c]}"' for c in range(num_cols)) + '}')
                    array_rows.append('{' + ', '.join(f'"{rows[0][c]}"' for c in range(num_cols)) + '}')

                    array_content = '{' + ', '.join(array_rows) + '}'
                    new_call = f'ctx.sayTable(new String[][]{array_content})'
                    result.append(new_call)
                    i = j + 1
                    changes += 1
                    continue

        result.append(content[i])
        i += 1

    return ''.join(result), changes


def fix_file(file_path: Path) -> int:
    """Fix List.of(Map.of()) pattern in a single file."""
    try:
        content = file_path.read_text()
        original = content

        content, changes = convert_list_map_to_array(content)

        if changes > 0:
            file_path.write_text(content)
            print(f'  -> Fixed {changes} List.of(Map.of()) patterns')

        return changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 0


def main():
    """Fix all List.of(Map.of()) patterns in test files."""
    test_dir = Path('src/test/java')

    # Find files with the pattern
    test_files = []
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()
        if 'List.of(Map.of(' in content:
            test_files.append(java_file)

    print(f'Found {len(test_files)} files with List.of(Map.of()) pattern')

    total_changes = 0
    for java_file in test_files:
        print(f'Processing: {java_file.relative_to(Path("."))}')
        total_changes += fix_file(java_file)

    print(f'\nTotal conversions: {total_changes}')


if __name__ == '__main__':
    main()
