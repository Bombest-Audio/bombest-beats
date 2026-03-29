# Codebase Concerns

**Analysis Date:** 2026-03-29

## Tech Debt

**Monolithic Backend File (Critical):**
- Issue: The entire backend API lives in a single 3,523-line file `beets-backend/upload_server.py` with 60+ route handlers. No separation of concerns (auth, playlists, uploads, streaming, metrics all in one file).
- Files: `beets-backend/upload_server.py`
- Impact: Extremely difficult to review, test, or modify safely. Any change risks unintended side effects. High bus factor risk.
- Fix approach: Extract into a Flask Blueprint structure: `routes/auth.py`, `routes/playlists.py`, `routes/upload.py`, `routes/streaming.py`, `routes/metrics.py`, with shared `db.py` and `helpers.py` modules.

**Duplicate LIBRARY_DB Assignment:**
- Issue: `LIBRARY_DB` is assigned on line 105 and then reassigned on line 1683 to a functionally identical value. This is confusing and error-prone.
- Files: `beets-backend/upload_server.py:105`, `beets-backend/upload_server.py:1683`
- Impact: If `os.getcwd()` differs from `MUSIC_FOLDER` base path, routes above line 1683 and below it would use different databases. Currently they resolve to the same path, but this is fragile.
- Fix approach: Remove the duplicate assignment on line 1683.

**Duplicate `import base64` Statements:**
- Issue: `import base64` appears twice (line 1967 and line 1989), and `from urllib.parse import urlparse` is imported mid-file (line 1990) instead of at the top.
- Files: `beets-backend/upload_server.py:1967`, `beets-backend/upload_server.py:1989`
- Impact: Minor code quality issue but indicates rushed, append-only development.
- Fix approach: Consolidate all imports at the top of the file.

**FIXME Comments (Incomplete Authorization Logic):**
- Issue: Two FIXME comments indicate that admin users cannot delete loops or comments owned by other users. The `# And not admin FIXME` comments show this was a known gap left unresolved.
- Files: `beets-backend/upload_server.py:1671`, `beets-backend/upload_server.py:1809`
- Impact: Admins cannot moderate content (delete inappropriate comments or loops). Only the original creator can delete.
- Fix approach: Add admin role check alongside ownership check: `if row[0] != current_user_id and user_role != 'admin':`.

**Lazy Table Migrations at Runtime:**
- Issue: Multiple endpoints create tables or add columns on every request (e.g., `CREATE TABLE IF NOT EXISTS plays` in `/metrics/play`, `PRAGMA table_info` + `ALTER TABLE` in `/playlists`). This adds latency and is a runtime migration antipattern.
- Files: `beets-backend/upload_server.py:2416-2430` (playlists GET), `beets-backend/upload_server.py:3388` (plays), `beets-backend/upload_server.py:3444` (plays), `beets-backend/upload_server.py:1856` (invites)
- Impact: Every playlist GET request runs 3 PRAGMA checks and potential ALTER TABLE statements. Wastes CPU and risks race conditions on concurrent requests.
- Fix approach: Run all migrations once at startup in `init_db.py`. Remove runtime migration code from route handlers.

**No Test Suite:**
- Issue: Zero application-level tests exist. No unit tests, integration tests, or end-to-end tests for the backend, frontend, or Android app. CI only validates that the code compiles/imports.
- Files: `.github/workflows/pre-merge.yml` (CI runs `assembleDebug`, `python -c "import upload_server"`, `npm run build`)
- Impact: No regression protection. Any change could break functionality silently. Refactoring the monolith is extremely risky without tests.
- Fix approach: Start with integration tests for critical API endpoints (auth, library, upload, streaming) using pytest + Flask test client.

**Hardcoded Artist Name:**
- Issue: The artist name `'thomas phillips'` is hardcoded in multiple places throughout the upload pipeline instead of being configurable or derived from metadata.
- Files: `beets-backend/upload_server.py:233` (fallback lookup), `beets-backend/upload_server.py:296` (set_audio_title), `beets-backend/upload_server.py:514` (post-import update), `beets-backend/upload_server.py:788` (_import_from_directory)
- Impact: The platform cannot be used by other artists without code changes.
- Fix approach: Make the default artist configurable via `config.yaml` or accept it from the upload request.

