#!/usr/bin/env python3
"""
Fix DTR imports - correct package names from io.github.seanchatmangpt.dtr to io.github.seanchatmangpt.dtr.junit5
"""

import re
from pathlib import Path

def fix_dtr_imports(content: str) -> tuple[str, int]:
    """Fix incorrect DTR imports"""
    changes = 0
    
    # Fix import statements
    content = re.sub(
        r'import io\.github\.seanchatmangpt\.dtr\.DtrContext;',
        'import io.github.seanchatmangpt.dtr.junit5.DtrContext;',
        content
    )
    content = re.sub(
        r'import io\.github\.seanchatmangpt\.dtr\.DtrTest;',
        'import io.github.seanchatmangpt.dtr.junit5.DtrTest;',
        content
    )
    
    # Count replacements
    changes = content.count('import io.github.seanchatmangpt.dtr.junit5.')
    
    return content, changes

def main():
    test_dir = Path('src/test/java')
    
    for java_file in test_dir.rglob('*.java'):
        content = java_file.read_text()
        
        # Only process files that need fixing
        if 'import io.github.seanchatmangpt.dtr.DtrContext;' in content or \
           'import io.github.seanchatmangpt.dtr.DtrTest;' in content:
            fixed_content, changes = fix_dtr_imports(content)
            
            if changes > 0:
                java_file.write_text(fixed_content)
                print(f'Fixed: {java_file.relative_to(Path("."))}')

if __name__ == '__main__':
    main()
