import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:spotube/models/metadata/metadata.dart';
import 'package:spotube/provider/metadata_plugin/metadata_plugin_provider.dart';
import 'package:spotube/provider/metadata_plugin/utils/common.dart';
import 'package:spotube/provider/metadata_plugin/utils/family_paginated.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_providers.dart';
import 'package:spotube/plugins/s3_bombest/lib/utils.dart';

class MetadataPluginSearchTracksNotifier
    extends AutoDisposeFamilyPaginatedAsyncNotifier<SpotubeFullTrackObject,
        String> {
  MetadataPluginSearchTracksNotifier() : super();

  @override
  fetch(offset, limit) async {
    if (arg.isEmpty) {
      return SpotubePaginationResponseObject<SpotubeFullTrackObject>(
        limit: limit,
        nextOffset: null,
        total: 0,
        items: [],
        hasMore: false,
      );
    }

    // Bombest Beats: Search S3 Tracks directly
    final s3Tracks = await ref.read(s3TracksProvider.future);
    final query = arg.toLowerCase();
    
    final matches = s3Tracks.where((t) => 
      t.title.toLowerCase().contains(query) || 
      t.filename.toLowerCase().contains(query)
    ).toList();

    // Pagination
    final total = matches.length;
    final end = (offset + limit) > total ? total : (offset + limit);
    final paginated = matches.sublist(offset, end);
    final hasMore = end < total;

    final spotubeTracks = paginated.map((t) => s3TrackToSpotubeTrack(t)).toList();

    return SpotubePaginationResponseObject<SpotubeFullTrackObject>(
      items: spotubeTracks,
      total: total,
      limit: limit,
      nextOffset: hasMore ? end : null,
      hasMore: hasMore,
    );
  }

  @override
  build(arg) async {
    ref.cacheFor();

    ref.watch(metadataPluginProvider);
    return await fetch(0, 20);
  }
}

final metadataPluginSearchTracksProvider =
    AutoDisposeAsyncNotifierProviderFamily<MetadataPluginSearchTracksNotifier,
        SpotubePaginationResponseObject<SpotubeFullTrackObject>, String>(
  () => MetadataPluginSearchTracksNotifier(),
);