**Bare `except:` in Script:**
- Issue: `beets-backend/upload_album.py:106` uses bare `except:` which catches all exceptions including `SystemExit` and `KeyboardInterrupt`.
- Files: `beets-backend/upload_album.py:106`
- Impact: Violates Python best practices. Can mask real errors.
- Fix approach: Change to `except OSError:` or at minimum `except Exception:`.

**Broad Exception Handling Throughout:**
- Issue: 68 instances of `except Exception as e:` and 3 instances of `except Exception:` (no variable capture) in `upload_server.py` alone. While better than bare `except`, this catches too broadly in many cases.
- Files: `beets-backend/upload_server.py` (68+ locations)
- Impact: Masks specific errors. Makes debugging harder. Some handlers silently swallow errors with only a print statement.
- Fix approach: Catch specific exceptions (e.g., `sqlite3.OperationalError`, `ClientError`, `FileNotFoundError`) where the failure mode is known.

**Uses `print()` Instead of `logging`:**
- Issue: 41 `print()` calls throughout the backend with zero `logging` module usage. No log levels, no structured logging, no log rotation.
- Files: `beets-backend/upload_server.py` (41 print statements)
- Impact: No way to filter log severity. Emoji in print statements (`✅`, `❌`) makes log parsing difficult. Production debugging is hampered.
- Fix approach: Replace all `print()` with `logging.info()`, `logging.error()`, etc. Configure a proper logging handler.

## Security Concerns

**Hardcoded Invite Codes in Source:**
- Risk: Two hardcoded invite codes (`'whatupdoe'` and `'bombest-admin-2025'`) are in the source code. The admin code grants admin privileges to anyone who registers with it.
- Files: `beets-backend/upload_server.py:1867`, `beets-backend/upload_server.py:1883`
- Current mitigation: None. Anyone with access to the code (or who guesses these strings) can create admin accounts.
- Recommendations: Remove hardcoded invite codes immediately. All invite codes should come from the `invites` table only. Rotate the admin password and revoke any accounts created with the hardcoded admin code.

**Hardcoded Default Admin Password:**
- Risk: `init_db.py` creates an admin user with password `"admin_password"` and prints it to stdout. The comment says "Change this immediately!" but nothing enforces it.
- Files: `beets-backend/init_db.py:130`
- Current mitigation: Only runs if admin user doesn't exist yet.
- Recommendations: Generate a random password at init time, or require password to be set via environment variable.

**JWT Tokens Never Expire:**
- Risk: `JWT_ACCESS_TOKEN_EXPIRES = False` means tokens are valid forever. A leaked token grants permanent access.
- Files: `beets-backend/upload_server.py:41`
- Current mitigation: None. Comment says "for simplicity in MVP."
- Recommendations: Set token expiration (e.g., 24 hours) and implement refresh token flow.

**JWT Secret Fallback to Hardcoded Value:**
- Risk: If `jwt_secret` is missing from `config.yaml`, the JWT secret defaults to `'dev-secret-key'`. This is a weak, predictable secret.
- Files: `beets-backend/upload_server.py:40`
- Current mitigation: `config.yaml` should contain a real secret, but there is no validation that it was changed.
- Recommendations: Fail fast if JWT secret is the default value. Require it via environment variable.

**Email Credentials in config.yaml (Committed to Git):**
- Risk: `config.yaml` and `config.docker.yaml` contain placeholder SMTP credentials (`sender_email`, `password` fields). While currently set to placeholder values, the file structure encourages putting real credentials here, and the file is not in `.gitignore`.
- Files: `beets-backend/config.yaml:10-15`, `beets-backend/config.docker.yaml:10-15`
- Current mitigation: Placeholder values currently. But if a developer puts real credentials here, they'd be committed.
- Recommendations: Move email credentials to environment variables. Add `config.yaml` to `.gitignore` and use `config.yaml.example` as template.

**CORS Wildcard in Beets Web Config:**
- Risk: `config.yaml` and `config.docker.yaml` set `cors: '*'` for the beets web plugin, allowing any origin.
- Files: `beets-backend/config.yaml:8`, `beets-backend/config.docker.yaml:8`
- Current mitigation: The beets web server (port 8337) is not directly exposed; Flask on 8338 is the public API with proper CORS.
- Recommendations: Restrict the beets web CORS to known origins, or ensure port 8337 is not exposed in production.

