import 'package:spotube/services/youtube_engine/youtube_engine.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';
import 'package:http_parser/http_parser.dart';
import 'package:spotube/services/logger/logger.dart';

import 's3_repository.dart';
import 'models/s3_track.dart';

class S3MusicEngine implements YouTubeEngine {
  final S3Repository _repository;
  List<S3Track> _cachedTracks = [];

  S3MusicEngine() : _repository = S3Repository();

  /// Initialize and fetch tracks if not already fetched
  Future<void> _ensureTracks() async {
    if (_cachedTracks.isEmpty) {
      _cachedTracks = await _repository.fetchTracks();
    }
  }

  @override
  Future<List<Video>> searchVideos(String query) async {
    await _ensureTracks();

    // Simple case-insensitive search
    final lowerQuery = query.toLowerCase();
    final matches = _cachedTracks.where((track) {
      return track.filename.toLowerCase().contains(lowerQuery) ||
             track.title.toLowerCase().contains(lowerQuery);
    });

    return matches.map((track) => _toVideo(track)).toList();
  }

  @override
  Future<Video> getVideo(String videoId) async {
    await _ensureTracks();
    final track = _cachedTracks.firstWhere(
      (t) => t.key == videoId, // We use S3 Key as Video ID
      orElse: () => throw Exception('Track not found: $videoId'),
    );
    return _toVideo(track);
  }

  @override
  Future<StreamManifest> getStreamManifest(String videoId) async {
    await _ensureTracks();
    final track = _cachedTracks.firstWhere(
      (t) => t.key == videoId,
      orElse: () => throw Exception('Track not found: $videoId'),
    );

    final url = _repository.getTrackUrl(track);
    final size = track.size;
    final bitrate = 128000; // Dummy constant

    AppLogger.log.i(
      "[S3MusicEngine] manifest url=$url size=$size key=${track.key} region=${track.region}",
    );

    // AudioOnlyStreamInfo(VideoId, tag, url, container, size, bitrate, audioCodec, quality, fragments, codec, audioTrack)
    // Note: We use 'c.Container' because Container is hidden in main import but imported via 'c'.
    final streamInfo = AudioOnlyStreamInfo(
      VideoId(track.key),
      140,
      Uri.parse(url),
      StreamContainer.mp4,
      FileSize(size),
      Bitrate(bitrate),
      'mp3',
      'medium',
      const [],
      MediaType('audio', 'mpeg'),
      null,
    );

    return StreamManifest([streamInfo]);
  }

  @override
  Future<(Video, StreamManifest)> getVideoWithStreamInfo(String videoId) async {
    final video = await getVideo(videoId);
    final manifest = await getStreamManifest(videoId);
    return (video, manifest);
  }

  @override
  @override
  void dispose() {
    _cachedTracks.clear();
  }

  Video _toVideo(S3Track track) {
    // We construct a Video object.
    AppLogger.log.d(
      "[S3MusicEngine] toVideo key=${track.key} url=${track.url} size=${track.size}",
    );
    return Video(
      VideoId(track.key),
      track.title,
      "Bombest Beats", // Author
      ChannelId("UC_bombest_beats"), // Dummy Channel ID
      track.lastModified,
      track.lastModified.toString(),
      track.lastModified,
      "S3 Music Track", // Description
      Duration(minutes: 3), // DUMMY Duration! We don't know it without metadata extraction.
      ThumbnailSet(track.url), // No thumbnail, use track url as placeholder or null
      [], // keywords
      Engagement(0, 0, 0),
      false, // isLive
    );
  }
}
