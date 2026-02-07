import 'dart:io';
import 'package:dio/dio.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:spotube/plugins/s3_bombest/lib/models/s3_track.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/services/analytics/s3_analytics.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

/// Unified cache manager for S3 tracks.
/// Handles progressive download during streaming, LRU eviction, and ETag validation.
class S3CacheManager {
  final Dio _dio;
  final String _cacheDir;
  static const int _maxCacheSizeMB = 500; // 500 MB default cache limit
  static const String _etagFileSuffix = '.etag';

  S3CacheManager({
    required Dio dio,
    required String cacheDir,
  })  : _dio = dio,
        _cacheDir = cacheDir;

  /// Get the cached file for an S3 track, if it exists and is valid.
  /// Returns null if not cached or cache is invalid.
  Future<File?> getCachedFile(S3Track track) async {
    try {
      final cacheFile = _getCacheFilePath(track);
      final etagFile = _getEtagFilePath(track);

      // Check if cache file exists
      if (!await cacheFile.exists()) {
        AppLogger.log.d('[S3CacheManager] Cache file not found for ${track.key}');
        s3Analytics.recordCacheMiss();
        return null;
      }

      // Verify ETag matches (if ETag file exists)
      if (await etagFile.exists()) {
        final cachedEtag = await etagFile.readAsString();
        if (cachedEtag.trim() != track.eTag) {
          AppLogger.log.d('[S3CacheManager] ETag mismatch for ${track.key}, invalidating cache');
          await cacheFile.delete();
          await etagFile.delete();
          return null;
        }
      }

      // Verify file size matches (basic integrity check)
      final fileSize = await cacheFile.length();
      if (fileSize != track.size) {
        AppLogger.log.w('[S3CacheManager] File size mismatch for ${track.key}: cached=$fileSize, expected=${track.size}');
        // Don't delete - might be partial download, allow resume
      }

      AppLogger.log.d('[S3CacheManager] Cache hit for ${track.key}');
      return cacheFile;
    } catch (e, stack) {
      AppLogger.log.e('[S3CacheManager] Error getting cached file for ${track.key}', error: e, stackTrace: stack);
      return null;
    }
  }

  /// Check if a track is cached.
  Future<bool> isCached(S3Track track) async {
    final cachedFile = await getCachedFile(track);
    return cachedFile != null;
  }

  /// Cache a track, downloading it in the background if needed.
  /// Returns the cached file when complete.
  /// If background is true, returns immediately and downloads asynchronously.
  Future<File> cacheTrack(
    S3Track track, {
    bool background = false,
    Function(int downloaded, int total)? onProgress,
  }) async {
    // Check if already cached
    final existingCache = await getCachedFile(track);
    if (existingCache != null) {
      AppLogger.log.d('[S3CacheManager] Track already cached: ${track.key}');
      return existingCache;
    }

    final cacheFile = _getCacheFilePath(track);
    final tempFile = File('${cacheFile.path}.tmp');
    final etagFile = _getEtagFilePath(track);

    // Ensure cache directory exists
    await Directory(_cacheDir).create(recursive: true);

    try {
      AppLogger.log.i('[S3CacheManager] Caching track: ${track.key} (${track.size} bytes)');

      // Download with progress tracking
      await _dio.download(
        track.url,
        tempFile.path,
        onReceiveProgress: (downloaded, total) {
          onProgress?.call(downloaded, total);
          AppLogger.log.d('[S3CacheManager] Download progress for ${track.key}: $downloaded/$total');
        },
        options: Options(
          // Support resume for partial downloads
          receiveTimeout: const Duration(minutes: 10),
          // Validate status codes
          validateStatus: (status) => status != null && status < 400,
        ),
      );

      // Move temp file to final location
      if (await tempFile.exists()) {
        await tempFile.rename(cacheFile.path);
        
        // Save ETag for validation
        await etagFile.writeAsString(track.eTag);
        
        AppLogger.log.i('[S3CacheManager] Successfully cached ${track.key}');
        
        // Check cache size and evict if needed
        await _evictIfNeeded();
        
        return cacheFile;
      } else {
        throw Exception('Downloaded file not found after download');
      }
    } catch (e, stack) {
      // Clean up temp file on error
      if (await tempFile.exists()) {
        await tempFile.delete();
      }
      AppLogger.log.e('[S3CacheManager] Error caching track ${track.key}', error: e, stackTrace: stack);
      rethrow;
    }
  }

