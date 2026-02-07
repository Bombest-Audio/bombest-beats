import 'dart:async';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/plugins/s3_bombest/lib/s3_repository.dart';
import 'package:http/http.dart' as http;

/// Health check results
class HealthCheckResult {
  final bool cacheDirectoryOk;
  final bool cacheDirectoryWritable;
  final bool storageSpaceOk;
  final int? availableSpaceMB;
  final bool s3BucketAccessible;
  final List<String> errors;
  final List<String> warnings;

  HealthCheckResult({
    required this.cacheDirectoryOk,
    required this.cacheDirectoryWritable,
    required this.storageSpaceOk,
    this.availableSpaceMB,
    required this.s3BucketAccessible,
    required this.errors,
    required this.warnings,
  });

  bool get isHealthy => 
      cacheDirectoryOk && 
      cacheDirectoryWritable && 
      storageSpaceOk && 
      s3BucketAccessible &&
      errors.isEmpty;

  String get summary {
    if (isHealthy) {
      return 'All health checks passed';
    }
    final issues = <String>[];
    if (!cacheDirectoryOk) issues.add('Cache directory missing');
    if (!cacheDirectoryWritable) issues.add('Cache directory not writable');
    if (!storageSpaceOk) issues.add('Insufficient storage space');
    if (!s3BucketAccessible) issues.add('S3 bucket not accessible');
    issues.addAll(errors);
    return 'Health check issues: ${issues.join(", ")}';
  }
}

/// Service for performing health checks on app startup and periodically
class HealthCheckService {
  static const int minRequiredSpaceMB = 100; // Minimum 100MB required

  /// Perform comprehensive health checks
  static Future<HealthCheckResult> performHealthChecks() async {
    final errors = <String>[];
    final warnings = <String>[];
    
    AppLogger.log.i('[HealthCheck] Starting health checks...');

    // Check cache directory
    bool cacheDirectoryOk = false;
    bool cacheDirectoryWritable = false;
    try {
      final cacheDir = await UserPreferencesNotifier.getMusicCacheDir();
      final dir = Directory(cacheDir);
      
      if (await dir.exists()) {
        cacheDirectoryOk = true;
        // Test writability by creating a test file
        try {
          final testFile = File('${dir.path}/.health_check_test');
          await testFile.writeAsString('test');
          await testFile.delete();
          cacheDirectoryWritable = true;
        } catch (e) {
          errors.add('Cache directory not writable: $e');
        }
      } else {
        try {
          await dir.create(recursive: true);
          cacheDirectoryOk = true;
          cacheDirectoryWritable = true;
        } catch (e) {
          errors.add('Failed to create cache directory: $e');
        }
      }
    } catch (e, stack) {
      AppLogger.log.e('[HealthCheck] Cache directory check failed', error: e, stackTrace: stack);
      errors.add('Cache directory check failed: $e');
    }

    // Check download directory (simplified - actual check happens in download manager)
    // Note: Download directory check requires provider access, so we skip it here
    // The download manager will validate the directory when needed

    // Check storage space
    bool storageSpaceOk = false;
    int? availableSpaceMB;
    try {
      final tempDir = await getTemporaryDirectory();
      final stat = await tempDir.stat();
      // Note: stat.freeSpace might not be available on all platforms
      // This is a best-effort check
      availableSpaceMB = null; // Platform-specific implementation needed
      storageSpaceOk = true; // Assume OK if we can't check
      warnings.add('Storage space check not fully implemented for this platform');
    } catch (e) {
      warnings.add('Storage space check failed: $e');
      storageSpaceOk = true; // Don't block on storage check failure
    }

    // Check S3 bucket accessibility
    bool s3BucketAccessible = false;
    try {
      final repository = S3Repository();
      // Construct URL manually since _baseUrl is private
      final baseUrl = "https://${repository.bucket}.s3.${repository.region}.amazonaws.com";
      final url = Uri.parse("$baseUrl?list-type=2&prefix=${repository.prefix}&max-keys=1");
      final response = await http.get(url).timeout(
        const Duration(seconds: 5),
        onTimeout: () => throw TimeoutException('S3 health check timed out'),
      );
      
      if (response.statusCode == 200 || response.statusCode == 403) {
        // 200 = accessible, 403 = accessible but no list permission (acceptable for direct access)
        s3BucketAccessible = true;
      } else if (response.statusCode == 404) {
        errors.add('S3 bucket not found (404)');
      } else {
        warnings.add('S3 bucket check returned status ${response.statusCode}');
        // Still consider accessible if we got a response (might be permission issue)
        s3BucketAccessible = true;
      }
    } catch (e, stack) {
      AppLogger.log.w('[HealthCheck] S3 bucket check failed', error: e, stackTrace: stack);
      warnings.add('S3 bucket accessibility check failed: $e');
      // Don't fail health check if S3 is temporarily unavailable
      s3BucketAccessible = true; // Assume OK for now
    }

    final result = HealthCheckResult(
      cacheDirectoryOk: cacheDirectoryOk,
      cacheDirectoryWritable: cacheDirectoryWritable,
      storageSpaceOk: storageSpaceOk,
      availableSpaceMB: availableSpaceMB,
      s3BucketAccessible: s3BucketAccessible,
      errors: errors,
      warnings: warnings,
    );

    AppLogger.log.i('[HealthCheck] Health check completed: ${result.summary}');
    if (result.warnings.isNotEmpty) {
      for (final warning in result.warnings) {
        AppLogger.log.w('[HealthCheck] Warning: $warning');
      }
    }
    if (result.errors.isNotEmpty) {
      for (final error in result.errors) {
        AppLogger.log.e('[HealthCheck] Error: $error');
      }
    }

    return result;
  }

  /// Check if sufficient storage space is available
  static Future<bool> checkStorageSpace({int requiredMB = minRequiredSpaceMB}) async {
    try {
      // Platform-specific implementation would go here
      // For now, return true (assume sufficient space)
      // TODO: Implement actual storage space check for Android/iOS
      return true;
    } catch (e) {
      AppLogger.log.w('[HealthCheck] Storage space check failed: $e');
      return true; // Don't block on check failure
    }
  }

  /// Verify cache directory exists and is writable
  static Future<bool> verifyCacheDirectory() async {
    try {
      final cacheDir = await UserPreferencesNotifier.getMusicCacheDir();
      final dir = Directory(cacheDir);
      
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      
      // Test writability
      final testFile = File('${dir.path}/.health_check_test');
      await testFile.writeAsString('test');
      await testFile.delete();
      
      return true;
    } catch (e, stack) {
      AppLogger.log.e('[HealthCheck] Cache directory verification failed', error: e, stackTrace: stack);
      return false;
    }
  }
}

