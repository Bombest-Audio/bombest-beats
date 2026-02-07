import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/services/s3_sync/s3_sync_service.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/services/connectivity_adapter.dart';

/// Background sync service that handles periodic syncing and sync on app resume.
/// Respects battery/data saver settings and autoDownloadAll preference.
class BackgroundSyncService with WidgetsBindingObserver {
  final Ref _ref;
  Timer? _periodicSyncTimer;
  bool _isInitialized = false;
  DateTime? _lastSyncTime;
  static const Duration _syncInterval = Duration(hours: 6); // Sync every 6 hours

  BackgroundSyncService(this._ref);

  /// Initialize the background sync service.
  /// Should be called once when the app starts.
  void initialize() {
    if (_isInitialized) {
      AppLogger.log.w('[BackgroundSyncService] Already initialized');
      return;
    }

    AppLogger.log.i('[BackgroundSyncService] Initializing background sync service');
    WidgetsBinding.instance.addObserver(this);
    _isInitialized = true;

    // Start periodic sync if autoDownloadAll is enabled
    _checkAndStartPeriodicSync();
  }

  /// Dispose the background sync service.
  void dispose() {
    if (!_isInitialized) return;

    AppLogger.log.i('[BackgroundSyncService] Disposing background sync service');
    WidgetsBinding.instance.removeObserver(this);
    _periodicSyncTimer?.cancel();
    _periodicSyncTimer = null;
    _isInitialized = false;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      AppLogger.log.i('[BackgroundSyncService] App resumed, checking for sync');
      _syncOnResume();
    } else if (state == AppLifecycleState.paused) {
      AppLogger.log.d('[BackgroundSyncService] App paused');
    }
  }

  /// Sync on app resume if conditions are met.
  Future<void> _syncOnResume() async {
    try {
      final preferences = _ref.read(userPreferencesProvider);
      
      // Only sync if autoDownloadAll is enabled
      if (!preferences.autoDownloadAll) {
        AppLogger.log.d('[BackgroundSyncService] Auto-download disabled, skipping resume sync');
        return;
      }

      // Check connectivity
      if (!ConnectionCheckerService.instance.isConnectedSync) {
        AppLogger.log.d('[BackgroundSyncService] No connectivity, skipping resume sync');
        return;
      }

      // Don't sync too frequently (at least 1 hour between syncs)
      if (_lastSyncTime != null) {
        final timeSinceLastSync = DateTime.now().difference(_lastSyncTime!);
        if (timeSinceLastSync < const Duration(hours: 1)) {
          AppLogger.log.d('[BackgroundSyncService] Sync too recent (${timeSinceLastSync.inMinutes}m ago), skipping');
          return;
        }
      }

      AppLogger.log.i('[BackgroundSyncService] Starting resume sync');
      final syncService = _ref.read(s3SyncServiceProvider);
      await syncService.syncAll();
      _lastSyncTime = DateTime.now();
    } catch (e, stack) {
      AppLogger.log.e(
        '[BackgroundSyncService] Error during resume sync',
        error: e,
        stackTrace: stack,
      );
    }
  }

  /// Check if periodic sync should be started and start it if needed.
  void _checkAndStartPeriodicSync() {
    final preferences = _ref.read(userPreferencesProvider);
    
    if (!preferences.autoDownloadAll) {
      AppLogger.log.d('[BackgroundSyncService] Auto-download disabled, not starting periodic sync');
      _periodicSyncTimer?.cancel();
      _periodicSyncTimer = null;
      return;
    }

    // Cancel existing timer if any
    _periodicSyncTimer?.cancel();

    // Start periodic sync timer
    AppLogger.log.i('[BackgroundSyncService] Starting periodic sync (interval: ${_syncInterval.inHours}h)');
    _periodicSyncTimer = Timer.periodic(_syncInterval, (_) async {
      await _performPeriodicSync();
    });
  }

  /// Perform periodic sync.
  Future<void> _performPeriodicSync() async {
    try {
      final preferences = _ref.read(userPreferencesProvider);
      
      // Check if autoDownloadAll is still enabled
      if (!preferences.autoDownloadAll) {
        AppLogger.log.d('[BackgroundSyncService] Auto-download disabled, stopping periodic sync');
        _periodicSyncTimer?.cancel();
        _periodicSyncTimer = null;
        return;
      }

      // Check connectivity
      if (!ConnectionCheckerService.instance.isConnectedSync) {
        AppLogger.log.d('[BackgroundSyncService] No connectivity, skipping periodic sync');
        return;
      }

      AppLogger.log.i('[BackgroundSyncService] Starting periodic sync');
      final syncService = _ref.read(s3SyncServiceProvider);
      await syncService.syncAll();
      _lastSyncTime = DateTime.now();
    } catch (e, stack) {
      AppLogger.log.e(
        '[BackgroundSyncService] Error during periodic sync',
        error: e,
        stackTrace: stack,
      );
    }
  }

  /// Manually trigger a sync (useful for testing or manual refresh).
  Future<void> triggerSync() async {
    try {
      AppLogger.log.i('[BackgroundSyncService] Manual sync triggered');
      final syncService = _ref.read(s3SyncServiceProvider);
      await syncService.syncAll();
      _lastSyncTime = DateTime.now();
    } catch (e, stack) {
      AppLogger.log.e(
        '[BackgroundSyncService] Error during manual sync',
        error: e,
        stackTrace: stack,
      );
      rethrow;
    }
  }

  /// Update sync behavior when preferences change.
  /// Should be called when autoDownloadAll preference changes.
  void onPreferencesChanged() {
    if (!_isInitialized) return;
    _checkAndStartPeriodicSync();
  }
}

/// Provider for BackgroundSyncService instance.
final backgroundSyncServiceProvider = Provider<BackgroundSyncService>((ref) {
  final service = BackgroundSyncService(ref);
  
  // Initialize when provider is created
  service.initialize();
  
  // Listen to preference changes
  ref.listen(userPreferencesProvider.select((p) => p.autoDownloadAll), (previous, next) {
    if (previous != next) {
      service.onPreferencesChanged();
    }
  });
  
  // Dispose when provider is disposed
  ref.onDispose(() {
    service.dispose();
  });
  
  return service;
});

