import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:dio/dio.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:spotube/stubs/metadata_god.dart';
import 'package:path/path.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart' hide join;
import 'package:spotube/collections/routes.dart';
import 'package:spotube/components/dialogs/replace_downloaded_dialog.dart';
import 'package:spotube/extensions/dio.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/provider/metadata_plugin/audio_source/quality_presets.dart';
import 'package:spotube/provider/server/sourced_track_provider.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/utils/service_utils.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_providers.dart';
import 'package:spotube/plugins/s3_bombest/lib/utils.dart';
import 'package:spotube/provider/local_tracks/local_tracks_provider.dart';
import 'package:spotube/services/analytics/s3_analytics.dart';

enum DownloadStatus {
  queued,
  downloading,
  completed,
  failed,
  canceled,
}

class DownloadTask {
  final SpotubeFullTrackObject track;
  final DownloadStatus status;
  final CancelToken cancelToken;
  final int? totalSizeBytes;
  final StreamController<int> _downloadedBytesStreamController;

  Stream<int> get downloadedBytesStream =>
      _downloadedBytesStreamController.stream;

  DownloadTask({
    required this.track,
    required this.status,
    required this.cancelToken,
    this.totalSizeBytes,
    StreamController<int>? downloadedBytesStreamController,
  }) : _downloadedBytesStreamController =
            downloadedBytesStreamController ?? StreamController.broadcast();

  DownloadTask copyWith({
    SpotubeFullTrackObject? track,
    DownloadStatus? status,
    CancelToken? cancelToken,
    int? totalSizeBytes,
    StreamController<int>? downloadedBytesStreamController,
  }) {
    return DownloadTask(
      track: track ?? this.track,
      status: status ?? this.status,
      cancelToken: cancelToken ?? this.cancelToken,
      totalSizeBytes: totalSizeBytes ?? this.totalSizeBytes,
      downloadedBytesStreamController:
          downloadedBytesStreamController ?? _downloadedBytesStreamController,
    );
  }
}

class DownloadManagerNotifier extends Notifier<List<DownloadTask>> {
  final Dio dio;
  DownloadManagerNotifier()
      : dio = Dio(),
        super();

  @override
  build() {
    ref.onDispose(() {
      for (final task in state) {
        if (task.status == DownloadStatus.downloading) {
          task.cancelToken.cancel();
        }
        task._downloadedBytesStreamController.close();
      }
    });

    return [];
  }

  DownloadTask? getTaskByTrackId(String trackId) {
    return state.firstWhereOrNull((element) => element.track.id == trackId);
  }

  DownloadStatus? getDownloadStatus(SpotubeFullTrackObject track) {
    final task = getTaskByTrackId(track.id);
    return task?.status;
  }

  bool isTrackInQueue(SpotubeFullTrackObject track) {
    final task = getTaskByTrackId(track.id);
    return task != null &&
        (task.status == DownloadStatus.queued ||
            task.status == DownloadStatus.downloading);
  }

  void cancelQueuedOnly() {
    state = state.map((task) {
      if (task.status == DownloadStatus.queued ||
          task.status == DownloadStatus.downloading) {
        if (task.status == DownloadStatus.downloading) {
          task.cancelToken.cancel();
        }
        return task.copyWith(status: DownloadStatus.canceled);
      }
      return task;
    }).toList();
  }

  void addToQueue(SpotubeFullTrackObject track) {
    if (state.any((element) => element.track.id == track.id)) return;
    state = [
      ...state,
      DownloadTask(
        track: track,
        status: DownloadStatus.queued,
        cancelToken: CancelToken(),
      ),
    ];

    ref.read(sourcedTrackProvider(track));

    _startDownloading(); // No await should be invoked to avoid stuck UI
  }

