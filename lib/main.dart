import 'dart:async';

import 'package:flutter/material.dart';

import 'config_store.dart';
import 'native_service.dart';

void main() {
  runApp(const QuestTelegramUploaderApp());
}

class QuestTelegramUploaderApp extends StatelessWidget {
  const QuestTelegramUploaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Quest Telegram Uploader',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF22C55E),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF020617),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _store = ConfigStore();
  final _native = NativeService();
  AppConfig? _config;
  NativeStatus? _status;
  PermissionStatus? _permissions;
  Timer? _timer;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => _refreshStatus());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final config = await _store.load();
    final permissions = await _native.getPermissions();
    final status = await _native.getStatus();
    if (!mounted) return;
    setState(() {
      _config = config;
      _permissions = permissions;
      _status = status;
    });
  }

  Future<void> _refreshStatus() async {
    final status = await _native.getStatus();
    final permissions = await _native.getPermissions();
    if (!mounted) return;
    setState(() {
      _status = status;
      _permissions = permissions;
    });
  }

  Future<void> _run(String success, Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      await _refreshStatus();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(success)));
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Falha: $error')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openSettings() async {
    final current = _config ?? const AppConfig(botToken: '', chatId: '', paths: ConfigStore.defaultPaths);
    final saved = await Navigator.of(context).push<AppConfig>(
      MaterialPageRoute(builder: (_) => SettingsScreen(initialConfig: current, native: _native)),
    );
    if (saved == null) return;
    await _store.save(saved);
    await _native.saveConfig(botToken: saved.botToken, chatId: saved.chatId, paths: saved.paths);
    await _load();
  }

  Future<void> _toggleAuto() async {
    final status = _status;
    if (status?.running == true || status?.autoEnabled == true) {
      await _run('Envio automático parado.', _native.stopAuto);
      return;
    }
    final config = _config;
    if (config == null || !config.isReady) {
      await _openSettings();
      return;
    }
    await _run('Envio automático iniciado.', _native.startAuto);
  }

  @override
  Widget build(BuildContext context) {
    final config = _config;
    final status = _status;
    final permissions = _permissions;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Quest Telegram Uploader'),
        actions: [
          IconButton(onPressed: _busy ? null : _openSettings, icon: const Icon(Icons.settings)),
        ],
      ),
      body: config == null || status == null || permissions == null
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _SetupCard(config: config, permissions: permissions, onSettings: _openSettings),
                  const SizedBox(height: 12),
                  _StatusCard(status: status),
                  const SizedBox(height: 12),
                  _PermissionCard(
                    permissions: permissions,
                    onRequest: _busy
                        ? null
                        : () => _run('Permissões atualizadas.', () async {
                              _permissions = await _native.requestPermissions();
                            }),
                    onAllFiles: _busy ? null : () => _native.openAllFilesSettings(),
                  ),
                  const SizedBox(height: 12),
                  _PathsCard(paths: config.paths),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: _busy ? null : _toggleAuto,
                    icon: Icon(status.running || status.autoEnabled ? Icons.stop : Icons.play_arrow),
                    label: Text(status.running || status.autoEnabled ? 'Parar envio automático' : 'Iniciar envio automático'),
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: _busy ? null : () => _run('Scan e envio executados.', () async => _status = await _native.scanNow()),
                    icon: const Icon(Icons.sync),
                    label: const Text('Reescanear e enviar pendentes agora'),
                  ),
                ],
              ),
            ),
    );
  }
}

class _SetupCard extends StatelessWidget {
  const _SetupCard({required this.config, required this.permissions, required this.onSettings});

  final AppConfig config;
  final PermissionStatus permissions;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final ok = config.isReady && permissions.canReadMedia;
    return Card(
      child: ListTile(
        leading: Icon(ok ? Icons.check_circle : Icons.warning, color: ok ? Colors.greenAccent : Colors.amberAccent),
        title: Text(ok ? 'Pronto para enviar capturas' : 'Configuração incompleta'),
        subtitle: Text(config.isReady ? 'Telegram configurado.' : 'Informe token do bot e chat ID.'),
        trailing: TextButton(onPressed: onSettings, child: const Text('Configurar')),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.status});

