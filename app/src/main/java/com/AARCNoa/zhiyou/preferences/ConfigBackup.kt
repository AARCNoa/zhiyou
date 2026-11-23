package com.AARCNoa.zhiyou.preferences

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tencent.mm.ui.LauncherUI
import com.AARCNoa.zhiyou.activity.TransparentActivity
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.path.exists
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText


// ---------------------------------------------------------------------------
//  配置备份 / 恢复
//
//  从 SettingsPager（UI 文件）抽出来的：这里是纯粹的「遍历 MMKV → JSON → 文件」逻辑，
//  和界面无关。改备份格式时不应该在 900 行的设置页里翻。
// ---------------------------------------------------------------------------

internal fun exportConfig(context: Context) {
    TransparentActivity.launch(context) {
        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val exportJson = run {
                    val map = WePrefs.default.getAll()
                    val jsonObject = buildJsonObject {
                        for ((key, value) in map) {
                            when (value) {
                                is Boolean -> put(key, value)
                                is Int -> put(key, value)
                                is Long -> put(key, value)
                                is Float -> put(key, value)
                                is Double -> put(key, value)
                                is String -> put(key, value)
                                is Set<*> -> put(key, buildJsonArray {
                                    @Suppress("UNCHECKED_CAST")
                                    (value as Set<String>).forEach { add(it) }
                                })

                                null -> put(key, JsonNull)
                            }
                        }
                        // 数据文件不属于 WePrefs：聊天分组存在 moduleData/conversation_groups.json，
                        // 之前导出只打包偏好，恢复后分组全部失效（用户实测反馈）。这里一并打包。
                        runCatching {
                            val groupsFile = KnownPaths.moduleData / "conversation_groups.json"
                            if (groupsFile.exists()) {
                                put("__file__conversation_groups.json", groupsFile.readText())
                            }
                        }.onFailure {
                            WeLogger.e("WePrefs", "failed to bundle conversation groups", it)
                        }
                    }
                    DefaultJson.encodeToString(jsonObject)
                }
                runCatching {
                    HostInfo.application.contentResolver.openOutputStream(uri, "w")!!.use { fos ->
                        fos.writer().use { it.write(exportJson) }
                    }
                }.onFailure {
                    showToastSuspend("导出失败!")
                    WeLogger.e("WePrefs", "failed to export", it)
                }.onSuccess { showToastSuspend("导出成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        exportLauncher.launch("wcx_prefs_backup.json")
    }
}

internal fun importConfig(context: Context) {
    TransparentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val jsonString = LauncherUI.getInstance()!!.contentResolver.openInputStream(uri)?.use { fis ->
                        fis.reader().readText()
                    } ?: return@launch
                    val jsonObject = DefaultJson.parseToJsonElement(jsonString).jsonObject
                    for ((key, element) in jsonObject) {
                        // 数据文件还原：__file__<name> 是导出时打包的 moduleData 下文件。
                        // 只接受安全的文件名（防路径穿越），且必须是 .json。
                        if (key.startsWith("__file__") && element is JsonPrimitive && element.isString) {
                            val fileName = key.removePrefix("__file__")
                            val safeName = fileName.matches(Regex("[A-Za-z0-9._-]+")) && fileName.endsWith(".json")
                            if (!safeName) {
                                WeLogger.w("WePrefs", "skip unsafe restore file name: " + fileName)
                                continue
                            }
                            runCatching {
                                val target = KnownPaths.moduleData / fileName
                                target.parent?.toFile()?.mkdirs()
                                target.writeText(element.content)
                            }.onFailure {
                                WeLogger.e("WePrefs", "failed to restore data file " + fileName, it)
                            }
                            continue
                        }
                        when (element) {
                            is JsonNull -> WePrefs.default.remove(key)
                            is JsonPrimitive -> when {
                                element.isString -> WePrefs.default.putString(key, element.content)
                                element.booleanOrNull != null && (element.content == "true" || element.content == "false") ->
                                    WePrefs.putBool(key, element.boolean)

                                element.longOrNull != null && element.intOrNull == null ->
                                    WePrefs.putLong(key, element.long)

                                element.intOrNull != null -> WePrefs.putInt(key, element.int)
                                element.floatOrNull != null -> WePrefs.putFloat(key, element.float)
                            }

                            is JsonArray -> WePrefs.default.putStringSet(
                                key,
                                element.mapTo(HashSet()) { it.jsonPrimitive.content }
                            )

                            else -> Unit
                        }
                    }
                }.onFailure {
                    showToastSuspend("导入失败!")
                    WeLogger.e("WePrefs", "failed to import", it)
                }.onSuccess { showToastSuspend("导入成功：聊天分组等数据文件已还原，其余设置重启微信后生效") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        importLauncher.launch(arrayOf("application/json"))
    }
}