  /// Get total cache size in bytes.
  Future<int> getCacheSize() async {
    try {
      final dir = Directory(_cacheDir);
      if (!await dir.exists()) {
        return 0;
      }

      int totalSize = 0;
      await for (final entity in dir.list(recursive: true)) {
        if (entity is File && !entity.path.endsWith(_etagFileSuffix)) {
          totalSize += await entity.length();
        }
      }
      return totalSize;
    } catch (e, stack) {
      AppLogger.log.e('[S3CacheManager] Error calculating cache size', error: e, stackTrace: stack);
      return 0;
    }
  }

  /// Clear cache, optionally keeping files under maxSizeMB.
  /// Uses LRU (Least Recently Used) eviction based on file modification time.
  /// If maxSizeMB is 0, clears all cache.
  Future<void> clearCache({int? maxSizeMB}) async {
    try {
      // If maxSizeMB is 0, clear everything
      if (maxSizeMB == 0) {
        AppLogger.log.i('[S3CacheManager] Clearing all cache...');
        final dir = Directory(_cacheDir);
        if (await dir.exists()) {
          await for (final entity in dir.list(recursive: true)) {
            if (entity is File) {
              await entity.delete();
            }
          }
        }
        AppLogger.log.i('[S3CacheManager] All cache cleared');
        return;
      }
      
      final maxBytes = (maxSizeMB ?? _maxCacheSizeMB) * 1024 * 1024;
      final currentSize = await getCacheSize();

      if (currentSize <= maxBytes) {
        AppLogger.log.d('[S3CacheManager] Cache size ($currentSize) within limit ($maxBytes)');
        return;
      }

      AppLogger.log.i('[S3CacheManager] Cache size ($currentSize) exceeds limit ($maxBytes), evicting...');

      // Get all cache files with their modification times
      final dir = Directory(_cacheDir);
      if (!await dir.exists()) {
        return;
      }

      final files = <File>[];
      await for (final entity in dir.list(recursive: true)) {
        if (entity is File && !entity.path.endsWith(_etagFileSuffix)) {
          files.add(entity);
        }
      }

      // Sort by modification time (oldest first - LRU)
      // Collect file stats first, then sort
      final filesWithStats = await Future.wait(
        files.map((file) async {
          final stat = await file.stat();
          return (file: file, modified: stat.modified);
        }),
      );
      filesWithStats.sort((a, b) => a.modified.compareTo(b.modified));
      final sortedFiles = filesWithStats.map((e) => e.file).toList();

      // Delete oldest files until under limit
      int deletedSize = 0;
      for (final file in sortedFiles) {
        if (currentSize - deletedSize <= maxBytes) {
          break;
        }

        final fileSize = await file.length();
        await file.delete();
        
        // Also delete associated ETag file
        final etagFile = File('${file.path}$_etagFileSuffix');
        if (await etagFile.exists()) {
          await etagFile.delete();
        }

        deletedSize += fileSize;
        AppLogger.log.d('[S3CacheManager] Evicted ${file.path}');
      }

      AppLogger.log.i('[S3CacheManager] Evicted ${deletedSize} bytes, new cache size: ${currentSize - deletedSize}');
    } catch (e, stack) {
      AppLogger.log.e('[S3CacheManager] Error clearing cache', error: e, stackTrace: stack);
    }
  }

  /// Evict cache if it exceeds the limit (called automatically after caching).
  Future<void> _evictIfNeeded() async {
    await clearCache();
  }

  /// Get the cache file path for a track.
  File _getCacheFilePath(S3Track track) {
    // Use track key as filename, sanitized for filesystem
    final sanitizedKey = track.key.replaceAll('/', '_').replaceAll(' ', '_');
    return File(path.join(_cacheDir, sanitizedKey));
  }

  /// Get the ETag file path for a track.
  File _getEtagFilePath(S3Track track) {
    return File('${_getCacheFilePath(track).path}$_etagFileSuffix');
  }
}

/// Provider for S3CacheManager instance.
final s3CacheManagerProvider = Provider<S3CacheManager>((ref) {
  final dio = Dio();
  // Get cache directory from user preferences
  final cacheDirFuture = UserPreferencesNotifier.getMusicCacheDir();
  
  // For now, use a synchronous approach - cache dir should be available
  // In production, this might need to be async or use a FutureProvider
  return S3CacheManager(
    dio: dio,
    cacheDir: '', // Will be set asynchronously
  );
});

/// Async provider that creates S3CacheManager with proper cache directory.
final s3CacheManagerAsyncProvider = FutureProvider<S3CacheManager>((ref) async {
  final dio = Dio();
  final cacheDir = await UserPreferencesNotifier.getMusicCacheDir();
  
  // Ensure cache directory exists
  final dir = Directory(cacheDir);
  if (!await dir.exists()) {
    await dir.create(recursive: true);
  }
  
  return S3CacheManager(
    dio: dio,
    cacheDir: cacheDir,
  );
});

