#!/usr/bin/env python3
"""
Convert ctx.sayTable(List.of(Map.of(...))) to ctx.sayTable(new String[][]{{...}}).

The Map.of() contains alternating values that form a table.
Example: Map.of("k1", "v1", "k2", "v2", "k3", "v3") becomes:
  Row 1: k1, k2, k3
  Row 2: v1, v2, v3
"""

import re
import sys
from pathlib import Path


def extract_map_of_values(call_content: str) -> list[str]:
    """Extract all string literal values from Map.of(...) call."""
    # Find Map.of(
    map_of_start = call_content.find('Map.of(')
    if map_of_start < 0:
        return []

    # Find the matching closing paren for Map.of
    map_start = map_of_start + 7
    paren_count = 1
    k = map_start
    content_len = len(call_content)

    while k < content_len and paren_count > 0:
        if call_content[k] == '(':
            paren_count += 1
        elif call_content[k] == ')':
            paren_count -= 1
        k += 1

    map_args = call_content[map_start:k-1]

    # Extract all string literals using regex
    str_literals = re.findall(r'"([^"]*)"', map_args)
    return str_literals


def convert_list_map_table(str_literals: list[str]) -> list[list[str]]:
    """
    Convert flat list of strings to 2D table.

    The format is:
    - First N strings are column headers
    - Next set of strings are values for row 2
    - Next set are values for row 3, etc.

    Based on the data pattern, it looks like:
    Row 1: headers (k1, k2, k3, ...)
    Row 2: values for column 1 (v1a, v1b, v1c, ...) - NO this doesn't match

    Actually looking at the actual data:
    "Aspect", "Exception-Based", "Railway-Oriented", "Error Propagation", ...

    This seems to represent columns and their values. Let me think...

    Looking at ResultRailwayTest:
    "Aspect", "Exception-Based", "Railway-Oriented",
    "Error Propagation", "Implicit...", "Explicit...",
    "Error Handling", "try-catch", "Pattern matching",
    ...

    This looks like:
    Column 1: Aspect -> Exception-Based, Railway-Oriented
    Column 2: Error Propagation -> Implicit..., Explicit...
    Column 3: Error Handling -> try-catch, Pattern matching

    So the format is:
    [header1, col1_val1, col1_val2, header2, col2_val1, col2_val2, ...]

    Wait, that doesn't work either because there are only 3 values per header group.

    Let me look at it differently. The actual table should be:
    | Aspect | Error Propagation | Error Handling | ... |
    |--------|-------------------|----------------|-----|
    | Exception-Based | Implicit... | try-catch | ... |
    | Railway-Oriented | Explicit... | Pattern matching | ... |

    So if we have 6 columns and 2 data rows (plus header):
    - First 6 strings: headers
    - Next 6 strings: row 2 values
    - Next 6 strings: row 3 values

    Total = 6 * 3 = 18 strings
    """
    if not str_literals:
        return []

    # Try different table layouts and pick the one that makes sense
    num_strings = len(str_literals)

    # Try layouts with different numbers of columns
    for num_cols in range(2, 10):
        if num_strings % num_cols == 0:
            num_rows = num_strings // num_cols
            if num_rows >= 2:  # At least header + 1 data row
                # This layout works
                table = []
                for row_idx in range(num_rows):
                    row = str_literals[row_idx * num_cols:(row_idx + 1) * num_cols]
                    table.append(row)
                return table

    # Fallback: assume 3 columns (common pattern)
    num_cols = 3
    table = []
    for row_idx in range((num_strings + num_cols - 1) // num_cols):
        start = row_idx * num_cols
        end = min(start + num_cols, num_strings)
        row = str_literals[start:end]
        # Pad if necessary
        while len(row) < num_cols:
            row.append("")
        table.append(row)

    return table


def fix_file(file_path: Path) -> int:
    """Fix List.of(Map.of()) pattern in a single file."""
    try:
        content = file_path.read_text()
        result = []
        i = 0
        content_len = len(content)
        changes = 0

        while i < content_len:
            # Check if we're at a ctx.sayTable call
            if i + 10 <= content_len and content[i:i+10] == 'ctx.sayTable':
                # Find the matching closing parenthesis for sayTable
                paren_count = 0
                j = i + 10

                # Find opening paren
                while j < content_len and content[j] != '(':
                    j += 1

                if j >= content_len:
                    result.append(content[i])
                    i += 1
                    continue

                paren_count = 1
                j += 1

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
                    str_literals = extract_map_of_values(call_content)

                    if str_literals:
                        table = convert_list_map_table(str_literals)

                        if table and len(table) >= 2:
                            # Build the 2D array representation
                            array_rows = []
                            for row in table:
                                row_str = '{' + ', '.join(f'"{v}"' for v in row) + '}'
                                array_rows.append(row_str)

                            array_content = '{' + ', '.join(array_rows) + '}'
                            new_call = f'ctx.sayTable(new String[][]{array_content})'
                            result.append(new_call)
                            i = j
                            changes += 1
                            continue

            result.append(content[i])
            i += 1

        if changes > 0:
            new_content = ''.join(result)
            file_path.write_text(new_content)

        return changes

    except Exception as e:
        print(f'  -> ERROR: {e}', file=sys.stderr)
        import traceback
        traceback.print_exc()
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
        changes = fix_file(java_file)
        if changes > 0:
            print(f'  -> Fixed {changes} occurrences')
            total_changes += changes

    print(f'\nTotal conversions: {total_changes}')


if __name__ == '__main__':
    main()
