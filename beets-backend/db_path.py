"""Resolve users.db path. Used by init_db, make_admin, upload_server."""
import os


def get_users_db_path():
    data_dir = os.environ.get('DATA_DIR')
    if data_dir:
        return os.path.join(data_dir, 'users.db')
    return os.path.join(os.path.dirname(__file__), 'music', 'users.db')
