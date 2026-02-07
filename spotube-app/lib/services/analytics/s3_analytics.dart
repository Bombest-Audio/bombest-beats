import 'package:spotube/services/logger/logger.dart';

/// Analytics and monitoring for S3 operations.
/// Tracks cache hit rates, download success rates, and sync performance metrics.
class S3Analytics {
  static final S3Analytics _instance = S3Analytics._internal();
  factory S3Analytics() => _instance;
  S3Analytics._internal();

  // Cache metrics
  int _cacheHits = 0;
  int _cacheMisses = 0;
  int _cacheErrors = 0;

  // Download metrics
  int _downloadSuccesses = 0;
  int _downloadFailures = 0;
  int _downloadCancellations = 0;

  // Sync metrics
  int _syncOperations = 0;
  int _syncSuccesses = 0;
  int _syncFailures = 0;
  final List<Duration> _syncDurations = [];

  /// Record a cache hit.
  void recordCacheHit() {
    _cacheHits++;
    AppLogger.log.d('[S3Analytics] Cache hit (total: $_cacheHits)');
  }

  /// Record a cache miss.
  void recordCacheMiss() {
    _cacheMisses++;
    AppLogger.log.d('[S3Analytics] Cache miss (total: $_cacheMisses)');
  }

  /// Record a cache error.
  void recordCacheError() {
    _cacheErrors++;
    AppLogger.log.w('[S3Analytics] Cache error (total: $_cacheErrors)');
  }

  /// Record a successful download.
  void recordDownloadSuccess() {
    _downloadSuccesses++;
    AppLogger.log.d('[S3Analytics] Download success (total: $_downloadSuccesses)');
  }

  /// Record a failed download.
  void recordDownloadFailure() {
    _downloadFailures++;
    AppLogger.log.w('[S3Analytics] Download failure (total: $_downloadFailures)');
  }

  /// Record a cancelled download.
  void recordDownloadCancellation() {
    _downloadCancellations++;
    AppLogger.log.d('[S3Analytics] Download cancellation (total: $_downloadCancellations)');
  }

  /// Record the start of a sync operation.
  DateTime? _syncStartTime;

  void recordSyncStart() {
    _syncOperations++;
    _syncStartTime = DateTime.now();
    AppLogger.log.d('[S3Analytics] Sync started (total: $_syncOperations)');
  }

  /// Record the completion of a sync operation.
  void recordSyncComplete({required bool success}) {
    if (_syncStartTime == null) {
      AppLogger.log.w('[S3Analytics] Sync complete called without start time');
      return;
    }

    final duration = DateTime.now().difference(_syncStartTime!);
    _syncDurations.add(duration);
    _syncStartTime = null;

    if (success) {
      _syncSuccesses++;
      AppLogger.log.d('[S3Analytics] Sync completed successfully in ${duration.inSeconds}s (total successes: $_syncSuccesses)');
    } else {
      _syncFailures++;
      AppLogger.log.w('[S3Analytics] Sync failed after ${duration.inSeconds}s (total failures: $_syncFailures)');
    }
  }

  /// Get cache hit rate (0.0 to 1.0).
  double get cacheHitRate {
    final total = _cacheHits + _cacheMisses;
    if (total == 0) return 0.0;
    return _cacheHits / total;
  }

  /// Get download success rate (0.0 to 1.0).
  double get downloadSuccessRate {
    final total = _downloadSuccesses + _downloadFailures;
    if (total == 0) return 0.0;
    return _downloadSuccesses / total;
  }

  /// Get sync success rate (0.0 to 1.0).
  double get syncSuccessRate {
    final total = _syncSuccesses + _syncFailures;
    if (total == 0) return 0.0;
    return _syncSuccesses / total;
  }

  /// Get average sync duration.
  Duration? get averageSyncDuration {
    if (_syncDurations.isEmpty) return null;
    final total = _syncDurations.fold<int>(
      0,
      (sum, duration) => sum + duration.inMilliseconds,
    );
    return Duration(milliseconds: total ~/ _syncDurations.length);
  }

  /// Get analytics summary as a map.
  Map<String, dynamic> getSummary() {
    return {
      'cache': {
        'hits': _cacheHits,
        'misses': _cacheMisses,
        'errors': _cacheErrors,
        'hitRate': cacheHitRate,
        'totalRequests': _cacheHits + _cacheMisses,
      },
      'downloads': {
        'successes': _downloadSuccesses,
        'failures': _downloadFailures,
        'cancellations': _downloadCancellations,
        'successRate': downloadSuccessRate,
        'total': _downloadSuccesses + _downloadFailures + _downloadCancellations,
      },
      'sync': {
        'operations': _syncOperations,
        'successes': _syncSuccesses,
        'failures': _syncFailures,
        'successRate': syncSuccessRate,
        'averageDurationMs': averageSyncDuration?.inMilliseconds,
      },
    };
  }

  /// Log analytics summary.
  void logSummary() {
    final summary = getSummary();
    AppLogger.log.i('[S3Analytics] Summary:');
    AppLogger.log.i('[S3Analytics]   Cache: ${summary['cache']['hits']} hits, ${summary['cache']['misses']} misses, ${(summary['cache']['hitRate'] * 100).toStringAsFixed(1)}% hit rate');
    AppLogger.log.i('[S3Analytics]   Downloads: ${summary['downloads']['successes']} successes, ${summary['downloads']['failures']} failures, ${(summary['downloads']['successRate'] * 100).toStringAsFixed(1)}% success rate');
    AppLogger.log.i('[S3Analytics]   Sync: ${summary['sync']['operations']} operations, ${summary['sync']['successes']} successes, ${summary['sync']['failures']} failures');
    if (summary['sync']['averageDurationMs'] != null) {
      AppLogger.log.i('[S3Analytics]   Average sync duration: ${(summary['sync']['averageDurationMs']! / 1000).toStringAsFixed(1)}s');
    }
  }

  /// Reset all metrics (useful for testing or periodic resets).
  void reset() {
    _cacheHits = 0;
    _cacheMisses = 0;
    _cacheErrors = 0;
    _downloadSuccesses = 0;
    _downloadFailures = 0;
    _downloadCancellations = 0;
    _syncOperations = 0;
    _syncSuccesses = 0;
    _syncFailures = 0;
    _syncDurations.clear();
    AppLogger.log.i('[S3Analytics] Metrics reset');
  }
}

/// Global instance for easy access.
final s3Analytics = S3Analytics();

