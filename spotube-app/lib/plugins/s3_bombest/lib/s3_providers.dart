import 'package:flutter_riverpod/flutter_riverpod.dart';
import 's3_repository.dart';
import 'models/s3_track.dart';
import 'package:spotube/services/logger/logger.dart';

final s3RepositoryProvider = Provider((ref) => S3Repository());

/// Provider for S3 tracks with keep-alive to prevent disposal.
/// This ensures tracks are available immediately when the library page loads.
final s3TracksProvider = FutureProvider.autoDispose<List<S3Track>>((ref) async {
  // Keep provider alive to prevent disposal
  // This ensures data persists across widget rebuilds
  ref.keepAlive();
  
  AppLogger.log.i('[s3TracksProvider] Provider called, fetching tracks...');
  final repository = ref.read(s3RepositoryProvider);
  
  int retryCount = 0;
  const maxRetries = 3;
  const baseDelay = Duration(seconds: 1);
  
  while (retryCount < maxRetries) {
    try {
      final tracks = await repository.fetchTracks();
      AppLogger.log.i('[s3TracksProvider] Provider resolved with ${tracks.length} tracks');
      return tracks;
    } catch (e, stack) {
      retryCount++;
      if (retryCount >= maxRetries) {
        AppLogger.log.e('[s3TracksProvider] Provider failed after $maxRetries retries', error: e, stackTrace: stack);
        rethrow;
      }
      
      // Exponential backoff: 1s, 2s, 4s
      final delay = Duration(milliseconds: baseDelay.inMilliseconds * (1 << (retryCount - 1)));
      AppLogger.log.w('[s3TracksProvider] Retry $retryCount/$maxRetries after ${delay.inSeconds}s');
      await Future.delayed(delay);
    }
  }
  
  // Should never reach here, but satisfy the compiler
  throw Exception('Failed to fetch S3 tracks after $maxRetries retries');
});

/// Refresh provider for manual refresh of S3 tracks
final s3TracksRefreshProvider = FutureProvider.autoDispose<List<S3Track>>((ref) async {
  // Invalidate the main provider to force refresh
  ref.invalidate(s3TracksProvider);
  // Return the refreshed data
  return ref.read(s3TracksProvider.future);
});
