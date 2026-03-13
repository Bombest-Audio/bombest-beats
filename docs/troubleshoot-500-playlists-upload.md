# Troubleshoot 500 on /playlists and Upload Errors

If the Upload screen shows "SERVER RETURNED AN ERROR PAGE" and the console shows:

- **500 Internal Server Error** on `https://beats.bom.best/playlists`
- **404** on `https://beats.bom.best/upload/folder`
- **SyntaxError: Unexpected token '<'** on jsmediatags (HTML returned instead of JS)

## 500 on /playlists

**Cause:** Database schema mismatch — the `playlists` table was created by an older `init_db.py` without `is_system`, `art_path`, etc. The backend crashes when querying these columns.

**Fix:** Two changes were made:

1. **upload_server.py** — Added migration to create `is_system` if missing (older DBs).
2. **init_db.py** — Updated schema for new installs.

**Apply the fix:** Redeploy the backend so EC2 runs the updated code:

```bash
./deploy-to-ec2.sh
```

If the container was started before migrations ran, the first request after deploy will add the missing columns and succeeding requests should work.

## 404 on /upload/folder

If `POST https://beats.bom.best/upload/folder` returns 404, traffic may not be reaching EC2. See [troubleshoot-404-upload.md](troubleshoot-404-upload.md).

## jsmediatags SyntaxError

`Unexpected token '<'` usually means the server returned an HTML error page instead of JavaScript. Common causes:

- A script URL 404s and CloudFront/S3 returns an HTML error page
- CORS or routing issues

Often resolves after fixing the 500 on /playlists. If it persists, check the Network tab for which exact URL returns HTML.

## Large folder upload times out (ERR_TIMED_OUT)

**Cause:** Cloudflare's **Proxy Write Timeout is 30 seconds** (not configurable). Each batch of files must upload and complete within that limit. Large batches (6+ files) can exceed it.

**Fix:** The frontend batches folder uploads in groups of 3 files (reduced from 6). Redeploy the frontend:

```bash
./scripts/deploy-frontend.sh
```

If timeouts persist with large files, try uploading in smaller batches (e.g. split your folder) or use a zip file (one request for the whole archive).

## Verify backend after fix

```bash
# Should return 200 (or 401 if auth required)
curl -s -o /dev/null -w "%{http_code}" https://beats.bom.best/library

# Playlists (may need auth cookie)
curl -s -o /dev/null -w "%{http_code}" https://beats.bom.best/playlists
```
