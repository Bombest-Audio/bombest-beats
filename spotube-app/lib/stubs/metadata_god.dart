
import 'dart:typed_data';

class Picture {
  final String mimeType;
  final Uint8List data;
  const Picture({required this.mimeType, required this.data});
}

class Metadata {
  final String? title;
  final String? artist;
  final String? album;
  final String? albumArtist;
  final int? year;
  final int? trackNumber;
  final int? discNumber;
  final String? genre;
  final double? durationMs;
  final BigInt? fileSize;
  final Picture? picture;

  const Metadata({
    this.title,
    this.artist,
    this.album,
    this.albumArtist,
    this.year,
    this.trackNumber,
    this.discNumber,
    this.genre,
    this.durationMs,
    this.fileSize,
    this.picture,
  });
}

class MetadataGod {
  static Future<void> initialize() async {}

  static Future<Metadata> readMetadata({required String file}) async {
    return const Metadata();
  }

  static Future<void> writeMetadata({required String file, required Metadata metadata}) async {}
  
  static Future<Metadata> getMetadata(String file) async {
    return const Metadata();
  }
}
