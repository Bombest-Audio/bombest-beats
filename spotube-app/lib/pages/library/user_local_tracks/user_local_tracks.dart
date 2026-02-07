import 'package:auto_route/auto_route.dart';
import 'package:flutter/material.dart' as material hide Scaffold, Switch, Divider, LinearProgressIndicator, CircularProgressIndicator, IconButton;
import 'package:flutter/material.dart' show MediaQuery, Size;
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:spotube/collections/spotube_icons.dart';
import 'package:spotube/components/track_tile/track_tile.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/provider/audio_player/audio_player.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/provider/audio_player/state.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_providers.dart';
import 'package:spotube/plugins/s3_bombest/lib/utils.dart';
import 'package:spotube/plugins/s3_bombest/lib/models/s3_track.dart';
import 'package:spotube/components/fallbacks/error_box.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:spotube/collections/fake.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/services/audio_player/audio_player.dart' as audio_service;
import 'package:spotube/provider/download_manager_provider.dart';
import 'package:spotube/provider/local_tracks/local_tracks_provider.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:collection/collection.dart';

enum SortBy {
  none,
  ascending,
  descending,
  artist,
  album,
  duration,
  newest,
  oldest,
}

@RoutePage()
class UserLocalLibraryPage extends HookConsumerWidget {
  static const name = 'user_local_library';
  const UserLocalLibraryPage({super.key});

