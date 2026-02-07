import 'package:auto_route/auto_route.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:shadcn_flutter/shadcn_flutter_extension.dart';
import 'package:spotube/collections/routes.gr.dart';

import 'package:spotube/collections/spotube_icons.dart';
import 'package:spotube/extensions/constrains.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/modules/player/player_queue.dart';
import 'package:spotube/components/heart_button/heart_button.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/provider/download_manager_provider.dart';
import 'package:spotube/provider/audio_player/audio_player.dart';
import 'package:spotube/provider/local_tracks/local_tracks_provider.dart';
import 'package:spotube/provider/metadata_plugin/core/auth.dart';

class PlayerActions extends HookConsumerWidget {
  final MainAxisAlignment mainAxisAlignment;
  final bool floatingQueue;
  final bool showQueue;
  final List<Widget>? extraActions;

  const PlayerActions({
    this.mainAxisAlignment = MainAxisAlignment.center,
    this.floatingQueue = true,
    this.showQueue = true,
    this.extraActions,
    super.key,
  });

  @override
  Widget build(BuildContext context, ref) {
    final playlist = ref.watch(audioPlayerProvider);
    final isLocalTrack = playlist.activeTrack is SpotubeLocalTrackObject;
    ref.watch(downloadManagerProvider);
    final downloader = ref.watch(downloadManagerProvider.notifier);
    final isInQueue = useMemoized(() {
      if (playlist.activeTrack is! SpotubeFullTrackObject) return false;
      final downloadTask =
          downloader.getTaskByTrackId(playlist.activeTrack!.id);
      return const [
        DownloadStatus.queued,
        DownloadStatus.downloading,
      ].contains(downloadTask?.status);
    }, [
      playlist.activeTrack,
      downloader,
    ]);

    final localTracks = ref.watch(localTracksProvider).value;
    final authenticated = ref.watch(metadataPluginAuthenticatedProvider);

    final isDownloaded = useMemoized(() {
      return localTracks?.values.expand((e) => e).any(
                (element) =>
                    element.name == playlist.activeTrack?.name &&
                    element.album.name == playlist.activeTrack?.album.name &&
                    element.artists.asString() ==
                        playlist.activeTrack?.artists.asString(),
              ) ==
          true;
    }, [localTracks, playlist.activeTrack]);
    return Row(
      mainAxisAlignment: mainAxisAlignment,
      children: [
        if (showQueue)
          Tooltip(
            tooltip: TooltipContainer(child: Text(context.l10n.queue)).call,
            child: IconButton.ghost(
              icon: const Icon(SpotubeIcons.queue),
              enabled: playlist.activeTrack != null,
              onPressed: () {
                openDrawer(
                  context: context,
                  position: OverlayPosition.right,
                  transformBackdrop: false,
                  draggable: false,
                  surfaceBlur: context.theme.surfaceBlur,
                  surfaceOpacity: 0.7,
                  builder: (context) {
                    return Container(
                      constraints: const BoxConstraints(maxWidth: 800),
                      child: Consumer(
                        builder: (context, ref, _) {
                          final playlist = ref.watch(audioPlayerProvider);
                          final playlistNotifier =
                              ref.read(audioPlayerProvider.notifier);

                          return PlayerQueue.fromAudioPlayerNotifier(
                            floating: true,
                            playlist: playlist,
                            notifier: playlistNotifier,
                          );
                        },
                      ),
                    );
                  },
                );
              },
            ),
          ),
        // Alternate sources icon removed for S3-only app
        if (!kIsWeb && !isLocalTrack)
          if (isInQueue)
            const SizedBox(
              height: 20,
              width: 20,
              child: CircularProgressIndicator(
                size: 2,
              ),
            )
          else
            Tooltip(
              tooltip:
                  TooltipContainer(child: Text(context.l10n.download_track))
                      .call,
              child: IconButton.ghost(
                icon: Icon(
                  isDownloaded ? SpotubeIcons.done : SpotubeIcons.download,
                ),
                onPressed: playlist.activeTrack != null
                    ? () => downloader.addToQueue(
                        playlist.activeTrack! as SpotubeFullTrackObject)
                    : null,
              ),
            ),
        if (playlist.activeTrack != null &&
            !isLocalTrack &&
            authenticated.asData?.value == true)
          TrackHeartButton(track: playlist.activeTrack!),
        // Sleep timer (snooze) button removed from fullscreen player
        ...(extraActions ?? [])
      ],
    );
  }
}
