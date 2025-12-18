---
description: Redesign Playlist UX/UI
---

ROLE: You are a senior product engineer + UX engineer. You will redesign the playlist flow end-to-end (UI + data model + playback integration) in this music player app.

0) Goals
	1.	Make the first screen a Playlists screen (not “All Songs / Playlists / Recently Played” cards).
	2.	Provide two auto playlists:
	•	All Songs (always present, contains every local+synced track)
	•	Favorites (always present, user-managed)
	3.	Implement Spotify-like playlist behavior:
	•	Create / rename / delete playlists
	•	Add/remove/reorder tracks in playlists
	•	Search within playlist
	•	Sort playlist (custom order, title, artist, recently added)
	•	Offline-first (playlists work without network)
	•	Sync playlists across user’s devices when signed in
	4.	Admin feature: allow admin to publish playlists to all users.
	•	Published playlists appear for all users (read-only or “save/copy” behavior)
	•	Non-admin playlists remain local/private unless user chooses to sync
	5.	Playback must integrate cleanly:
	•	Play button on playlist
	•	Shuffle playlist
	•	“Play next”, “Add to queue”
	•	Now Playing bar persists and updates properly

1) New Navigation & Screens

Implement the following screens + nav structure:
	•	Playlists (Root/Home)
	•	List of playlists with cover art collage and metadata
	•	Top pinned: All Songs, Favorites
	•	FAB: “New playlist”
	•	Overflow menu per playlist: rename, delete, edit, share (if supported), publish (admin only)
	•	Playlist Detail
	•	Header: playlist cover, title, track count, duration
	•	Primary actions: Play, Shuffle
	•	Track list with contextual actions: remove, favorite/unfavorite, add to another playlist, play next, add to queue
	•	Reorder mode (drag handle)
	•	Add to Playlist Modal / Bottom Sheet
	•	Search playlists
	•	Create new playlist inline
	•	Favorites
	•	Treated like a playlist detail screen (special playlist)

Make it feel like Spotify:
	•	Tap playlist = open details
	•	Tap play button on playlist card = play starting at first track (or last played position depending on your playback rules)
	•	Long-press playlist = selection mode (optional)

2) UI Style (match your current dark theme + optional graffiti theme)

Keep dark UI, but improve hierarchy and spacing.

Playlist card style:
	•	Left: cover image (use album art collage if playlist has >1 track, else fallback placeholder)
	•	Middle: playlist name + “X tracks”
	•	Right: subtle play icon button + overflow menu
	•	Rounded corners, soft elevation, consistent padding

Also:
	•	Make the floating “+” on playlist detail context-aware:
	•	In Playlist Detail, FAB = “Add songs”
	•	Use your graffiti placeholder for missing art, but don’t slap it everywhere:
	•	Only as a fallback image; not repeated on every row if avoidable.

3) Data Model & Storage

Create a stable playlist schema and persistence layer.

Playlist entity
	•	id (UUID)
	•	name
	•	description (optional)
	•	ownerUserId (nullable for local-only)
	•	isSystem (All Songs, Favorites)
	•	isPublished (admin only)
	•	isSynced (boolean)
	•	createdAt, updatedAt
	•	trackIds (ordered list)
	•	sortMode (custom / title / artist / recentlyAdded)
	•	artwork (optional: explicit cover image OR generated collage refs)

Storage
	•	Local DB (SQLite / Room / IndexedDB depending on platform)
	•	Must support ordered list updates (reordering)
	•	Favorites is a special playlist, but stored the same way for consistency.

4) Sync Between Devices (Spotify-ish)

Implement a sync service with these rules:
	•	If user is not signed in: playlists are local only
	•	If signed in: playlists are synced
	•	Sync playlist metadata + track references
	•	Conflict resolution:
	•	last-write-wins for metadata
	•	ordered track list merges with stable ops (or last-write-wins for ordering to start)
	•	Support “pull published playlists” from server:
	•	Published playlists show up for all users
	•	Users can “Save to My Playlists” (creates a local copy linked to sourcePlaylistId)

Add a “Sync Status” indicator in settings (small).

5) Admin Publishing

Add role-based access:
	•	isAdmin flag from auth profile or server claim.
	•	Admin-only action on playlists:
	•	“Publish to all users”
	•	“Unpublish”
Publishing behavior:
	•	When published: playlist appears in a “Published” section for everyone.
	•	Non-admins cannot edit published playlist contents.
	•	Non-admins can “Copy” to create their own editable version.

6) Playback Integration

Update playback service/queue to support playlist context:
	•	When starting playback from playlist:
	•	Create queue = playlist’s ordered tracks
	•	Maintain currentContext = { type: 'playlist', playlistId }
	•	Add actions:
	•	Play Next (inserts after current track)
	•	Add to Queue (appends)
	•	When removing a track from a playlist:
	•	If currently playing and removed from playlist, playback continues but queue remains stable.
	•	Ensure Now Playing bar shows:
	•	Track title/artist/art
	•	Context subtitle: playlist name (if applicable)

7) Standard Music Player Playlist Features Checklist

Implement these user features:
	•	Create playlist
	•	Add songs to playlist
	•	Remove songs
	•	Reorder songs
	•	Rename playlist
	•	Delete playlist
	•	Favorite/unfavorite track
	•	“Add to playlist” from track overflow
	•	Shuffle playlist
	•	Search within playlist
	•	Sort playlist
	•	Persist last played position per playlist (optional but preferred)

8) Output Requirements

Make changes directly in the codebase:
	•	Update routes/navigation
	•	Add new screens/components
	•	Add playlist service + repository layer
	•	Add sync scaffolding (even if backend endpoints are stubbed)
	•	Ensure UI is polished and consistent
	•	Add tests for playlist CRUD + ordering logic

9) Constraints
	•	Do NOT break existing playback.
	•	Keep changes modular.
	•	Add comments explaining architecture decisions.