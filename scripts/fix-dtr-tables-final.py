#!/usr/bin/env python3
"""
Convert ctx.sayTable(List.of(Map.of(...))) to ctx.sayTable(new String[][]{{...}}).

The Map.of() data is arranged column-by-column (column-major order).
Converts to row-major String[][] format.
"""

import re
import sys
from pathlib import Path


def convert_column_major_to_rows(str_literals: list[str], num_cols: int) -> list[list[str]]:
    """Convert column-major data to row-major table format."""
    num_rows = len(str_literals) // num_cols

    table = []
    for row_idx in range(num_rows):
        row = []
        for col_idx in range(num_cols):
            idx = col_idx * num_rows + row_idx
            if idx < len(str_literals):
                row.append(str_literals[idx])
            else:
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
                    # Extract Map.of values
                    map_of_start = call_content.find('Map.of(')
                    if map_of_start >= 0:
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
                        str_literals = re.findall(r'"([^"]*)"', map_args)

                        if str_literals:
                            # Determine number of columns
                            # Common patterns: 3, 4, 5, 6, 7, 8 columns
                            num_cols = None
                            for possible_cols in [3, 4, 5, 6, 7, 8, 9, 10]:
                                if len(str_literals) % possible_cols == 0:
                                    num_cols = possible_cols
                                    break

                            if num_cols is None:
                                # Use number of unique "header-like" strings
                                num_cols = 6  # Default

                            table = convert_column_major_to_rows(str_literals, num_cols)

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
