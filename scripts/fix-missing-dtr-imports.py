#!/usr/bin/env python3
"""
Add missing DTR imports for DocSection and DocDescription
"""

import re
from pathlib import Path

def fix_dtr_doc_imports(content: str) -> tuple[str, int]:
    """Add missing DTR documentation imports"""
    changes = 0
    
    # Check if file uses @DocSection or @DocDescription annotations
    has_doc_section = '@DocSection' in content or 'DocSection' in content
    has_doc_description = '@DocDescription' in content or 'DocDescription' in content
    has_dtr_context = 'DtrContext' in content
    
    if (has_doc_section or has_doc_description) and has_dtr_context:
        # Check if the imports are already present
        has_doc_section_import = 'import io.github.seanchatmangpt.dtr.DocSection;' in content
        has_doc_description_import = 'import io.github.seanchatmangpt.dtr.DocDescription;' in content
        
        lines = content.split('\n')
        result = []
        added_section = False
        added_description = False
        
        for i, line in enumerate(lines):
            result.append(line)
            
            # Add imports after package declaration
            if not added_section and has_doc_section and line.startswith('package '):
                # Find the next import statement
                for j in range(i+1, min(i+20, len(lines))):
                    if lines[j].startswith('import '):
                        # Insert before this import
                        if 'import io.github.seanchatmangpt.dtr.DocSection;' not in '\n'.join(lines[:j]):
                            result.insert(-1, 'import io.github.seanchatmangpt.dtr.DocSection;')
                            added_section = True
                            changes += 1
                        break
                added_section = True  # Only try once
            
            # Similar for DocDescription
            if not added_description and has_doc_description and line.startswith('package '):
                for j in range(i+1, min(i+20, len(lines))):
                    if lines[j].startswith('import '):
                        if 'import io.github.seanchatmangpt.dtr.DocDescription;' not in '\n'.join(lines[:j]):
                            result.insert(-1, 'import io.github.seanchatmangpt.dtr.DocDescription;')
                            added_description = True
                            changes += 1
                        break
                added_description = True
        
        return '\n'.join(result), changes
    
    return content, changes

def main():
    test_dir = Path('src/test/java')
    
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()
        fixed_content, changes = fix_dtr_doc_imports(content)
        
        if changes > 0:
            java_file.write_text(fixed_content)
            print(f'Added doc imports to: {java_file.relative_to(Path("."))}')

if __name__ == '__main__':
    main()
