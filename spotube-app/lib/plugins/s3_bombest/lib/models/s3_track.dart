class S3Track {
  final String key;
  final String bucket;
  final String region;
  final int size;
  final DateTime lastModified;
  final String eTag;

  S3Track({
    required this.key,
    required this.bucket,
    required this.region,
    required this.size,
    required this.lastModified,
    required this.eTag,
  });

  String get filename => key.split('/').last;

  String get title {
    final name = filename;
    final dotIndex = name.lastIndexOf('.');
    if (dotIndex == -1) return name;
    return name.substring(0, dotIndex);
  }

  /// URL-safe path construction (keys may contain spaces/brackets).
  String get url {
    final encodedSegments = key.split('/').map(Uri.encodeComponent).toList();
    final uri = Uri(
      scheme: 'https',
      host: '$bucket.s3.$region.amazonaws.com',
      pathSegments: encodedSegments,
    );
    return uri.toString();
  }
}