  void addAllToQueue(List<SpotubeFullTrackObject> tracks) {
    // Filter out tracks that are already in queue or completed
    final tracksToAdd = tracks.where((track) {
      final existingTask = getTaskByTrackId(track.id);
      return existingTask == null ||
          (existingTask.status != DownloadStatus.completed &&
              existingTask.status != DownloadStatus.downloading &&
              existingTask.status != DownloadStatus.queued);
    }).toList();

    if (tracksToAdd.isEmpty) return;

    state = [
      ...state,
      ...tracksToAdd.map((e) => DownloadTask(
            track: e,
            status: DownloadStatus.queued,
            cancelToken: CancelToken(),
          )),
    ];

    ref.read(sourcedTrackProvider(tracksToAdd.first));
    _startDownloading(); // No await should be invoked to avoid stuck UI
  }

  /// Retry a failed or canceled download
  /// This allows user-initiated retry for failed downloads
  void retry(SpotubeFullTrackObject track) {
    final existingTask = state.firstWhereOrNull((e) => e.track.id == track.id);
    if (existingTask == null) {
      // Track not in download queue, add it
      addToQueue(track);
      return;
    }
    
    if (existingTask.status == DownloadStatus.canceled || 
        existingTask.status == DownloadStatus.failed) {
      AppLogger.log.i('[DownloadManager] Retrying download for ${track.id}');
      _setStatus(track, DownloadStatus.queued);
      _startDownloading(); // No await should be invoked to avoid stuck UI
    } else {
      AppLogger.log.w('[DownloadManager] Cannot retry track ${track.id}: status is ${existingTask.status}');
    }
  }

  void cancel(SpotubeFullTrackObject track) {
    if (state.firstWhereOrNull((e) => e.track.id == track.id)?.status ==
        DownloadStatus.failed) {
      return;
    }
    _setStatus(track, DownloadStatus.canceled);
  }

  void clearAll() {
    for (final task in state) {
      if (task.status == DownloadStatus.downloading) {
        task.cancelToken.cancel();
      }
    }
    state = [];
  }

  void _setStatus(SpotubeFullTrackObject track, DownloadStatus status) {
    final previousStatus = state.firstWhereOrNull((e) => e.track.id == track.id)?.status;
    
    state = state.map((e) {
      if (e.track.id == track.id) {
        if ((status == DownloadStatus.canceled) && e.cancelToken.isCancelled) {
          e.cancelToken.cancel();
        }

        return e.copyWith(status: status);
      }
      return e;
    }).toList();
    
    // Record analytics for status changes
    if (previousStatus != status) {
      switch (status) {
        case DownloadStatus.completed:
          s3Analytics.recordDownloadSuccess();
          break;
        case DownloadStatus.failed:
          s3Analytics.recordDownloadFailure();
          break;
        case DownloadStatus.canceled:
          s3Analytics.recordDownloadCancellation();
          break;
        default:
          break;
      }
    }
    
    // Invalidate local tracks provider when a download completes
    // This ensures the UI updates to show the downloaded iconography
    if (status == DownloadStatus.completed) {
      ref.invalidate(localTracksProvider);
    }
  }

  bool _isShowingDialog = false;

  Future<bool> _shouldReplaceFileOnExist(DownloadTask task) async {
    if (rootNavigatorKey.currentContext == null || _isShowingDialog) {
      return false;
    }
    final replaceAll = ref.read(replaceDownloadedFileState);
    if (replaceAll != null) return replaceAll;
    _isShowingDialog = true;
    try {
      return await showDialog<bool>(
            context: rootNavigatorKey.currentContext!,
            builder: (context) => ReplaceDownloadedDialog(
              track: task.track,
            ),
          ) ??
          false;
    } finally {
      _isShowingDialog = false;
    }
  }

  /// Check if a track is an S3 track.
  bool _isS3Track(SpotubeFullTrackObject track) {
    return (track.externalUri != null &&
            track.externalUri!.startsWith('https://') &&
            track.externalUri!.contains('s3.amazonaws.com')) ||
        track.id.startsWith('music/');
  }