**Multiple Unauthenticated Endpoints with Write Access:**
- Risk: Several endpoints that modify data have no authentication:
  - `GET /playlists` (line 2409) - no auth, lists all playlists
  - `POST /playlists` (line 2448) - no auth, anyone can create playlists
  - `PUT /playlists/<id>` (line 2477) - no auth, anyone can rename any playlist
  - `DELETE /playlists/<id>` (line 2496) - no auth, anyone can delete any playlist
  - `PUT /tracks/reorder` (line 1404) - no auth, anyone can reorder tracks
  - `GET /library` (line 1504) - no auth, exposes entire library
  - `GET /stream/<id>` (line 2951) - no auth, anyone can stream any track
  - `DELETE /tracks/batch` (line 1428) - no auth, anyone can batch delete tracks
- Files: `beets-backend/upload_server.py` (lines referenced above)
- Current mitigation: Cloudflare sits in front, but this only provides DDoS protection, not auth.
- Recommendations: Add `@jwt_required()` to all data-modifying endpoints. For read endpoints like `/library` and `/stream`, decide on public vs. authenticated access policy.

**No Rate Limiting:**
- Risk: No rate limiting on any endpoint, including auth endpoints (`/auth/login`, `/auth/register`). Susceptible to brute-force password attacks and resource exhaustion.
- Files: Entire backend (`beets-backend/upload_server.py`)
- Current mitigation: Cloudflare may provide some DDoS protection.
- Recommendations: Add Flask-Limiter or similar. At minimum, rate-limit `/auth/login` (e.g., 5 attempts per minute per IP).

**No Input Validation/Sanitization:**
- Risk: User-supplied data (playlist names, lyrics content, comment content) is stored directly without sanitization. No length limits on text fields beyond SQL storage limits.
- Files: `beets-backend/upload_server.py:2452-2468` (playlist creation), `beets-backend/upload_server.py:1714-1740` (lyrics), `beets-backend/upload_server.py:1770-1793` (comments)
- Current mitigation: Parameterized SQL queries prevent SQL injection. But there is no XSS protection or content length validation.
- Recommendations: Add input length limits. Sanitize text content for any HTML/script injection if ever rendered in a web context.

**Docker Container Runs as Root:**
- Risk: The `Dockerfile` does not specify a non-root user. The Flask app runs as root inside the container.
- Files: `beets-backend/Dockerfile`
- Current mitigation: None.
- Recommendations: Add `RUN useradd -m appuser` and `USER appuser` to the Dockerfile.

**Path Traversal Risk in Stream Endpoint:**
- Risk: The stream endpoint at line 2951 retrieves file paths from the database and serves them directly via `send_file()`. A comment on line 2968 says "Security check: Ensure file is within music directory? For now, trust the DB as it's internal." If the database is compromised, arbitrary files could be served.
- Files: `beets-backend/upload_server.py:2966-3011`
- Current mitigation: Database is trusted. Path comes from beets import.
- Recommendations: Add path validation to ensure the resolved path is within the music directory.

## Performance Concerns

**SQLite Connection-Per-Request Pattern:**
- Problem: Every route handler opens a new `sqlite3.connect()` call, does work, and closes. There are 87 `sqlite3.connect()` calls and only some use the `_get_db()` helper (which sets WAL mode and busy timeout). Many connect directly without WAL mode.
- Files: `beets-backend/upload_server.py` (87 `sqlite3.connect()` calls)
- Cause: No connection pooling. Inconsistent use of the `_get_db()` helper (only ~5 calls use it vs. ~82 direct `sqlite3.connect()`).
- Improvement path: Use the `_get_db()` helper consistently for all connections. Consider Flask's `g` object for request-scoped connections.

**N+1 Query in Playlist Listing:**
- Problem: `/playlists` GET fetches all playlists, then loops over each to execute a separate `SELECT COUNT(*)` query for track counts.
- Files: `beets-backend/upload_server.py:2440-2442`
- Cause: No JOIN or subquery used.
- Improvement path: Use `SELECT p.*, COUNT(pt.track_id) FROM playlists p LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id GROUP BY p.id`.

**N+1 Query in Dashboard Metrics:**
- Problem: `/metrics/dashboard` fetches top track IDs, then loops over each to query metadata from a different database file.
- Files: `beets-backend/upload_server.py:3469-3475`
- Cause: Data split across two SQLite databases (`users.db` for plays, `library.db` for metadata). Cannot join across databases without ATTACH.
- Improvement path: Use SQLite `ATTACH DATABASE` to join across files, or batch the library lookups into a single `WHERE id IN (...)` query.

