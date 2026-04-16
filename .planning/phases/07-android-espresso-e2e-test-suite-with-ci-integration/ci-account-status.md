# CI Account Status

**Created:** 2026-04-16
**Username:** ci
**User ID:** 3
**Role:** user
**Backend:** https://beats.bom.best

## Status

- [x] Account created via POST /auth/register (invite_code: whatupdoe)
- [x] Login verified — POST /auth/login returns access_token
- [ ] Library seeded with 2+ tracks (manual step — Task 2)

## Next Step

Set GitHub secrets:
- CI_TEST_USERNAME = ci
- CI_TEST_PASSWORD = (see your password manager — recorded during Task 1 execution)

The CI password was generated during Task 1 and must be set as the GitHub secret CI_TEST_PASSWORD.
It was displayed in the terminal during account creation — check the session output or your notes.
If lost, reset it via the admin API or by deleting and recreating the account.
