package com.moligon.questtelegramuploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var permissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatus" -> result.success(QuestUploadService.statusMap(this))
                "getPermissions" -> result.success(permissionMap())
                "requestPermissions" -> requestRuntimePermissions(result)
                "openAllFilesSettings" -> {
                    openAllFilesSettings()
                    result.success(null)
                }
                "saveConfig" -> {
                    val token = call.argument<String>("botToken").orEmpty()
                    val chatId = call.argument<String>("chatId").orEmpty()
                    val paths = call.argument<List<String>>("paths") ?: emptyList()
                    QuestUploadService.saveConfig(this, token, chatId, paths)
                    result.success(null)
                }
                "startAuto" -> {
                    QuestUploadService.setAutoEnabled(this, true)
                    ContextCompat.startForegroundService(this, Intent(this, QuestUploadService::class.java))
                    result.success(null)
                }
                "stopAuto" -> {
                    QuestUploadService.setAutoEnabled(this, false)
                    stopService(Intent(this, QuestUploadService::class.java))
                    result.success(null)
                }
                "scanNow" -> {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, QuestUploadService::class.java).putExtra(QuestUploadService.EXTRA_RUN_ONCE, true),
                    )
                    result.success(QuestUploadService.statusMap(this))
                }
                "testTelegram" -> {
                    Thread {
                        try {
                            QuestUploadService.testTelegram(this)
                            runOnUiThread { result.success("Mensagem de teste enviada para o Telegram.") }
                        } catch (error: Exception) {
                            runOnUiThread { result.error("telegram_test_failed", error.message, null) }
                        }
                    }.start()
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun requestRuntimePermissions(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(permissionMap())
            return
        }
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_IMAGES
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            result.success(permissionMap())
            return
        }
        permissionResult?.success(permissionMap())
        permissionResult = result
        requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            permissionResult?.success(permissionMap())
            permissionResult = null
        }
    }

    private fun permissionMap(): Map<String, Boolean> {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val mediaImages = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val mediaVideo = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val legacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            checkPermissionCompat(Manifest.permission.READ_EXTERNAL_STORAGE)
        val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        return mapOf(
            "notifications" to notifications,
            "mediaImages" to mediaImages,
            "mediaVideo" to mediaVideo,
            "legacyStorage" to legacyStorage,
            "allFiles" to allFiles,
        )
    }

    private fun checkPermissionCompat(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAllFilesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val CHANNEL = "quest_telegram_uploader/service"
        private const val PERMISSION_REQUEST = 3701
    }
}
