import 'dart:async';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class DiscordNotifier extends AsyncNotifier<void> {
  @override
  FutureOr<void> build() async {
    // Discord RPC disabled
  }

  Future<void> updatePresence(dynamic track) async {}
  Future<void> clear() async {}
  Future<void> close() async {}
}

final discordProvider =
    AsyncNotifierProvider<DiscordNotifier, void>(() => DiscordNotifier());
