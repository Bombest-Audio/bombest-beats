import 'dart:async';
import 'package:dio/dio.dart';
import 'package:spotube/plugins/s3_bombest/lib/models/s3_track.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_providers.dart';
import 'package:spotube/provider/download_manager_provider.dart';
import 'package:spotube/provider/local_tracks/local_tracks_provider.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/services/analytics/s3_analytics.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:spotube/plugins/s3_bombest/lib/utils.dart';
import 'package:spotube/models/metadata/metadata.dart';

/// Service for syncing all S3 tracks to local storage.
/// Integrates with DownloadManagerNotifier to queue downloads.
class S3SyncService {
  final Ref _ref;
  bool _isSyncing = false;
  CancelToken? _cancelToken;

  S3SyncService(this._ref);

  /// Sync all S3 tracks that aren't already downloaded.
  /// Calls onProgress callback with (current, total) as tracks are queued.
  Future<void> syncAll({
    Function(int current, int total)? onProgress,
  }) async {
    if (_isSyncing) {
      AppLogger.log.w('[S3SyncService] Sync already in progress');
      return;
    }

    _isSyncing = true;
    _cancelToken = CancelToken();

    try {
      AppLogger.log.i('[S3SyncService] Starting sync of all S3 tracks');

      // Get all S3 tracks
      final s3Tracks = await _ref.read(s3TracksProvider.future);
      AppLogger.log.i('[S3SyncService] Found ${s3Tracks.length} S3 tracks');

      // Get local tracks to check what's already downloaded
      final localTracksData = await _ref.read(localTracksProvider.future);
      final allLocal = localTracksData.values
          .expand((tracks) => tracks)
          .toList();

      // Get current download queue
      final downloadQueue = _ref.read(downloadManagerProvider);
      final downloadManagerNotifier = _ref.read(downloadManagerProvider.notifier);

      // Convert S3 tracks to Spotube tracks and filter out already downloaded/queued
      final tracksToSync = <SpotubeFullTrackObject>[];
      int processed = 0;

      for (final s3Track in s3Tracks) {
        if (_cancelToken?.isCancelled ?? false) {
          AppLogger.log.i('[S3SyncService] Sync cancelled');
          break;
        }

        final spotubeTrack = s3TrackToSpotubeTrack(s3Track);

        // Skip if already in queue
        if (downloadManagerNotifier.isTrackInQueue(spotubeTrack)) {
          processed++;
          onProgress?.call(processed, s3Tracks.length);
          continue;
        }

        // Skip if already downloaded
        final isDownloaded = allLocal.any(
          (localTrack) => matchS3TrackToLocalFile(spotubeTrack, localTrack),
        );

        if (!isDownloaded) {
          tracksToSync.add(spotubeTrack);
        }

        processed++;
        onProgress?.call(processed, s3Tracks.length);
      }

      AppLogger.log.i('[S3SyncService] ${tracksToSync.length} tracks need syncing');

      // Queue tracks for download (max 3 concurrent downloads handled by download manager)
      if (tracksToSync.isNotEmpty) {
        downloadManagerNotifier.addAllToQueue(tracksToSync);
        AppLogger.log.i('[S3SyncService] Queued ${tracksToSync.length} tracks for download');
      }

      AppLogger.log.i('[S3SyncService] Sync completed');
      s3Analytics.recordSyncComplete(success: true);
    } catch (e, stack) {
      AppLogger.log.e(
        '[S3SyncService] Error during sync',
        error: e,
        stackTrace: stack,
      );
      s3Analytics.recordSyncComplete(success: false);
      rethrow;
    } finally {
      _isSyncing = false;
      _cancelToken = null;
    }
  }

  /// Sync a single S3 track.
  Future<void> syncTrack(S3Track track) async {
    try {
      final spotubeTrack = s3TrackToSpotubeTrack(track);
      final downloadManagerNotifier = _ref.read(downloadManagerProvider.notifier);

      // Check if already downloaded
      final localTracksData = await _ref.read(localTracksProvider.future);
      final allLocal = localTracksData.values
          .expand((tracks) => tracks)
          .toList();

      final isDownloaded = allLocal.any(
        (localTrack) => matchS3TrackToLocalFile(spotubeTrack, localTrack),
      );

      if (isDownloaded) {
        AppLogger.log.d('[S3SyncService] Track already downloaded: ${track.key}');
        return;
      }

      // Check if already in queue
      if (downloadManagerNotifier.isTrackInQueue(spotubeTrack)) {
        AppLogger.log.d('[S3SyncService] Track already in queue: ${track.key}');
        return;
      }

      // Add to download queue
      downloadManagerNotifier.addToQueue(spotubeTrack);
      AppLogger.log.i('[S3SyncService] Queued track for sync: ${track.key}');
    } catch (e, stack) {
      AppLogger.log.e(
        '[S3SyncService] Error syncing track ${track.key}',
        error: e,
        stackTrace: stack,
      );
      rethrow;
    }
  }

  /// Cancel ongoing sync operation.
  Future<void> cancelSync() async {
    if (_isSyncing) {
      _cancelToken?.cancel();
      _isSyncing = false;
      AppLogger.log.i('[S3SyncService] Sync cancelled');
    }
  }

  /// Check if sync is currently in progress.
  bool get isSyncing => _isSyncing;
}

/// Provider for S3SyncService instance.
final s3SyncServiceProvider = Provider<S3SyncService>((ref) {
  return S3SyncService(ref);
});

