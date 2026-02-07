import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/provider/metadata_plugin/metadata_plugin_provider.dart';
import 'package:spotube/services/metadata/errors/exceptions.dart';

final metadataPluginSearchAllProvider =
    FutureProvider.autoDispose.family<SpotubeSearchResponseObject, String>(
  (ref, query) async {
    final metadataPlugin = await ref.watch(metadataPluginProvider.future);

    if (metadataPlugin == null) {
    return SpotubeSearchResponseObject(
      albums: const [],
      artists: const [],
      playlists: const [],
      tracks: const [],
    );
    }

    return metadataPlugin.search.all(query);
  },
);

final metadataPluginSearchChipsProvider = FutureProvider((ref) async {
  // Bombest Beats: fallback to simple chips when no plugin is present
  final metadataPlugin = await ref.watch(metadataPluginProvider.future);
  if (metadataPlugin == null) {
    return const ["all"];
  }
  return metadataPlugin.search.chips;
});
