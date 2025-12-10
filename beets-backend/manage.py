import os
import subprocess
import sys
import shutil

def init_db():
    print("Initializing Beets database...")
    if not os.path.exists('music'):
        os.makedirs('music')
    # Create a dummy track for testing if it doesn't exist
    if not os.path.exists('music/dummy.mp3'):
        # In a real scenario we'd copy a file or generate silence
        # For now, let's just create an empty file masquerading as mp3
        # or better, valid silent mp3 if possible, but empty might fail metadata read.
        # Let's try to rely on import finding nothing or just setting up the struct.
        pass

    # Run beet import
    # This assumes 'beet' is in path
    cmd = ['beet', '-c', 'config.yaml', 'import', '-q', './music']
    subprocess.run(cmd)

def run_server():
    print("Starting Beets Web Server...")
    cmd = ['beet', '-c', 'config.yaml', 'web']
    subprocess.run(cmd)

if __name__ == '__main__':
    if len(sys.argv) > 1:
        if sys.argv[1] == 'init':
            init_db()
        elif sys.argv[1] == 'run':
            run_server()
        else:
            print("Usage: python manage.py [init|run]")
    else:
        run_server()
