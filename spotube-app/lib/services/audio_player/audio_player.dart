import 'dart:io';

import 'package:media_kit/media_kit.dart' hide Track;
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:flutter/foundation.dart';
import 'package:spotube/services/audio_player/custom_player.dart';
import 'dart:async';

import 'package:media_kit/media_kit.dart' as mk;

import 'package:spotube/services/audio_player/playback_state.dart';
import 'package:spotube/utils/platform.dart';
import 'package:spotube/plugins/s3_bombest/lib/models/s3_track.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_providers.dart';
import 'package:spotube/services/s3_cache/s3_cache_manager.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/utils/service_utils.dart';

part 'audio_players_streams_mixin.dart';
part 'audio_player_impl.dart';

class SpotubeMedia extends mk.Media {
  static int serverPort = 0;
  static String? _cachedCacheDir; // Cache the cache directory path
  static String? _cachedDownloadDir; // Cache the download directory path

  static String get _host =>
      kIsWindows ? "localhost" : "127.0.0.1"; // loopback default (fallback)

  final SpotubeTrackObject track;
  SpotubeMedia(this.track)
      : assert(
          track is SpotubeLocalTrackObject || track is SpotubeFullTrackObject,
          "Track must be a either a local track or a full track object with ISRC",
        ),
        // For local files use path; for S3 tracks use direct URL; otherwise fallback to local proxy.
        super(
          _resolveUri(track),
          extras: track.toJson(),
        );

  factory SpotubeMedia.media(Media media) {
    assert(media.extras != null, "[Media] must have extra metadata set");
    return SpotubeMedia(SpotubeTrackObject.fromJson(media.extras!));
  }

  static String _resolveUri(SpotubeTrackObject track) {
    if (track is SpotubeLocalTrackObject) return track.path;
    
    // For S3 tracks, check cache first before returning URL
    if (track is SpotubeFullTrackObject &&
        track.externalUri != null &&
        track.externalUri!.startsWith("http")) {
      
      // Check if this is an S3 track (by URL pattern or track ID)
      final isS3Track = track.externalUri!.contains('s3.amazonaws.com') ||
          track.id.startsWith('music/');
      
      if (isS3Track) {
        // Quick synchronous check for cached file (cache directory)
        final cachedPath = _getCachedFilePathSync(track);
        if (cachedPath != null && File(cachedPath).existsSync()) {
          AppLogger.log.d('[SpotubeMedia] Using cached file for ${track.id}');
          return cachedPath;
        }
        
        // Also check download location for downloaded files
        final downloadedPath = _getDownloadedFilePathSync(track);
        if (downloadedPath != null && File(downloadedPath).existsSync()) {
          AppLogger.log.d('[SpotubeMedia] Using downloaded file for ${track.id}');
          return downloadedPath;
        }
        
        // Cache miss - return S3 URL and trigger background caching
        // Background caching will be handled by cache manager when track starts playing
        AppLogger.log.d('[SpotubeMedia] Cache miss for ${track.id}, using S3 URL');
      }
      
      return track.externalUri!;
    }
    // Fallback to embedded proxy server.
    return "http://$_host:$serverPort/stream/${track.id}";
  }
  
  /// Synchronously check for cached file path (quick check only).
  /// Returns null if not cached or cache directory unavailable.
  static String? _getCachedFilePathSync(SpotubeFullTrackObject track) {
    try {
      // Get cache directory (use cached value if available)
      String? cacheDir = _cachedCacheDir;
      if (cacheDir == null) {
        // Try to get it synchronously - this might fail, so we'll return null
        // The async cache check in audio player notifier will handle full validation
        return null;
      }

      // Sanitize track key for filename
      final sanitizedKey = track.id.replaceAll('/', '_').replaceAll(' ', '_');
      final cacheFile = File(path.join(cacheDir, sanitizedKey));
      
      // Quick existence check
      if (cacheFile.existsSync()) {
        return cacheFile.path;
      }
      
      return null;
    } catch (e) {
      return null;
    }
  }
  
