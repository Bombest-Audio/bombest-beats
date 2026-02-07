import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotube/models/database/database.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
// import 'package:spotube/services/youtube_engine/newpipe_engine.dart';
// import 'package:spotube/services/youtube_engine/youtube_explode_engine.dart';
// import 'package:spotube/services/youtube_engine/yt_dlp_engine.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_bombest_plugin.dart';

final youtubeEngineProvider = Provider((ref) {
  // Bombest Beats: Use S3MusicEngine by default
  return S3MusicEngine();
});