  Future<void> _downloadTrack(DownloadTask task) async {
    // Route S3 tracks to dedicated S3 download method
    if (_isS3Track(task.track)) {
      return _downloadS3Track(task);
    }

    // Original download logic for non-S3 tracks
    try {
      _setStatus(task.track, DownloadStatus.downloading);
      AppLogger.log.i('[DownloadManager] Starting download for ${task.track.id}');
      
      final track = await ref.read(sourcedTrackProvider(task.track).future);
      AppLogger.log.i('[DownloadManager] Got sourced track, url=${track.url}');
      
      if (task.cancelToken.isCancelled) {
        _setStatus(task.track, DownloadStatus.canceled);
        return;
      }
      final presets = ref.read(audioSourcePresetsProvider);
      final container =
          presets.presets[presets.selectedDownloadingContainerIndex];
      final downloadLocation = ref.read(
          userPreferencesProvider.select((value) => value.downloadLocation));

      AppLogger.log.i('[DownloadManager] Download location: $downloadLocation');
      AppLogger.log.i('[DownloadManager] Container: ${container.name}');

      final url = track.getUrlOfQuality(
        container,
        presets.selectedDownloadingQualityIndex,
      );

      AppLogger.log.i('[DownloadManager] Download URL: $url');

      if (url == null) {
        AppLogger.log.e('[DownloadManager] No download URL found for ${task.track.id}');
        throw Exception("No download URL found for selected codec");
      }

      final savePath = join(
        downloadLocation,
        ServiceUtils.sanitizeFilename(
          "${track.query.name} - ${track.query.artists.map((e) => e.name).join(", ")}.${container.getFileExtension()}",
        ),
      );

      final savePathFile = File(savePath);
      if (await savePathFile.exists()) {
        // dio automatically replaces the file if it exists so no deletion required
        if (!await _shouldReplaceFileOnExist(task)) {
          _setStatus(track.query, DownloadStatus.completed);
          return;
        }
      }

      AppLogger.log.i('[DownloadManager] Starting chunk download to $savePath');
      
      final response = await dio.chunkDownload(
        url,
        savePath,
        cancelToken: task.cancelToken,
        onReceiveProgress: (count, total) {
          if (task.totalSizeBytes == null) {
            state = state.map((e) {
              if (e.track.id == track.query.id) {
                return e.copyWith(totalSizeBytes: total);
              }
              return e;
            }).toList();
          }
          task._downloadedBytesStreamController.add(count);
        },
        deleteOnError: true,
        fileAccessMode: FileAccessMode.write,
      );
      
      AppLogger.log.i('[DownloadManager] Download response: statusCode=${response.statusCode}');
      
      if (response.statusCode != null && response.statusCode! < 400) {
        AppLogger.log.i('[DownloadManager] Download completed successfully for ${track.query.id}');
        _setStatus(track.query, DownloadStatus.completed);
      } else {
        AppLogger.log.e('[DownloadManager] Download failed for ${track.query.id}: statusCode=${response.statusCode}');
        _setStatus(track.query, DownloadStatus.failed);
        return;
      }

      if (container.getFileExtension() == "weba") return;

      final imageBytes = await ServiceUtils.downloadImage(
        (task.track.album.images).asUrlString(
          placeholder: ImagePlaceholder.albumArt,
          index: 1,
        ),
      );
      await MetadataGod.writeMetadata(
        file: savePath,
        metadata: task.track.toMetadata(
          fileLength: await savePathFile.length(),
          imageBytes: imageBytes,
        ),
      );
    } catch (e, stack) {
      if (e is! DioException || e.type != DioExceptionType.cancel) {
        AppLogger.log.e(
          '[DownloadManager] Download error for ${task.track.id}',
          error: e,
          stackTrace: stack,
        );
        _setStatus(task.track, DownloadStatus.failed);
        AppLogger.reportError(e, stack);
      } else {
        AppLogger.log.i('[DownloadManager] Download canceled for ${task.track.id}');
      }
    }
  }

