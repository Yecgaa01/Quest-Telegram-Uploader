import 'package:flutter/services.dart';

class NativeStatus {
  const NativeStatus({
    required this.running,
    required this.autoEnabled,
    required this.pending,
    required this.uploaded,
    required this.failed,
    required this.skippedLarge,
    required this.lastScan,
    required this.message,
  });

  final bool running;
  final bool autoEnabled;
  final int pending;
  final int uploaded;
  final int failed;
  final int skippedLarge;
  final String lastScan;
  final String message;

  factory NativeStatus.fromMap(Map<dynamic, dynamic> map) {
    return NativeStatus(
      running: map['running'] == true,
      autoEnabled: map['autoEnabled'] == true,
      pending: (map['pending'] as num?)?.toInt() ?? 0,
      uploaded: (map['uploaded'] as num?)?.toInt() ?? 0,
      failed: (map['failed'] as num?)?.toInt() ?? 0,
      skippedLarge: (map['skippedLarge'] as num?)?.toInt() ?? 0,
      lastScan: map['lastScan']?.toString() ?? 'Nunca',
      message: map['message']?.toString() ?? '',
    );
  }
}

class PermissionStatus {
  const PermissionStatus({
    required this.notifications,
    required this.mediaImages,
    required this.mediaVideo,
    required this.legacyStorage,
    required this.allFiles,
  });

  final bool notifications;
  final bool mediaImages;
  final bool mediaVideo;
  final bool legacyStorage;
  final bool allFiles;

  bool get canReadMedia => allFiles || (mediaImages && mediaVideo) || legacyStorage;

  factory PermissionStatus.fromMap(Map<dynamic, dynamic> map) {
    return PermissionStatus(
      notifications: map['notifications'] == true,
      mediaImages: map['mediaImages'] == true,
      mediaVideo: map['mediaVideo'] == true,
      legacyStorage: map['legacyStorage'] == true,
      allFiles: map['allFiles'] == true,
    );
  }
}

class NativeService {
  static const _channel = MethodChannel('quest_telegram_uploader/service');

  Future<NativeStatus> getStatus() async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('getStatus');
    return NativeStatus.fromMap(result ?? const {});
  }

  Future<PermissionStatus> getPermissions() async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('getPermissions');
    return PermissionStatus.fromMap(result ?? const {});
  }

  Future<PermissionStatus> requestPermissions() async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('requestPermissions');
    return PermissionStatus.fromMap(result ?? const {});
  }

  Future<void> openAllFilesSettings() async {
    await _channel.invokeMethod<void>('openAllFilesSettings');
  }

  Future<void> saveConfig({
    required String botToken,
    required String chatId,
    required List<String> paths,
  }) async {
    await _channel.invokeMethod<void>('saveConfig', {
      'botToken': botToken,
      'chatId': chatId,
      'paths': paths,
    });
  }

  Future<void> startAuto() async {
    await _channel.invokeMethod<void>('startAuto');
  }

  Future<void> stopAuto() async {
    await _channel.invokeMethod<void>('stopAuto');
  }

  Future<NativeStatus> scanNow() async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('scanNow');
    return NativeStatus.fromMap(result ?? const {});
  }

  Future<String> testTelegram() async {
    final result = await _channel.invokeMethod<String>('testTelegram');
    return result ?? 'Teste enviado.';
  }
}