  @override
  Widget build(BuildContext context, ref) {
    AppLogger.log.d('[UserLocalLibraryPage] build() called');
    print('[UserLocalLibraryPage] build() called');
    
    // Watch the provider - ref.watch automatically triggers it
    final s3TracksQuery = ref.watch(s3TracksProvider);
    
    // Monitor MediaQuery for configuration changes (fold transitions, rotation, etc.)
    final mediaQuery = MediaQuery.of(context);
    final screenSize = mediaQuery.size;
    final previousScreenSize = useRef<Size?>(null);
    
    // Detect significant configuration changes (e.g., fold transitions)
    useEffect(() {
      final prevSize = previousScreenSize.value;
      if (prevSize != null) {
        final sizeChange = (screenSize.width - prevSize.width).abs() + 
                          (screenSize.height - prevSize.height).abs();
        // If screen size changed significantly (more than 200 pixels total), reload
        if (sizeChange > 200) {
          AppLogger.log.i('[UserLocalLibraryPage] Significant screen size change detected (${sizeChange.toStringAsFixed(0)}px), reloading provider');
          WidgetsBinding.instance.addPostFrameCallback((_) {
            ref.invalidate(s3TracksProvider);
            ref.read(s3TracksProvider.future).catchError((e) {
              AppLogger.log.e('[UserLocalLibraryPage] Config change reload error', error: e);
              return <S3Track>[];
            });
          });
        }
      }
      previousScreenSize.value = screenSize;
      return null;
    }, [screenSize.width, screenSize.height]);
    
    // Improved provider trigger logic - more aggressive detection of stale states
    // This catches edge cases where provider needs reload but isn't automatically triggered
    if (!s3TracksQuery.isLoading && !s3TracksQuery.hasValue && !s3TracksQuery.hasError) {
      print('[UserLocalLibraryPage] Provider not started, triggering explicitly');
      AppLogger.log.d('[UserLocalLibraryPage] Provider not started, triggering explicitly');
      WidgetsBinding.instance.addPostFrameCallback((_) {
        // Invalidate first to ensure fresh load
        ref.invalidate(s3TracksProvider);
        ref.read(s3TracksProvider.future).catchError((e) {
          print('[UserLocalLibraryPage] Provider trigger error: $e');
          AppLogger.log.e('[UserLocalLibraryPage] Provider trigger error: $e');
          return <S3Track>[]; // Return empty list on error
        });
      });
    }
    
    print('[UserLocalLibraryPage] Provider state: isLoading=${s3TracksQuery.isLoading}, hasValue=${s3TracksQuery.hasValue}, hasError=${s3TracksQuery.hasError}');
    AppLogger.log.d('[UserLocalLibraryPage] Provider state: isLoading=${s3TracksQuery.isLoading}, hasValue=${s3TracksQuery.hasValue}, hasError=${s3TracksQuery.hasError}');
    
    final playlist = ref.watch(audioPlayerProvider);
    final playlistNotifier = ref.watch(audioPlayerProvider.notifier);
    final downloadQueue = ref.watch(downloadManagerProvider);
    final downloadManagerNotifier = ref.watch(downloadManagerProvider.notifier);
    final localTracksQuery = ref.watch(localTracksProvider);
    final autoDownloadAll = ref.watch(
      userPreferencesProvider.select((p) => p.autoDownloadAll),
    );
    final preferencesNotifier = ref.watch(userPreferencesProvider.notifier);

    // Get S3 tracks data, preserving it during loading states
    // Declare these early so they can be used in useEffect hooks below
    final s3Tracks = s3TracksQuery.asData?.value;
    final s3TracksIsLoading = s3TracksQuery.isLoading;
    final s3TracksError = s3TracksQuery.hasError ? s3TracksQuery.error : null;

    // Enhanced provider reload logic - detects stale states and reloads
    // This handles cases where provider is disposed during configuration changes
    useEffect(() {
      // Reload provider if in stale state (no data, not loading, no error)
      // This catches cases where provider was disposed during fold transitions
      if (!s3TracksIsLoading && s3Tracks == null && s3TracksError == null) {
        AppLogger.log.w('[UserLocalLibraryPage] Detected stale provider state, reloading...');
        WidgetsBinding.instance.addPostFrameCallback((_) {
          ref.invalidate(s3TracksProvider);
          ref.read(s3TracksProvider.future).catchError((e) {
            AppLogger.log.e('[UserLocalLibraryPage] Provider reload error', error: e);
            return <S3Track>[];
          });
        });
      }
      return null;
    }, [s3TracksIsLoading, s3Tracks, s3TracksError]);
    
    // Route visibility detection - reload when page becomes active
    // This handles navigation back to the page and fold transitions
    final router = context.watchRouter;
    final isCurrentRoute = useRef<bool>(false);
    final previousPath = useRef<String?>(null);
    
    useEffect(() {
      // Check if we're on the library/local route
      final currentPath = router.currentPath;
      final isLibraryRoute = currentPath.contains('/library/local') || currentPath == '/library';
      
      // If path changed and we're now on library route, check if we need to reload
      if (isLibraryRoute && previousPath.value != currentPath) {
        previousPath.value = currentPath;
        if (!isCurrentRoute.value) {
          isCurrentRoute.value = true;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            // Only reload if we don't have data and not already loading
            if (s3Tracks == null && !s3TracksIsLoading) {
              AppLogger.log.i('[UserLocalLibraryPage] Route became active but no data, reloading provider');
              ref.invalidate(s3TracksProvider);
              ref.read(s3TracksProvider.future).catchError((e) {
                AppLogger.log.e('[UserLocalLibraryPage] Route visibility reload error', error: e);
                return <S3Track>[];
              });
            }
          });
        }
      } else if (!isLibraryRoute) {
        isCurrentRoute.value = false;
        previousPath.value = currentPath;
      }
      return null;
    }, [router.currentPath, s3Tracks, s3TracksIsLoading]);

    // Auto-download logic removed - user must manually toggle "Download all" to start downloads
    // This prevents automatic re-downloading prompts on every app launch

    // Count active downloads
    final activeDownloadCount = downloadQueue
        .where((task) =>
            task.status == DownloadStatus.downloading ||
            task.status == DownloadStatus.queued)
        .length;

    // Get local tracks data, preserving it during loading states
    final localTracksData = localTracksQuery.asData?.value;
    final localTracksIsLoading = localTracksQuery.isLoading;
    final localTracksError = localTracksQuery.hasError ? localTracksQuery.error : null;

    // Convert all S3 tracks to Spotube tracks (preserve during rotation)
    // Use useState + useEffect to preserve converted tracks across rebuilds
    // This ensures the list doesn't disappear during rotation
    final tracksState = useState<List<SpotubeFullTrackObject>>(<SpotubeFullTrackObject>[]);
    final lastS3TracksCount = useRef<int?>(null);
    final lastS3TracksFirstKey = useRef<String?>(null);
    
    useEffect(() {
      if (s3Tracks == null) {
        // Don't clear tracks if we're just rotating - preserve existing tracks
        // Only clear if we truly have no data and we haven't had data before
        if (lastS3TracksCount.value == null) {
          print('[UserLocalLibraryPage] useEffect: s3Tracks is null, clearing tracks');
          tracksState.value = <SpotubeFullTrackObject>[];
        } else {
          print('[UserLocalLibraryPage] useEffect: s3Tracks is null but we have cached tracks (count=${tracksState.value.length}), preserving during rotation');
        }
        return null;
      }
      
      // Compare by count and first track key to detect actual data changes
      // This is more robust than a full hash and handles rotation better
      final currentCount = s3Tracks.length;
      final currentFirstKey = s3Tracks.isNotEmpty ? s3Tracks.first.key : null;
      
      // Only update if the data actually changed (different count or different first track)
      if (lastS3TracksCount.value != currentCount || lastS3TracksFirstKey.value != currentFirstKey) {
        print('[UserLocalLibraryPage] useEffect: Converting ${s3Tracks.length} S3 tracks to Spotube tracks');
        try {
          final converted = s3Tracks.map((t) => s3TrackToSpotubeTrack(t)).toList();
          print('[UserLocalLibraryPage] useEffect: Successfully converted to ${converted.length} tracks');
          if (converted.isNotEmpty) {
            print('[UserLocalLibraryPage] useEffect: First track: ${converted.first.name} by ${converted.first.artists.first.name}');
          }
          tracksState.value = converted;
          lastS3TracksCount.value = currentCount;
          lastS3TracksFirstKey.value = currentFirstKey;
        } catch (e, stack) {
          print('[UserLocalLibraryPage] useEffect: Error converting tracks: $e');
          AppLogger.log.e('[UserLocalLibraryPage] Error converting tracks', error: e, stackTrace: stack);
          tracksState.value = <SpotubeFullTrackObject>[];
          lastS3TracksCount.value = null;
          lastS3TracksFirstKey.value = null;
        }
      } else {
        print('[UserLocalLibraryPage] useEffect: Track data unchanged (count=$currentCount), preserving existing converted tracks');
      }
      return null;
    }, [s3Tracks]);
    
    final tracks = tracksState.value;

    // Get local tracks list (use cached data if available, empty list if loading)
    final allLocalState = useState<List<SpotubeLocalTrackObject>>(<SpotubeLocalTrackObject>[]);
    final lastLocalTracksHash = useRef<String?>(null);
    
    useEffect(() {
      if (localTracksData == null) {
        if (lastLocalTracksHash.value == null) {
          allLocalState.value = <SpotubeLocalTrackObject>[];
        }
        return null;
      }
      
      final currentHash = localTracksData.keys.join(',');
      if (lastLocalTracksHash.value != currentHash) {
        allLocalState.value = localTracksData.values.expand((tracks) => tracks).toList();
        lastLocalTracksHash.value = currentHash;
      }
      return null;
    }, [localTracksData]);
    
    final allLocal = allLocalState.value;

    print('[UserLocalLibraryPage] Top-level: s3Tracks=${s3Tracks?.length ?? 'null'}, tracks.length=${tracks.length}, allLocal.length=${allLocal.length}');

    return Scaffold(
      child: Column(
        children: [
          // Header with Download All toggle
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Library', // Changed from context.l10n.local_library
                      ).semiBold(),
                      if (activeDownloadCount > 0)
                        AnimatedSwitcher(
                          duration: const Duration(milliseconds: 200),
                          child: Container(
                            key: ValueKey('download_count_$activeDownloadCount'),
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              color: material.Theme.of(context).colorScheme.primary.withOpacity(0.1),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 12,
                                  height: 12,
                                  child: material.CircularProgressIndicator(
                                    strokeWidth: 2,
                                    valueColor: AlwaysStoppedAnimation<material.Color>(
                                      material.Theme.of(context).colorScheme.primary,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  context.l10n.currently_downloading(activeDownloadCount),
                                ).small(),
                              ],
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Row(
                  children: [
                    Switch(
                      value: autoDownloadAll,
                      onChanged: (value) {
                        preferencesNotifier.setAutoDownloadAll(value);
                      },
                    ),
                    const SizedBox(width: 8),
                    Text(context.l10n.download_all).small(),
                    if (activeDownloadCount > 0) ...[
                      const SizedBox(width: 16),
                      Button.destructive(
                        onPressed: () {
                          downloadManagerNotifier.cancelQueuedOnly();
                        },
                        child: Text(context.l10n.cancel_all),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
          const Divider(),
          // Track list with pull-to-refresh
          Expanded(
            child: material.RefreshIndicator.adaptive(
              onRefresh: () async {
                AppLogger.log.i('[UserLocalLibraryPage] Pull-to-refresh triggered');
                // Invalidate and refresh the provider
                ref.invalidate(s3TracksProvider);
                // Wait for refresh to complete
                await ref.read(s3TracksProvider.future);
              },
              child: Builder(
                builder: (context) {
                  print('[UserLocalLibraryPage] Builder: s3Tracks=${s3Tracks?.length ?? 'null'}, isLoading=$s3TracksIsLoading, hasError=${s3TracksQuery.hasError}');
                  if (s3TracksError != null) {
                    print('[UserLocalLibraryPage] Builder: Error = $s3TracksError');
                  }

                  // Show error if S3 tracks failed and no cached data
                  if (s3TracksError != null && s3Tracks == null) {
                    return Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          ErrorBox(error: s3TracksError),
                          const SizedBox(height: 16),
                          Button(
                            style: ButtonVariance.outline,
                            onPressed: () {
                              ref.invalidate(s3TracksProvider);
                            },
                            child: Text(context.l10n.retry),
                          ),
                        ],
                      ),
                    );
                  }

                  // Show error if local tracks failed and we don't have S3 tracks yet
                  if (localTracksError != null && s3Tracks == null) {
                    return Center(
                      child: ErrorBox(error: localTracksError),
                    );
                  }

                  // Show loading skeleton if we have no S3 tracks data yet
                  if (s3Tracks == null) {
                    print('[UserLocalLibraryPage] Showing skeleton: isLoading=$s3TracksIsLoading, hasError=${s3TracksQuery.hasError}');
                    // Show skeleton if loading, or if no error (might be initializing)
                    if (s3TracksIsLoading || !s3TracksQuery.hasError) {
                      // Fallback: if we've been showing skeleton for a while and not loading, try reload
                      if (!s3TracksIsLoading && !s3TracksQuery.hasError) {
                        WidgetsBinding.instance.addPostFrameCallback((_) {
                          AppLogger.log.w('[UserLocalLibraryPage] Skeleton shown but not loading, triggering reload');
                          ref.invalidate(s3TracksProvider);
                          ref.read(s3TracksProvider.future).catchError((e) {
                            AppLogger.log.e('[UserLocalLibraryPage] Fallback reload error', error: e);
                            return <S3Track>[];
                          });
                        });
                      }
                      return AnimatedSwitcher(
                        duration: const Duration(milliseconds: 300),
                        transitionBuilder: (child, animation) {
                          return FadeTransition(
                            opacity: animation,
                            child: child,
                          );
                        },
                        child: Skeletonizer(
                          key: const ValueKey('skeleton'),
                          enabled: true,
                          child: ListView.builder(
                            key: const PageStorageKey('local_library_list'),
                            itemCount: 10,
                            itemBuilder: (context, index) => TrackTile(
                              index: index,
                              track: FakeData.track,
                              playlist: playlist,
                            ),
                          ),
                        ),
                      );
                    }
                    // If we have an error and no data, show error (handled below)
                  }

                  // Show empty state if no tracks
                  if (s3Tracks != null && s3Tracks.isEmpty) {
                    print('[UserLocalLibraryPage] Showing empty state: s3Tracks is empty');
                    return Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Text(context.l10n.no_tracks).muted(),
                          const SizedBox(height: 16),
                          Button(
                            style: ButtonVariance.outline,
                            onPressed: () {
                              ref.invalidate(s3TracksProvider);
                            },
                            child: const Text('Refresh'),
                          ),
                        ],
                      ),
                    );
                  }

                  // If we have S3 tracks, show the list (even if local tracks are loading)
                  // Show loading overlay for local tracks if loading
                  if (localTracksIsLoading && tracks.isNotEmpty) {
                    return AnimatedSwitcher(
                      duration: const Duration(milliseconds: 200),
                      child: Stack(
                        key: ValueKey('list_with_progress_${tracks.length}'),
                        children: [
                          _buildTrackList(
                            tracks: tracks,
                            allLocal: allLocal,
                            downloadQueue: downloadQueue,
                            playlist: playlist,
                            playlistNotifier: playlistNotifier,
                            downloadManagerNotifier: downloadManagerNotifier,
                          ),
                          Positioned(
                            top: 0,
                            left: 0,
                            right: 0,
                            child: AnimatedOpacity(
                              opacity: localTracksIsLoading ? 1.0 : 0.0,
                              duration: const Duration(milliseconds: 200),
                              child: LinearProgressIndicator(),
                            ),
                          ),
                        ],
                      ),
                    );
                  }

                  // Show error overlay for local tracks if error but we have S3 tracks
                  if (localTracksError != null && tracks.isNotEmpty) {
                    AppLogger.log.w(
                      '[UserLocalLibraryPage] Local tracks error, showing S3 tracks anyway',
                      error: localTracksError,
                    );
                  }

                  // Build the track list with current data
                  print('[UserLocalLibraryPage] Building track list: tracks.length=${tracks.length}, allLocal.length=${allLocal.length}');
                  if (tracks.isEmpty) {
                    print('[UserLocalLibraryPage] WARNING: tracks list is empty but s3Tracks has ${s3Tracks?.length ?? 0} items');
                  }
                  return _buildTrackList(
                    tracks: tracks,
                    allLocal: allLocal,
                    downloadQueue: downloadQueue,
                    playlist: playlist,
                    playlistNotifier: playlistNotifier,
                    downloadManagerNotifier: downloadManagerNotifier,
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTrackList({
    required List<SpotubeFullTrackObject> tracks,
    required List<SpotubeLocalTrackObject> allLocal,
    required List<DownloadTask> downloadQueue,
    required AudioPlayerState playlist,
    required AudioPlayerNotifier playlistNotifier,
    required DownloadManagerNotifier downloadManagerNotifier,
  }) {
    print('[UserLocalLibraryPage] _buildTrackList called with ${tracks.length} tracks');
    if (tracks.isEmpty) {
      print('[UserLocalLibraryPage] _buildTrackList: tracks list is empty, returning empty container');
      return const SizedBox.shrink();
    }
    return ListView.builder(
      key: const PageStorageKey('local_library_list'),
      padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 8),
      itemCount: tracks.length,
      itemBuilder: (context, index) {
        print('[UserLocalLibraryPage] _buildTrackList: Building item $index of ${tracks.length}');
        final track = tracks[index];
        final isPlaying = playlist.activeTrack?.id == track.id;

        // Get download status - wrap in try-catch to handle any errors
        DownloadStatus? downloadStatus;
        DownloadTask? downloadTask;
        try {
          downloadStatus = getTrackDownloadStatus(
            track,
            downloadQueue,
            allLocal,
          );
          downloadTask = downloadQueue.firstWhereOrNull(
            (task) => task.track.id == track.id,
          );
        } catch (e, stack) {
          AppLogger.log.e(
            '[UserLocalLibraryPage] Error getting download status for ${track.id}',
            error: e,
            stackTrace: stack,
          );
          downloadStatus = null;
          downloadTask = null;
        }

        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: _TrackTileWithDownloadStatus(
            track: track,
            playlist: playlist,
            playlistNotifier: playlistNotifier,
            tracks: tracks,
            index: index,
            isPlaying: isPlaying,
            downloadStatus: downloadStatus,
            downloadTask: downloadTask,
            downloadManagerNotifier: downloadManagerNotifier,
          ),
        );
      },
    );
  }
}

class _TrackTileWithDownloadStatus extends HookConsumerWidget {
  final SpotubeFullTrackObject track;
  final AudioPlayerState playlist;
  final AudioPlayerNotifier playlistNotifier;
  final List<SpotubeFullTrackObject> tracks;
  final int index;
  final bool isPlaying;
  final DownloadStatus? downloadStatus;
  final DownloadTask? downloadTask;
  final DownloadManagerNotifier downloadManagerNotifier;

  const _TrackTileWithDownloadStatus({
    required this.track,
    required this.playlist,
    required this.playlistNotifier,
    required this.tracks,
    required this.index,
    required this.isPlaying,
    required this.downloadStatus,
    required this.downloadTask,
    required this.downloadManagerNotifier,
  });

  @override
  Widget build(BuildContext context, ref) {
    return TrackTile(
      index: null,
      track: track,
      playlist: playlist,
      userPlaylist: false,
      onTap: () async {
        if (isPlaying) {
          if (playlist.playing) {
            await audio_service.audioPlayer.pause();
          } else {
            await audio_service.audioPlayer.resume();
          }
        } else {
          await playlistNotifier.load(
            tracks,
            initialIndex: index,
            autoPlay: true,
          );
        }
      },
      leadingActions: [
        // Show download status indicator in leading area (left side) for better visibility
        // Always show iconography to differentiate downloaded vs undownloaded tracks
        Padding(
          padding: const EdgeInsets.only(right: 8),
          child: _DownloadStatusIndicator(
            status: downloadStatus,
            task: downloadTask,
            onDownload: () {
              // If download failed or was canceled, retry it; otherwise add to queue
              if (downloadStatus == DownloadStatus.failed || 
                  downloadStatus == DownloadStatus.canceled) {
                downloadManagerNotifier.retry(track);
              } else {
                downloadManagerNotifier.addToQueue(track);
              }
            },
            onCancel: () {
              downloadManagerNotifier.cancel(track);
            },
          ),
        ),
      ],
    );
  }
}

class _DownloadStatusIndicator extends HookConsumerWidget {
  final DownloadStatus? status;
  final DownloadTask? task;
  final VoidCallback onDownload;
  final VoidCallback onCancel;

  const _DownloadStatusIndicator({
    required this.status,
    required this.task,
    required this.onDownload,
    required this.onCancel,
  });

  @override
  Widget build(BuildContext context, ref) {
    if (status == null) {
      // Not downloaded - show download button
      return IconButton.ghost(
        icon: const Icon(SpotubeIcons.download, size: 20),
        onPressed: onDownload,
      );
    }

    return switch (status!) {
      DownloadStatus.downloading => HookBuilder(
          builder: (context) {
            if (task == null) return const SizedBox.shrink();
            return StreamBuilder<int>(
              stream: task!.downloadedBytesStream,
              builder: (context, snapshot) {
                final progress = task!.totalSizeBytes == null ||
                        task!.totalSizeBytes == 0
                    ? 0.0
                    : (snapshot.data ?? 0) / task!.totalSizeBytes!;

                return SizedBox(
                  width: 20,
                  height: 20,
                  child: Stack(
                    children: [
                      material.CircularProgressIndicator(
                        value: progress,
                        strokeWidth: 2,
                      ),
                      Center(
                        child: GestureDetector(
                          onTap: onCancel,
                          child: const Icon(SpotubeIcons.close, size: 12),
                        ),
                      ),
                    ],
                  ),
                );
              },
            );
          },
        ),
      DownloadStatus.queued => IconButton.ghost(
          icon: const Icon(SpotubeIcons.queueAdd, size: 20),
          onPressed: onCancel,
        ),
      DownloadStatus.completed => Icon(
          SpotubeIcons.done,
          size: 20,
          color: material.Colors.green[600],
        ), // Show checkmark for downloaded tracks
      DownloadStatus.failed => IconButton.ghost(
          icon: Icon(SpotubeIcons.error, size: 20, color: material.Colors.red[400]),
          onPressed: onDownload,
        ),
      DownloadStatus.canceled => IconButton.ghost(
          icon: const Icon(SpotubeIcons.download, size: 20),
          onPressed: onDownload,
        ),
    };
  }
}
