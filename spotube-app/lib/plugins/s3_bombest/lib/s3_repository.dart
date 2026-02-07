import 'dart:async';
import 'package:http/http.dart' as http;
import 'package:xml/xml.dart';
import 'models/s3_track.dart';
import 'package:spotube/services/logger/logger.dart';

class S3Repository {
  final String bucket;
  final String region;
  final String prefix;

  S3Repository({
    this.bucket = 'bombest-beats-music',
    this.region = 'us-west-2',
    this.prefix = 'music/',
  });

  String get _baseUrl => "https://$bucket.s3.$region.amazonaws.com";
  static const _audioExtensions = {
    '.mp3',
    '.m4a',
    '.aac',
    '.wav',
    '.flac',
    '.ogg',
    '.opus',
  };

  Future<List<S3Track>> fetchTracks() async {
    AppLogger.log.i('[S3Repository] Starting fetchTracks() from bucket=$bucket prefix=$prefix');
    final url = Uri.parse("$_baseUrl?list-type=2&prefix=$prefix");
    
    AppLogger.log.d('[S3Repository] Fetching from URL: $url');
    
    http.Response response;
    try {
      response = await http.get(url).timeout(
        const Duration(seconds: 30),
        onTimeout: () {
          throw TimeoutException('S3 request timed out after 30 seconds');
        },
      );
    } catch (e, stack) {
      if (e is TimeoutException) {
        AppLogger.log.e('[S3Repository] Request timed out', error: e, stackTrace: stack);
        throw Exception('Network timeout: Unable to fetch tracks from S3. Please check your internet connection.');
      } else if (e.toString().contains('SocketException') || e.toString().contains('Failed host lookup')) {
        AppLogger.log.e('[S3Repository] Network error', error: e, stackTrace: stack);
        throw Exception('Network error: Unable to connect to S3. Please check your internet connection.');
      } else {
        AppLogger.log.e('[S3Repository] Unexpected error', error: e, stackTrace: stack);
        rethrow;
      }
    }

    // Handle different HTTP status codes gracefully
    if (response.statusCode == 200) {
      // Success - continue processing
    } else if (response.statusCode == 403) {
      AppLogger.log.e('[S3Repository] Access forbidden (403) - check bucket permissions');
      throw Exception('Access denied: S3 bucket permissions issue. Please check bucket configuration.');
    } else if (response.statusCode == 404) {
      AppLogger.log.e('[S3Repository] Bucket not found (404)');
      throw Exception('Bucket not found: S3 bucket does not exist or is not accessible.');
    } else if (response.statusCode >= 500) {
      AppLogger.log.e('[S3Repository] Server error ${response.statusCode}');
      throw Exception('S3 server error: Please try again later.');
    } else {
      AppLogger.log.e('[S3Repository] Failed with status ${response.statusCode}');
      throw Exception('Failed to fetch tracks from S3: HTTP ${response.statusCode}');
    }

    AppLogger.log.d('[S3Repository] Received ${response.body.length} bytes, parsing XML');
    
    XmlDocument document;
    try {
      document = XmlDocument.parse(response.body);
    } catch (e, stack) {
      AppLogger.log.e('[S3Repository] XML parsing failed', error: e, stackTrace: stack);
      throw Exception('Failed to parse S3 response: Invalid XML format');
    }
    
    final contents = document.findAllElements('Contents');
    AppLogger.log.d('[S3Repository] Found ${contents.length} Contents elements');

    final tracks = <S3Track>[];
    int skippedCount = 0;
    
    for (final element in contents) {
      try {
        final keyElements = element.findElements('Key');
        if (keyElements.isEmpty) {
          skippedCount++;
          continue;
        }
        final keyElement = keyElements.first;
        
        final key = keyElement.innerText;
        if (key.endsWith('/')) {
          skippedCount++;
          continue; // skip folders
        }
        if (!key.contains('.')) {
          skippedCount++;
          continue; // skip entries without extension
        }
        
        final lowerKey = key.toLowerCase();
        if (_audioExtensions.every((ext) => !lowerKey.endsWith(ext))) {
          skippedCount++;
          continue; // skip non-audio files
        }
        
        final sizeElements = element.findElements('Size');
        final size = sizeElements.isNotEmpty 
            ? int.tryParse(sizeElements.first.innerText) ?? 0 
            : 0;
        if (size <= 0) {
          skippedCount++;
          continue; // skip zero-length
        }
        
        // Skip files with numeric-only names (e.g., "00.wav")
        final filename = key.split('/').last;
        if (filename.contains('.')) {
          final nameWithoutExt = filename.substring(0, filename.lastIndexOf('.'));
          if (RegExp(r'^\d+$').hasMatch(nameWithoutExt)) {
            skippedCount++;
            continue;
          }
        }
        
        final eTagElements = element.findElements('ETag');
        final eTag = eTagElements.isNotEmpty 
            ? eTagElements.first.innerText.replaceAll('"', '')
            : '';
        
        final lastModifiedElements = element.findElements('LastModified');
        if (lastModifiedElements.isEmpty) {
          skippedCount++;
          continue;
        }
        final lastModifiedElement = lastModifiedElements.first;
        
        DateTime lastModified;
        try {
          lastModified = DateTime.parse(lastModifiedElement.innerText);
        } catch (e) {
          AppLogger.log.w('[S3Repository] Invalid date format for $key, skipping');
          skippedCount++;
          continue;
        }

        tracks.add(S3Track(
          key: key,
          bucket: bucket,
          region: region,
          size: size,
          lastModified: lastModified,
          eTag: eTag,
        ));
      } catch (e, stack) {
        // Skip individual track parsing errors, log and continue
        AppLogger.log.w('[S3Repository] Error parsing track element, skipping', error: e, stackTrace: stack);
        skippedCount++;
      }
    }
    
    if (skippedCount > 0) {
      AppLogger.log.d('[S3Repository] Skipped $skippedCount invalid entries');
    }
    
    AppLogger.log.i('[S3Repository] Successfully parsed ${tracks.length} valid tracks');
    for (final track in tracks.take(3)) {
      AppLogger.log.d('[S3Repository]   - ${track.title} (${track.size} bytes)');
    }
    if (tracks.length > 3) {
      AppLogger.log.d('[S3Repository]   ... and ${tracks.length - 3} more');
    }
    
    return tracks;
  }

  String getTrackUrl(S3Track track) {
    return track.url;
  }
}
