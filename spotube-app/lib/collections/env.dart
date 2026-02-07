import 'package:envied/envied.dart';
import 'package:spotube/utils/platform.dart';

part 'env.g.dart';

enum ReleaseChannel {
  nightly,
  stable,
}

@Envied(obfuscate: true, requireEnvFile: true, path: ".env")
abstract class Env {
  @EnviedField(varName: 'LASTFM_API_KEY')
  static final String lastFmApiKey = _Env.lastFmApiKey;

  @EnviedField(varName: 'LASTFM_API_SECRET')
  static final String lastFmApiSecret = _Env.lastFmApiSecret;

  // @EnviedField(varName: 'HIDE_DONATIONS', defaultValue: "0")
  static const int _hideDonations = 0; // _Env._hideDonations;

  static bool get hideDonations => _hideDonations == 1;

  // @EnviedField(varName: 'ENABLE_UPDATE_CHECK', defaultValue: "1")
  static const String _enableUpdateChecker = "0"; // Bombest: disable update checker

  // @EnviedField(varName: "RELEASE_CHANNEL", defaultValue: "nightly")
  static const String _releaseChannel = "stable"; // Bombest: stable channel

  static ReleaseChannel get releaseChannel => _releaseChannel == "stable"
      ? ReleaseChannel.stable
      : ReleaseChannel.nightly;

  static bool get enableUpdateChecker =>
      kIsFlatpak || _enableUpdateChecker == "1";

  static String discordAppId = "1176718791388975124";
}