**In-Memory State (Process Jobs and Sessions):**
- Problem: `_presign_sessions` (line 898), `_process_jobs` (line 905), and `passkey_challenges` (line 2022) are all Python dicts stored in process memory. They are lost on restart and incompatible with multi-worker deployment.
- Files: `beets-backend/upload_server.py:898`, `beets-backend/upload_server.py:905`, `beets-backend/upload_server.py:2022`
- Cause: MVP approach; comments acknowledge this (line 2020: "in production, use Redis or similar").
- Improvement path: Move to Redis or SQLite-backed session storage. At minimum, document the single-worker constraint.

**Full Library Loaded in Memory:**
- Problem: `/library` loads all tracks from the database into memory and transforms them into JSON. No pagination support.
- Files: `beets-backend/upload_server.py:1504-1552`
- Cause: No limit/offset parameters.
- Improvement path: Add pagination parameters (`?page=1&limit=50`). Current library is small enough that this works, but will not scale.

**Waveform/Beat Generation Blocks Request:**
- Problem: `/waveform/<id>` and `/track/<id>/beats` compute waveform peaks and beat detection synchronously on first request. `librosa.load()` for beat detection is CPU-intensive.
- Files: `beets-backend/upload_server.py:375-406` (waveform), `beets-backend/upload_server.py:409-460` (beats)
- Cause: No background processing; results are cached on disk after first computation.
- Improvement path: Pre-compute waveforms and beats during the import pipeline, or move to a background task queue.

## Scalability Concerns

**Single-Process Flask Server:**
- Current capacity: Single worker process, single thread (plus daemon threads for async uploads).
- Limit: Cannot handle concurrent requests efficiently. Background threads (line 1062) share GIL with request handlers. No WSGI server (gunicorn/uwsgi) configured.
- Files: `beets-backend/upload_server.py:3523` (`app.run()` with Flask dev server)
- Scaling path: Deploy behind gunicorn with multiple workers. But in-memory state (see above) would need to be externalized first.

**SQLite as Production Database:**
- Current capacity: Works well for single-user or low-concurrency read workloads.
- Limit: SQLite write operations are serialized. Concurrent writes from multiple workers would cause `SQLITE_BUSY` errors. WAL mode helps but doesn't solve multi-process contention.
- Files: Two databases: `library.db` (beets-managed), `users.db` (app data)
- Scaling path: Migrate `users.db` to PostgreSQL for concurrent write support. `library.db` is tightly coupled to beets and harder to migrate.

**Single EC2 Instance:**
- Current capacity: One EC2 instance behind Cloudflare.
- Limit: Single point of failure. No auto-scaling. Server restart loses in-memory state (upload sessions, passkey challenges).
- Scaling path: Add health checks, auto-recovery. For horizontal scaling, externalize state to Redis/PostgreSQL.

**500MB Upload Limit with In-Memory Buffering:**
- Current capacity: `MAX_CONTENT_LENGTH = 500 * 1024 * 1024` (500 MB).
- Limit: Large uploads consume server memory. Direct uploads go through Flask's request handling which buffers in memory.
- Files: `beets-backend/upload_server.py:119`, `nginx-ec2.conf:14` (`client_max_body_size 500M`)
- Scaling path: The presigned S3 URL flow (line 928) bypasses this for large files. Consider making it the only upload method.

## Maintenance Concerns

**Forked Frontend with Upstream Attribution:**
- Issue: The frontend `package.json` still references the original upstream project (`AKAspanion/music-app`) in its `repository`, `bugs`, and `author` fields. This is a forked project with significant modifications.
- Files: `music-frontend/package.json:72-79`
- Impact: Confusing for contributors. Bug reports would go to the wrong place.
- Fix approach: Update metadata to reflect the Bombest Beats project.

**Stale Utility Scripts:**
- Issue: Several one-off scripts with hardcoded paths exist in the backend: `upload_album.py` (hardcoded to a specific album), `reimport_as_album.py`, `cleanup_all_tracks.py`, `find_and_remove_duplicate_tracks.py`.
- Files: `beets-backend/upload_album.py`, `beets-backend/reimport_as_album.py`, `beets-backend/cleanup_all_tracks.py`, `beets-backend/find_and_remove_duplicate_tracks.py`
- Impact: Clutter. Hardcoded paths (e.g., `upload_album.py:26-28`) are specific to one developer's machine.
- Fix approach: Move to a `scripts/` directory or remove if functionality is now in the API.

