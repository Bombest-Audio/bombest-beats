#!/usr/bin/env python3
"""
Integrate new playlist endpoints into upload_server.py
Inserts endpoints before the if __name__ == '__main__': block
"""

def integrate_endpoints():
    # Read main server file
    with open('upload_server.py', 'r') as f:
        lines = f.readlines()
    
    # Find the if __name__ line
    main_line_idx = None
    for i, line in enumerate(lines):
        if line.strip().startswith("if __name__ == '__main__':"):
            main_line_idx = i
            break
    
    if main_line_idx is None:
        print("Error: Could not find if __name__ == '__main__': line")
        return False
    
    # Read new endpoints (skip first 3 comment lines)
    with open('new_playlist_endpoints.py', 'r') as f:
        endpoint_lines = f.readlines()[3:]  # Skip header comments
    
    # Insert before if __name__
    new_lines = lines[:main_line_idx] + endpoint_lines + ['\n'] + lines[main_line_idx:]
    
    # Write back
    with open('upload_server.py', 'w') as f:
        f.writelines(new_lines)
    
    print(f"✅ Inserted {len(endpoint_lines)} lines of endpoints before line {main_line_idx + 1}")
    print(f"✅ New total lines: {len(new_lines)}")
    return True

if __name__ == '__main__':
    import os
    os.chdir('/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend')
    success = integrate_endpoints()
    exit(0 if success else 1)