  final NativeStatus status;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(status.running ? 'Monitorando em segundo plano' : 'Serviço parado', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(status.message.isEmpty ? 'Nenhuma mensagem ainda.' : status.message),
            const Divider(height: 24),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _Chip(label: 'Pendentes', value: status.pending),
                _Chip(label: 'Enviados', value: status.uploaded),
                _Chip(label: 'Falhas', value: status.failed),
                _Chip(label: 'Grandes', value: status.skippedLarge),
              ],
            ),
            const SizedBox(height: 8),
            Text('Último scan: ${status.lastScan}'),
          ],
        ),
      ),
    );
  }
}

class _PermissionCard extends StatelessWidget {
  const _PermissionCard({required this.permissions, required this.onRequest, required this.onAllFiles});

  final PermissionStatus permissions;
  final VoidCallback? onRequest;
  final VoidCallback? onAllFiles;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Permissões', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('Notificações: ${permissions.notifications ? 'ok' : 'pendente'}'),
            Text('Leitura de mídia: ${permissions.canReadMedia ? 'ok' : 'pendente'}'),
            Text('Acesso total a arquivos: ${permissions.allFiles ? 'ok' : 'opcional'}'),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                OutlinedButton(onPressed: onRequest, child: const Text('Pedir permissões')),
                OutlinedButton(onPressed: onAllFiles, child: const Text('Abrir acesso total')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _PathsCard extends StatelessWidget {
  const _PathsCard({required this.paths});

  final List<String> paths;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Pastas monitoradas', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            for (final path in paths) Text(path),
          ],
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.label, required this.value});

  final String label;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Chip(label: Text('$label: $value'));
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.initialConfig, required this.native});

  final AppConfig initialConfig;
  final NativeService native;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _token;
  late final TextEditingController _chatId;
  late final TextEditingController _paths;
  bool _testing = false;

  @override
  void initState() {
    super.initState();
    _token = TextEditingController(text: widget.initialConfig.botToken);
    _chatId = TextEditingController(text: widget.initialConfig.chatId);
    _paths = TextEditingController(text: widget.initialConfig.paths.join('\n'));
  }

  @override
  void dispose() {
    _token.dispose();
    _chatId.dispose();
    _paths.dispose();
    super.dispose();
  }

  AppConfig _current() {
    return AppConfig(
      botToken: _token.text,
      chatId: _chatId.text,
      paths: _paths.text.split('\n').map((line) => line.trim()).where((line) => line.isNotEmpty).toList(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Configuração')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: _token,
            decoration: const InputDecoration(labelText: 'Token do bot Telegram', border: OutlineInputBorder()),
            obscureText: true,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _chatId,
            decoration: const InputDecoration(labelText: 'Chat ID', border: OutlineInputBorder()),
            keyboardType: TextInputType.text,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _paths,
            decoration: const InputDecoration(labelText: 'Pastas monitoradas, uma por linha', border: OutlineInputBorder()),
            minLines: 3,
            maxLines: 6,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => Navigator.of(context).pop(_current()),
            icon: const Icon(Icons.save),
            label: const Text('Salvar'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _testing
                ? null
                : () async {
                    setState(() => _testing = true);
                    final config = _current();
                    await ConfigStore().save(config);
                    await widget.native.saveConfig(botToken: config.botToken, chatId: config.chatId, paths: config.paths);
                    try {
                      final message = await widget.native.testTelegram();
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
                    } catch (error) {
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Falha no teste: $error')));
                    } finally {
                      if (mounted) setState(() => _testing = false);
                    }
                  },
            icon: const Icon(Icons.send),
            label: const Text('Salvar e testar Telegram'),
          ),
        ],
      ),
    );
  }
}