**config.yaml Not Gitignored:**
- Issue: `config.yaml` contains environment-specific paths and placeholder credentials but is committed to git. `config.docker.yaml` is also committed.
- Files: `beets-backend/config.yaml`, `beets-backend/config.docker.yaml`
- Impact: Any developer who puts real credentials in `config.yaml` would commit them.
- Fix approach: Add `beets-backend/config.yaml` to `.gitignore`. Provide `config.yaml.example`. Use environment variables for secrets.

**Large Committed Files in Root:**
- Issue: Several large files are committed to the repository root that should not be: `android_debug.log` (1.6MB), `cloudflare.log` (140KB), `upload_server.log` (89KB), `graffitti-bomb.png` (1.9MB), `graffitti-icon.png` (1.2MB), `no-image.png` (1.7MB), `tuist_build.log` (20KB), `server.log`.
- Files: Root directory log and image files
- Impact: Bloats repository size. Log files may contain sensitive information.
- Fix approach: Remove log files from tracking. Move image assets to a dedicated assets directory or S3. Update `.gitignore`.

## Dependency Risks

**Outdated React Stack:**
- Risk: React 17 (current: 19), TypeScript 4.0 (current: 5.x), react-scripts 4.0 (effectively EOL). Requires `--openssl-legacy-provider` flag to build, indicating OpenSSL compatibility issues with the old webpack version.
- Files: `music-frontend/package.json:18-24`, `music-frontend/package.json:41`
- Impact: No access to React 18+ features (Suspense, Concurrent Mode, Server Components). The `--openssl-legacy-provider` workaround will eventually break.
- Migration plan: Upgrade to React 18+, TypeScript 5, and replace react-scripts with Vite.

**Workbox 5 (Service Worker):**
- Risk: 11 workbox dependencies pinned to `^5.1.3` (current: 7.x). Major versions behind.
- Files: `music-frontend/package.json:27-38`
- Impact: Missing security patches and performance improvements in service worker caching.
- Migration plan: Upgrade workbox packages to v7 along with the React/build tooling upgrade.

**Python Dependencies Loosely Pinned:**
- Risk: Several dependencies use `>=` instead of `==` or `~=`: `requests>=2.31.0`, `numpy>=1.24.4`, `librosa>=0.10.0`, `webauthn>=2.0.0`, `boto3>=1.34.0`. This means builds are not reproducible.
- Files: `beets-backend/requirements.txt:8,19,20,27,28`
- Impact: Different deployments could get different versions. A breaking change in a new release could silently break the app.
- Migration plan: Pin all dependencies to exact versions (`==`). Use `pip freeze` to capture current working versions.

**No requirements.txt Lockfile:**
- Risk: No `requirements.lock` or similar lockfile exists. `pip install -r requirements.txt` resolves transitive dependencies at install time.
- Files: `beets-backend/requirements.txt`
- Impact: Builds are not reproducible across environments.
- Migration plan: Generate a lockfile with `pip-compile` (pip-tools) or migrate to Poetry/PDM.

## Test Coverage Gaps

**Backend: Zero Tests:**
- What's not tested: All 60+ API endpoints including auth, upload, streaming, playlists, metrics.
- Files: `beets-backend/` (no test files exist)
- Risk: Any refactoring (especially of the monolithic `upload_server.py`) could break functionality silently.
- Priority: **High** - This is the most critical gap. Start with auth endpoints and the upload pipeline.

**Frontend: Zero Tests:**
- What's not tested: All React components, Redux state management, API client, upload service.
- Files: `music-frontend/src/` (no test files outside `node_modules/`)
- Risk: UI regressions go unnoticed. Build-only CI check catches syntax errors but not logic bugs.
- Priority: **Medium** - Less critical than backend since frontend bugs are more visible.

**Android: Build-Only CI:**
- What's not tested: All ViewModels, API integration, upload flow, passkey auth.
- Files: `android-app/` (CI only runs `assembleDebug`)
- Risk: Runtime crashes and logic errors are not caught before merge.
- Priority: **Medium** - Write unit tests for `MainViewModel` and API layer.

---

*Concerns audit: 2026-03-29*
