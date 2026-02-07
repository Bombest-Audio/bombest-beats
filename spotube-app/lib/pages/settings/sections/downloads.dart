import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart' show ListTile;
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:spotube/collections/spotube_icons.dart';
import 'package:spotube/modules/settings/section_card_with_heading.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/provider/user_preferences/user_preferences_provider.dart';
import 'package:spotube/services/s3_cache/s3_cache_manager.dart';
import 'package:spotube/utils/platform.dart';

class SettingsDownloadsSection extends HookConsumerWidget {
  const SettingsDownloadsSection({super.key});

  @override
  Widget build(BuildContext context, ref) {
    final preferencesNotifier = ref.watch(userPreferencesProvider.notifier);
    final preferences = ref.watch(userPreferencesProvider);

    final pickDownloadLocation = useCallback(() async {
      if (kIsMobile || kIsMacOS) {
        final dirStr = await FilePicker.platform.getDirectoryPath(
          initialDirectory: preferences.downloadLocation,
        );
        if (dirStr == null) return;
        preferencesNotifier.setDownloadLocation(dirStr);
      } else {
        String? dirStr = await getDirectoryPath(
          initialDirectory: preferences.downloadLocation,
        );
        if (dirStr == null) return;
        preferencesNotifier.setDownloadLocation(dirStr);
      }
    }, [preferences.downloadLocation]);

    final autoDownloadAll = preferences.autoDownloadAll;
    
    // Cache management
    final cacheManagerAsync = ref.watch(s3CacheManagerAsyncProvider);
    final cacheSizeState = useState<int?>(null);
    final isClearingCache = useState<bool>(false);
    
    useEffect(() {
      // Load cache size when cache manager is available
      cacheManagerAsync.whenData((cacheManager) async {
        final size = await cacheManager.getCacheSize();
        cacheSizeState.value = size;
      });
      return null;
    }, [cacheManagerAsync]);
    
    String formatBytes(int bytes) {
      if (bytes < 1024) return '$bytes B';
      if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
      if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
    }
    
    Future<void> clearCache() async {
      isClearingCache.value = true;
      try {
        await cacheManagerAsync.whenData((cacheManager) async {
          await cacheManager.clearCache(maxSizeMB: 0); // Clear all
          final newSize = await cacheManager.getCacheSize();
          cacheSizeState.value = newSize;
        });
      } catch (e) {
        // Error handled by cache manager
      } finally {
        isClearingCache.value = false;
      }
    }

    return SectionCardWithHeading(
      heading: context.l10n.downloads,
      children: [
        ListTile(
          leading: const Icon(SpotubeIcons.download),
          title: Text(context.l10n.download_location),
          subtitle: Text(preferences.downloadLocation),
          trailing: IconButton.secondary(
            onPressed: pickDownloadLocation,
            icon: const Icon(SpotubeIcons.folder),
          ),
          onTap: pickDownloadLocation,
        ),
        ListTile(
          leading: const Icon(SpotubeIcons.download),
          title: Text(context.l10n.download_all),
          subtitle: Text(
            'Automatically download all tracks from S3 when enabled',
          ).small(),
          trailing: Switch(
            value: autoDownloadAll,
            onChanged: (value) {
              preferencesNotifier.setAutoDownloadAll(value);
            },
          ),
        ),
        const Divider(),
        // Cache Management Section
        ListTile(
          leading: const Icon(SpotubeIcons.cache),
          title: const Text('Cache Management'),
          subtitle: cacheSizeState.value != null
              ? Text('Cache size: ${formatBytes(cacheSizeState.value!)}')
              : cacheManagerAsync.isLoading
                  ? const Text('Calculating...').small()
                  : const Text('Cache size unavailable').small(),
        ),
        ListTile(
          leading: const Icon(SpotubeIcons.trash),
          title: const Text('Clear Cache'),
          subtitle: const Text('Remove all cached tracks to free up storage').small(),
          trailing: isClearingCache.value
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : IconButton.destructive(
                  onPressed: cacheManagerAsync.hasValue && !isClearingCache.value
                      ? clearCache
                      : null,
                  icon: const Icon(SpotubeIcons.trash),
                ),
          onTap: cacheManagerAsync.hasValue && !isClearingCache.value ? clearCache : null,
        ),
        if (kIsDesktop || kIsMacOS)
          ListTile(
            leading: const Icon(SpotubeIcons.folder),
            title: const Text('Open Cache Folder'),
            subtitle: const Text('View cached files in file manager').small(),
            trailing: const Icon(SpotubeIcons.angleRight),
            onTap: preferencesNotifier.openCacheFolder,
          ),
      ],
    );
  }
}