  /// Synchronously check for downloaded file path in download location.
  /// Matches files by filename pattern (sanitized track name and artist).
  /// Returns null if not found or download directory unavailable.
  static String? _getDownloadedFilePathSync(SpotubeFullTrackObject track) {
    try {
      // Get download directory (use cached value if available)
      String? downloadDir = _cachedDownloadDir;
      if (downloadDir == null || downloadDir.isEmpty) {
        return null;
      }

      final downloadDirectory = Directory(downloadDir);
      if (!downloadDirectory.existsSync()) {
        return null;
      }

      // Build expected filename pattern (same as download manager uses)
      final artistNames = track.artists.map((a) => a.name).join(", ");
      final expectedFilename = ServiceUtils.sanitizeFilename(
        "${track.name} - $artistNames",
      );
      
      // Get file extension from track ID or external URI
      String? extension;
      if (track.externalUri != null) {
        final uriExt = path.extension(track.externalUri!);
        if (uriExt.isNotEmpty) {
          extension = uriExt.toLowerCase();
        }
      }
      if (extension == null || extension.isEmpty) {
        // Try to get from track ID
        final idExt = path.extension(track.id);
        if (idExt.isNotEmpty) {
          extension = idExt.toLowerCase();
        } else {
          extension = '.wav'; // Default fallback
        }
      }

      // Build full expected filename
      final expectedFullName = "$expectedFilename$extension";

      // Try to find matching file in download directory
      // List files synchronously (might be slow for large directories, but necessary)
      try {
        final files = downloadDirectory.listSync(recursive: false)
            .whereType<File>()
            .where((file) {
              final fileName = path.basename(file.path);
              
              // Exact match with sanitized filename
              return fileName == expectedFullName;
            })
            .toList();
        
        if (files.isNotEmpty) {
          // Return first match
          return files.first.path;
        }
      } catch (e) {
        // If listing fails, return null
        AppLogger.log.w('[SpotubeMedia] Failed to list download directory: $e');
        return null;
      }
      
      return null;
    } catch (e) {
      return null;
    }
  }
  
  /// Initialize cache and download directories (called asynchronously).
  /// This allows _resolveUri to check for cached/downloaded files synchronously.
  /// [downloadDir] is optional - if not provided, download directory won't be initialized.
  static Future<void> initializeCacheDir({String? downloadDir}) async {
    if (_cachedCacheDir == null) {
      try {
        _cachedCacheDir = await UserPreferencesNotifier.getMusicCacheDir();
        AppLogger.log.d('[SpotubeMedia] Cache directory initialized: $_cachedCacheDir');
      } catch (e) {
        AppLogger.log.w('[SpotubeMedia] Failed to initialize cache dir: $e');
      }
    }
    
    if (_cachedDownloadDir == null && downloadDir != null && downloadDir.isNotEmpty) {
      _cachedDownloadDir = downloadDir;
      AppLogger.log.d('[SpotubeMedia] Download directory initialized: $_cachedDownloadDir');
    }
  }
}

abstract class AudioPlayerInterface {
  final CustomPlayer _mkPlayer;

  AudioPlayerInterface()
      : _mkPlayer = CustomPlayer(
          configuration: const mk.PlayerConfiguration(
            title: "Spotube",
            logLevel: kDebugMode ? mk.MPVLogLevel.info : mk.MPVLogLevel.error,
            async: true,
          ),
        ) {
    _mkPlayer.stream.error.listen((event) {
      AppLogger.reportError(event, StackTrace.current);
    });
  }

  /// Whether the current platform supports the audioplayers plugin
  static const bool _mkSupportedPlatform = true;

  bool get mkSupportedPlatform => _mkSupportedPlatform;

  Duration get duration {
    return _mkPlayer.state.duration;
  }

  Playlist get playlist {
    return _mkPlayer.state.playlist;
  }

  Duration get position {
    return _mkPlayer.state.position;
  }

  Duration get bufferedPosition {
    return _mkPlayer.state.buffer;
  }

  Future<mk.AudioDevice> get selectedDevice async {
    return _mkPlayer.state.audioDevice;
  }

  Future<List<mk.AudioDevice>> get devices async {
    return _mkPlayer.state.audioDevices;
  }

  bool get hasSource {
    return _mkPlayer.state.playlist.medias.isNotEmpty;
  }

  // states
  bool get isPlaying {
    return _mkPlayer.state.playing;
  }

  bool get isPaused {
    return !_mkPlayer.state.playing;
  }

  bool get isStopped {
    return !hasSource;
  }

  Future<bool> get isCompleted async {
    return _mkPlayer.state.completed;
  }

  bool get isShuffled {
    return _mkPlayer.shuffled;
  }

  PlaylistMode get loopMode {
    return _mkPlayer.state.playlistMode;
  }

  /// Returns the current volume of the player, between 0 and 1
  double get volume {
    return _mkPlayer.state.volume / 100;
  }

  bool get isBuffering {
    return _mkPlayer.state.buffering;
  }
}
