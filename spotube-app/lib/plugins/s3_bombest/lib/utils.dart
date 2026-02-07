import 'dart:math';
import 'dart:convert';
import 'package:collection/collection.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/provider/download_manager_provider.dart';
import 'models/s3_track.dart';

/// Generate a stable color based on track name for placeholder album art
String _generatePlaceholderArt(String trackName) {
  // Use track name hash to generate consistent color
  final hash = trackName.hashCode.abs();
  final random = Random(hash);
  
  // Generate pleasing pastel/vibrant colors
  final hue = random.nextInt(360);
  final saturation = 60 + random.nextInt(30); // 60-90%
  final lightness = 45 + random.nextInt(20); // 45-65%
  
  // Convert HSL to RGB
  final c = (1 - (2 * lightness / 100 - 1).abs()) * saturation / 100;
  final x = c * (1 - ((hue / 60) % 2 - 1).abs());
  final m = lightness / 100 - c / 2;
  
  double r, g, b;
  if (hue < 60) {
    r = c; g = x; b = 0;
  } else if (hue < 120) {
    r = x; g = c; b = 0;
  } else if (hue < 180) {
    r = 0; g = c; b = x;
  } else if (hue < 240) {
    r = 0; g = x; b = c;
  } else if (hue < 300) {
    r = x; g = 0; b = c;
  } else {
    r = c; g = 0; b = x;
  }
  
  final red = ((r + m) * 255).round();
  final green = ((g + m) * 255).round();
  final blue = ((b + m) * 255).round();
  
  // Create a simple SVG with solid color background
  final svg = '''<svg width="300" height="300" xmlns="http://www.w3.org/2000/svg">
  <rect width="300" height="300" fill="rgb($red,$green,$blue)"/>
  <text x="150" y="150" font-family="Arial" font-size="120" fill="rgba(255,255,255,0.3)" text-anchor="middle" dominant-baseline="middle">${trackName.isNotEmpty ? trackName[0].toUpperCase() : 'B'}</text>
</svg>''';
  
  return 'data:image/svg+xml;base64,${base64Encode(utf8.encode(svg))}';
}

SpotubeFullTrackObject s3TrackToSpotubeTrack(S3Track s3Track) {
  // Common artist and album for S3 tracks
  // In a real scenario, we might parse artist/album from directory structure.
  // For now, flat structure -> Single Artist/Album "S3 Library"
  
  // Parse filename: "Artist - Title.mp3" or just "Title.mp3"
  String title = s3Track.title.replaceAll('_', ' '); // Default from model (filename without ext)
  String artistName = "thomas phillips";

  if (title.contains(' - ')) {
    final parts = title.split(' - ');
    if (parts.length >= 2) {
      artistName = parts[0].trim();
      title = parts.sublist(1).join(' - ').trim();
    }
  }

  final artist = SpotubeSimpleArtistObject(
    id: artistName, // Use name as ID for now
    name: artistName,
    externalUri: s3Track.url,
    images: [], 
  );

  final album = SpotubeSimpleAlbumObject(
    id: "s3_album",
    name: "S3 Cloud Library",
    externalUri: s3Track.url,
    artists: [artist],
    // Use graffiti bomb as default album art
    images: [
      SpotubeImageObject(
        url: "https://bombest-beats-music.s3.us-west-2.amazonaws.com/music/graffitti-bomb.png",
        width: 300,
        height: 300,
      )
    ],
    albumType: SpotubeAlbumType.album,
    releaseDate: s3Track.lastModified.toString().split(' ').first,
  );

  return SpotubeFullTrackObject(
    id: s3Track.key, 
    name: title, // Use parsed title
    externalUri: s3Track.url,
    artists: [artist],
    album: album,
    // Unknown duration; avoid guessing to prevent wrong UI/seek.
    durationMs: 0,
    isrc: "", // No ISRC
    explicit: false,
  );
}

/// Normalize a string for matching (lowercase, trim, remove special chars)
String _normalizeString(String str) {
  return str
      .toLowerCase()
      .trim()
      .replaceAll(RegExp(r'[^\w\s]'), '')
      .replaceAll(RegExp(r'\s+'), ' ');
}

/// Normalize artist names for matching
String _normalizeArtists(List<SpotubeSimpleArtistObject> artists) {
  final normalized = artists.map((a) => _normalizeString(a.name)).toList()
    ..sort();
  return normalized.join(', ');
}

/// Match an S3 track with a local track by comparing normalized title and artists
bool matchS3TrackToLocalFile(
  SpotubeFullTrackObject s3Track,
  SpotubeLocalTrackObject localTrack,
) {
  // Normalize track titles
  final s3Title = _normalizeString(s3Track.name);
  final localTitle = _normalizeString(localTrack.name);

  // Normalize artist names
  final s3Artists = _normalizeArtists(s3Track.artists);
  final localArtists = _normalizeArtists(localTrack.artists);

  // Match if title and artists match (case-insensitive, normalized)
  return s3Title == localTitle && s3Artists == localArtists;
}

/// Get download status for a track
/// Returns null if not downloaded and not in queue
DownloadStatus? getTrackDownloadStatus(
  SpotubeFullTrackObject track,
  List<DownloadTask> downloadQueue,
  List<SpotubeLocalTrackObject> localTracks,
) {
  // First check download queue
  final queueTask = downloadQueue.firstWhereOrNull(
    (task) => task.track.id == track.id,
  );

  if (queueTask != null) {
    return queueTask.status;
  }

  // Then check local files
  final isDownloaded = localTracks.any(
    (localTrack) => matchS3TrackToLocalFile(track, localTrack),
  );

  if (isDownloaded) {
    return DownloadStatus.completed;
  }

  // Not downloaded and not in queue
  return null;
}
