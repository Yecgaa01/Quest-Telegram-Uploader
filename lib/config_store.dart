import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

class AppConfig {
  const AppConfig({
    required this.botToken,
    required this.chatId,
    required this.paths,
  });

  final String botToken;
  final String chatId;
  final List<String> paths;

  bool get isReady => botToken.trim().isNotEmpty && chatId.trim().isNotEmpty;

  AppConfig copyWith({String? botToken, String? chatId, List<String>? paths}) {
    return AppConfig(
      botToken: botToken ?? this.botToken,
      chatId: chatId ?? this.chatId,
      paths: paths ?? this.paths,
    );
  }
}

class ConfigStore {
  static const _secure = FlutterSecureStorage();
  static const _tokenKey = 'telegram_bot_token';
  static const _chatIdKey = 'telegram_chat_id';
  static const _pathsKey = 'media_paths';

  static const defaultPaths = [
    '/sdcard/Oculus/Screenshots',
    '/sdcard/Oculus/VideoShots',
  ];

  Future<AppConfig> load() async {
    final prefs = await SharedPreferences.getInstance();
    final token = await _secure.read(key: _tokenKey) ?? '';
    final chatId = prefs.getString(_chatIdKey) ?? '';
    final paths = prefs.getStringList(_pathsKey) ?? defaultPaths;
    return AppConfig(botToken: token, chatId: chatId, paths: paths);
  }

  Future<void> save(AppConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    await _secure.write(key: _tokenKey, value: config.botToken.trim());
    await prefs.setString(_chatIdKey, config.chatId.trim());
    await prefs.setStringList(
      _pathsKey,
      config.paths.map((path) => path.trim()).where((path) => path.isNotEmpty).toList(),
    );
  }
}