  /// Download an S3 track directly from S3, bypassing sourcedTrackProvider.
  Future<void> _downloadS3Track(DownloadTask task) async {
    try {
      _setStatus(task.track, DownloadStatus.downloading);
      AppLogger.log.i('[DownloadManager] Starting S3 download for ${task.track.id}');

      // Get S3 track from provider
      final s3Tracks = await ref.read(s3TracksProvider.future);
      final s3Track = s3Tracks.firstWhereOrNull(
        (s3t) => s3t.key == task.track.id,
      );

      if (s3Track == null) {
        AppLogger.log.e('[DownloadManager] S3 track not found for ${task.track.id}');
        _setStatus(task.track, DownloadStatus.failed);
        return;
      }

      if (task.cancelToken.isCancelled) {
        _setStatus(task.track, DownloadStatus.canceled);
        return;
      }

      final downloadLocation = ref.read(
        userPreferencesProvider.select((value) => value.downloadLocation),
      );

      if (downloadLocation.isEmpty) {
        AppLogger.log.e('[DownloadManager] Download location not set');
        _setStatus(task.track, DownloadStatus.failed);
        return;
      }

      // Check download directory exists and is writable
      final downloadDir = Directory(downloadLocation);
      if (!await downloadDir.exists()) {
        try {
          await downloadDir.create(recursive: true);
        } catch (e, stack) {
          AppLogger.log.e('[DownloadManager] Failed to create download directory', error: e, stackTrace: stack);
          _setStatus(task.track, DownloadStatus.failed);
          return;
        }
      }

      // Check if directory is writable
      try {
        final testFile = File('${downloadDir.path}/.write_test');
        await testFile.writeAsString('test');
        await testFile.delete();
      } catch (e, stack) {
        AppLogger.log.e('[DownloadManager] Download directory not writable', error: e, stackTrace: stack);
        _setStatus(task.track, DownloadStatus.failed);
        return;
      }

      // Determine file extension from URL or track key
      final extension = s3Track.key.split('.').last.toLowerCase();
      final sanitizedFilename = ServiceUtils.sanitizeFilename(
        "${task.track.name} - ${task.track.artists.map((e) => e.name).join(", ")}.$extension",
      );

      final savePath = join(downloadLocation, sanitizedFilename);
      final savePathFile = File(savePath);

      if (await savePathFile.exists()) {
        if (!await _shouldReplaceFileOnExist(task)) {
          _setStatus(task.track, DownloadStatus.completed);
          return;
        }
      }

      AppLogger.log.i('[DownloadManager] Starting S3 chunk download to $savePath');

      // Download with progress tracking and retry logic
      int retryCount = 0;
      const maxRetries = 3;
      const baseDelay = Duration(seconds: 1);

      while (retryCount < maxRetries) {
        try {
          final response = await dio.chunkDownload(
            s3Track.url,
            savePath,
            cancelToken: task.cancelToken,
            onReceiveProgress: (count, total) {
              if (task.totalSizeBytes == null && total > 0) {
                state = state.map((e) {
                  if (e.track.id == task.track.id) {
                    return e.copyWith(totalSizeBytes: total);
                  }
                  return e;
                }).toList();
              }
              task._downloadedBytesStreamController.add(count);
            },
            deleteOnError: true,
            fileAccessMode: FileAccessMode.write,
          );

          if (response.statusCode != null && response.statusCode! < 400) {
            AppLogger.log.i('[DownloadManager] S3 download completed successfully for ${task.track.id}');

            // Write metadata if possible
            try {
              final imageBytes = await ServiceUtils.downloadImage(
                (task.track.album.images).asUrlString(
                  placeholder: ImagePlaceholder.albumArt,
                  index: 1,
                ),
              );
              await MetadataGod.writeMetadata(
                file: savePath,
                metadata: task.track.toMetadata(
                  fileLength: await savePathFile.length(),
                  imageBytes: imageBytes,
                ),
              );
            } catch (e, stack) {
              AppLogger.log.w(
                '[DownloadManager] Failed to write metadata for ${task.track.id}',
                error: e,
                stackTrace: stack,
              );
              // Don't fail the download if metadata write fails
            }

            _setStatus(task.track, DownloadStatus.completed);
            return;
          } else {
            throw Exception('Download failed with status code: ${response.statusCode}');
          }
        } catch (e) {
          retryCount++;
          
          // Categorize error type
          final isNetworkError = e is DioException && (
            e.type == DioExceptionType.connectionTimeout ||
            e.type == DioExceptionType.receiveTimeout ||
            e.type == DioExceptionType.sendTimeout ||
            e.type == DioExceptionType.connectionError
          );
          
          final isServerError = e is DioException && 
            e.response != null && 
            e.response!.statusCode != null &&
            e.response!.statusCode! >= 500;
          
          final isClientError = e is DioException && 
            e.response != null && 
            e.response!.statusCode != null &&
            e.response!.statusCode! >= 400 &&
            e.response!.statusCode! < 500;
          
          // Don't retry on client errors (403, 404) - these are permanent failures
          if (isClientError && !isNetworkError) {
            AppLogger.log.e('[DownloadManager] Client error (${e is DioException ? e.response?.statusCode : 'unknown'}), not retrying');
            rethrow;
          }
          
          if (retryCount >= maxRetries) {
            // Provide user-friendly error message
            String errorMessage = 'Download failed';
            if (isNetworkError) {
              errorMessage = 'Network error: Unable to download. Please check your internet connection.';
            } else if (isServerError) {
              errorMessage = 'Server error: Please try again later.';
            } else if (e is DioException && e.response?.statusCode == 403) {
              errorMessage = 'Access denied: Unable to download this track.';
            } else if (e is DioException && e.response?.statusCode == 404) {
              errorMessage = 'Track not found: This track is no longer available.';
            }
            AppLogger.log.e('[DownloadManager] Download failed after $maxRetries retries: $errorMessage');
            rethrow;
          }

          // Exponential backoff for retryable errors
          final delay = Duration(milliseconds: baseDelay.inMilliseconds * (1 << (retryCount - 1)));
          AppLogger.log.w('[DownloadManager] S3 download retry $retryCount/$maxRetries after ${delay.inSeconds}s (error: ${e.toString()})');
          await Future.delayed(delay);
        }
      }
    } catch (e, stack) {
      if (e is DioException && e.type == DioExceptionType.cancel) {
        AppLogger.log.i('[DownloadManager] S3 download canceled for ${task.track.id}');
        _setStatus(task.track, DownloadStatus.canceled);
      } else {
        // Categorize final error for better user messaging
        String errorType = 'Unknown error';
        if (e is DioException) {
          if (e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              e.type == DioExceptionType.sendTimeout ||
              e.type == DioExceptionType.connectionError) {
            errorType = 'Network error';
          } else if (e.response?.statusCode == 403) {
            errorType = 'Access denied (403)';
          } else if (e.response?.statusCode == 404) {
            errorType = 'Track not found (404)';
          } else if (e.response?.statusCode != null && e.response!.statusCode! >= 500) {
            errorType = 'Server error';
          }
        } else if (e.toString().contains('FileSystemException') || 
                   e.toString().contains('Permission denied')) {
          errorType = 'Storage error';
        }
        
        AppLogger.log.e(
          '[DownloadManager] S3 download error for ${task.track.id}: $errorType',
          error: e,
          stackTrace: stack,
        );
        _setStatus(task.track, DownloadStatus.failed);
        AppLogger.reportError(e, stack);
      }
    }
  }

  Future<void> _startDownloading() async {
    for (final task in state) {
      if (task.status == DownloadStatus.downloading) return;

      if (task.status == DownloadStatus.queued) {
        try {
          await _downloadTrack(task);
        } finally {
          // After completion, check for more queued tasks
          // Ignore errors of the prior task to allow next task to complete
          await _startDownloading();
        }
      }
    }
  }
}

final downloadManagerProvider =
    NotifierProvider<DownloadManagerNotifier, List<DownloadTask>>(
  DownloadManagerNotifier.new,
);
