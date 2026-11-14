package com.f0x1d.logfox.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.f0x1d.logfox.feature.crashes.api.data.CrashLogRepository
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRepository
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.logging.api.data.LogLineParser
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.f0x1d.logfox.feature.terminals.api.domain.GetDefaultTerminalUseCase
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

/**
 * Debug data provider for the MCP server.
 *
 * Everything is text/JSON: log lines, crash records, app/process/device info.
 * No screenshots, no touch injection.
 */
class AiDebug private constructor(
    private val context: Context,
    private val crashesRepository: CrashesRepository,
    private val crashLogRepository: CrashLogRepository,
    private val logLineParser: LogLineParser,
    private val getDefaultTerminal: GetDefaultTerminalUseCase,
    private val terminals: Map<TerminalType, Terminal>,
) {

    companion object {
        private val LEVELS = mapOf("V" to 0, "D" to 1, "I" to 2, "W" to 3, "E" to 4, "F" to 5, "S" to 6)
        private val DUMPSYS_ALLOWED = setOf(
            "activity", "window", "package", "meminfo", "dropbox",
            "cpuinfo", "battery", "display", "input", "connectivity", "accessibility",
        )

        @JvmStatic
        fun create(context: Context): AiDebug {
            val app = context.applicationContext
            val ep = EntryPointAccessors.fromApplication(app, AiDebugEntryPoint::class.java)
            return AiDebug(
                context = app,
                crashesRepository = ep.crashesRepository(),
                crashLogRepository = ep.crashLogRepository(),
                logLineParser = ep.logLineParser(),
                getDefaultTerminal = ep.getDefaultTerminalUseCase(),
                terminals = ep.terminals(),
            )
        }

        private fun ts(ms: Long): String =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    }

    // ---------------------------------------------------------------- terminal

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun runtimeExec(vararg command: String): TerminalResult = runBlocking(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(command)
            val out = p.inputStream.readBytes().decodeToString()
            val err = p.errorStream.readBytes().decodeToString()
            return@runBlocking TerminalResult(p.waitFor(), out, err)
        } catch (t: Throwable) {
            return@runBlocking TerminalResult(-1, "", t.toString())
        }
    }

    private fun shizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun pickTerminal(): Terminal {
        if (shizukuGranted()) {
            terminals[TerminalType.Shizuku]?.let { return it }
        }
        return terminals[TerminalType.Default] ?: getDefaultTerminal()
    }

    private fun exec(vararg command: String): TerminalResult {
        val terminal: Terminal = try {
            pickTerminal()
        } catch (t: Throwable) {
            return runtimeExec(*command)
        }
        val supported = io { runCatching { terminal.isSupported() }.getOrDefault(false) }
        if (supported) {
            val result = io { runCatching { terminal.executeNow(*command) }.getOrNull() }
            if (result != null && (result.output.isNotBlank() || result.isSuccessful || result.errorOutput.isBlank())) {
                return result
            }
        }
        return runtimeExec(*command)
    }

    fun requestShizuku(): JSONObject {
        val terminal = terminals[TerminalType.Shizuku]
            ?: return JSONObject().put("ok", false).put("error", "Shizuku terminal unavailable")
        val supported = io { runCatching { terminal.isSupported() }.getOrDefault(false) }
        return JSONObject()
            .put("ok", true)
            .put("supported", supported)
            .put("granted", shizukuGranted())
            .put("hint", if (supported) "Shizuku 已授权" else "请在 Shizuku 弹窗中允许 LogFox AI")
    }

    fun terminalInfo(): JSONObject {
        val o = JSONObject()
        val terminal = try {
            pickTerminal()
        } catch (t: Throwable) {
            o.put("ok", false)
            o.put("error", t.toString())
            return o
        }
        val supported = io { runCatching { terminal.isSupported() }.getOrDefault(false) }
        val shizuku = shizukuGranted()
        val test = if (supported || terminal.type.key == "default") {
            exec("logcat", "-d", "-t", "1")
        } else {
            null
        }
        val logcatOk = test != null && (test.isSuccessful || test.output.isNotBlank())
        o.put("ok", true)
        o.put("type", terminal.type.key)
        o.put("supported", supported)
        o.put("shizuku_granted", shizuku)
        o.put("logcat", logcatOk)
        if (test != null && !logcatOk) {
            o.put("error", test.errorOutput.take(500))
        }
        if (!logcatOk) {
            o.put(
                "hint",
                "在 LogFox 的『设置 → 服务』里启用 Shizuku 或 root，或用 adb 给本应用授予 READ_LOGS",
            )
        }
        return o
    }

    // ------------------------------------------------------------------- logs

    private fun parseLines(result: TerminalResult): List<LogLine> {
        val parsed = ArrayList<LogLine>()
        var id = 0L
        result.output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val item = runCatching { logLineParser.parse(id, line) }.getOrNull()
            if (item != null) {
                id++
                parsed.add(item)
            }
        }
        return parsed
    }

    private fun rawLogcat(limit: Int, buffer: String): Pair<TerminalResult, List<LogLine>> {
        val result = exec(
            "logcat", "-v", "uid", "-v", "epoch", "-d",
            "-t", limit.toString(), "-b", buffer,
        )
        return result to parseLines(result)
    }

    private fun levelRank(level: String): Int = LEVELS[level] ?: 0

    fun logs(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 200).coerceIn(1, 1000)
        val buffer = args.optString("buffer", "main").ifBlank { "main" }
        val (result, parsed) = rawLogcat(limit, buffer)
        val pkg = args.optString("package", "")
        val pidMap = if (pkg.isNotBlank()) pidPackageMap() else emptyMap()
        val pid = args.optString("pid", "")
        val tag = args.optString("tag", "")
        val contains = args.optString("contains", "")
        val regex = args.optBoolean("regex", false)
        val minLevel = levelRank(args.optString("level", "V"))
        val re = if (regex && contains.isNotBlank()) runCatching { Regex(contains) }.getOrNull() else null

        val filtered = parsed.filter { line ->
            if (pkg.isNotBlank()) {
                val linePkg = line.packageName ?: pidMap[line.pid] ?: ""
                if (!linePkg.contains(pkg, ignoreCase = true)) return@filter false
            }
            if (pid.isNotBlank() && line.pid != pid) return@filter false
            if (tag.isNotBlank() && !line.tag.contains(tag, ignoreCase = true)) return@filter false
            if (levelRank(line.level.letter) < minLevel) return@filter false
            if (contains.isNotBlank()) {
                if (re != null) {
                    if (!re.containsMatchIn(line.content) && !re.containsMatchIn(line.tag)) return@filter false
                } else if (!line.content.contains(contains, ignoreCase = true) &&
                    !line.tag.contains(contains, ignoreCase = true)
                ) {
                    return@filter false
                }
            }
            true
        }.takeLast(limit)

        val entries = JSONArray()
        filtered.forEach { entries.put(logJson(it)) }
        val o = JSONObject()
        o.put("ok", true)
        o.put("count", entries.length())
        o.put("terminal", terminalInfo().optString("type", ""))
        o.put("entries", entries)
        if (entries.length() > 0) {
            o.put("text", filtered.joinToString("\n") { format(it) })
        }
        if (entries.length() == 0) {
            o.put("hint", if (parsed.isEmpty()) {
                "没有解析到日志。检查『终端与日志权限』；error=${result.errorOutput.take(400)}"
            } else {
                "没有匹配的日志行"
            })
        }
        return o
    }

    private fun pidPackageMap(): Map<String, String> {
        if (!shizukuGranted()) return emptyMap()
        val map = HashMap<String, String>()
        val result = exec("ps", "-A")
        result.output.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts.last()
                if (name.contains('.')) map[parts[1]] = name
            }
        }
        return map
    }

    fun logStats(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 500).coerceIn(10, 1000)
        val pkg = args.optString("package", "")
        val pidMap = if (pkg.isNotBlank()) pidPackageMap() else emptyMap()
        val (_, parsed) = rawLogcat(limit, args.optString("buffer", "main").ifBlank { "main" })
        val byLevel = JSONObject()
        val byTag = HashMap<String, Int>()
        var total = 0
        parsed.forEach { line ->
            if (pkg.isNotBlank()) {
                val linePkg = line.packageName ?: pidMap[line.pid] ?: ""
                if (!linePkg.contains(pkg, ignoreCase = true)) return@forEach
            }
            total++
            val key = line.level.letter
            byLevel.put(key, byLevel.optInt(key, 0) + 1)
            byTag[line.tag] = (byTag[line.tag] ?: 0) + 1
        }
        val top = byTag.entries.sortedByDescending { it.value }.take(15)
        val tags = JSONArray()
        top.forEach { tags.put(JSONObject().put("tag", it.key).put("count", it.value)) }
        return JSONObject()
            .put("ok", true)
            .put("total", total)
            .put("by_level", byLevel)
            .put("top_tags", tags)
    }

    fun logsWait(args: JSONObject): JSONObject {
        val timeout = args.optLong("timeout_ms", 15000).coerceIn(1000, 120000)
        val contains = args.optString("contains", "")
        val pkg = args.optString("package", "")
        val re = if (args.optBoolean("regex", false) && contains.isNotBlank()) {
            runCatching { Regex(contains) }.getOrNull()
        } else {
            null
        }
        val baseline = System.currentTimeMillis() - 2000
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val probe = JSONObject()
                .put("limit", 300)
                .put("package", pkg)
                .put("contains", if (re == null) contains else "")
            val result = logs(probe)
            val entries = result.optJSONArray("entries") ?: JSONArray()
            val matches = JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                if (e.optLong("ts", 0) < baseline) continue
                val msg = e.optString("message", "") + " " + e.optString("tag", "")
                if (re != null && !re.containsMatchIn(msg)) continue
                matches.put(e)
            }
            if (matches.length() > 0) {
                return JSONObject()
                    .put("ok", true)
                    .put("found", true)
                    .put("elapsed_ms", timeout - (deadline - System.currentTimeMillis()))
                    .put("matches", matches)
            }
            Thread.sleep(500)
        }
        return JSONObject().put("ok", false).put("error", "timeout after ${timeout}ms")
    }

    private fun logJson(line: LogLine): JSONObject = JSONObject()
        .put("ts", line.dateAndTime)
        .put("time", ts(line.dateAndTime))
        .put("pid", line.pid)
        .put("tid", line.tid)
        .put("level", line.level.letter)
        .put("tag", line.tag)
        .put("package", line.packageName ?: "")
        .put("message", line.content)

    private fun format(line: LogLine): String =
        "${ts(line.dateAndTime)} ${line.level.letter}/${line.tag}(${line.pid}): ${line.content}"

    // ----------------------------------------------------------------- crimes

    fun crashes(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "")
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val all = io { runCatching { crashesRepository.getAll() }.getOrDefault(emptyList()) }
        val filtered = all
            .filter { pkg.isBlank() || it.packageName.contains(pkg, ignoreCase = true) }
            .sortedByDescending { it.dateAndTime }
            .take(limit)
        val arr = JSONArray()
        filtered.forEach { arr.put(crashJson(it)) }
        val o = JSONObject().put("ok", true).put("count", arr.length()).put("crashes", arr)
        if (all.isEmpty()) {
            o.put("hint", "LogFox 崩溃库为空；用 crash_buffer 查看系统崩溃缓冲区，或让 LogFox 的崩溃监听服务先运行")
        }
        return o
    }

    fun crashDetail(args: JSONObject): JSONObject {
        val id = args.optLong("id", -1)
        if (id < 0) return JSONObject().put("ok", false).put("error", "id is required")
        val crash = io { runCatching { crashesRepository.getById(id) }.getOrNull() }
            ?: return JSONObject().put("ok", false).put("error", "crash $id not found")
        val log = io { runCatching { crashLogRepository.readCrashLog(crash) }.getOrDefault(emptyList()) }
        val text = log.joinToString("\n")
        return crashJson(crash)
            .put("ok", true)
            .put("log", text.take(30000))
            .put("log_line_count", log.size)
    }

    private fun crashJson(crash: AppCrash): JSONObject = JSONObject()
        .put("id", crash.id)
        .put("package", crash.packageName)
        .put("app", crash.appName ?: "")
        .put("type", crash.crashType.name)
        .put("ts", crash.dateAndTime)
        .put("time", ts(crash.dateAndTime))
        .put("has_log", crash.logFile?.exists() == true)
        .put("has_dump", crash.logDumpFile?.exists() == true)

    private fun cleanLogcatLine(line: String): String {
        var s = line.trim()
        // 去掉 "MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: " 前缀，只留正文
        val m = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+[^:]*:\\s?").find(s)
        if (m != null) s = s.substring(m.range.last + 1).trimStart()
        return s.trim()
    }

    private fun parseCrashBlocks(text: String, pkgFilter: String): JSONArray {
        val lines = text.lines()
        val arr = JSONArray()
        var i = 0
        while (i < lines.size && arr.length() < 20) {
            val line = lines[i]
            if (!line.contains("FATAL EXCEPTION") && !line.contains("ANR in ")) {
                i++
                continue
            }
            val start = i
            var j = i + 1
            while (j < lines.size && j < start + 150 &&
                !lines[j].contains("FATAL EXCEPTION") && !lines[j].contains("ANR in ")
            ) {
                j++
            }
            val block = lines.subList(start, j).toList()
            val joined = block.joinToString("\n")
            val pkg = Regex("Process: ([^,]+)").find(joined)?.groupValues?.get(1)?.trim()
                ?: Regex("ANR in ([^\\s(]+)").find(joined)?.groupValues?.get(1)?.trim()
                ?: ""
            if (pkgFilter.isNotBlank() && !pkg.contains(pkgFilter, ignoreCase = true)) {
                i = j
                continue
            }
            val pid = Regex("PID: (\\d+)").find(joined)?.groupValues?.get(1) ?: ""
            val normalized = block.asSequence().map { cleanLogcatLine(it) }.toList()
            val exception = normalized.firstOrNull { it.startsWith("ANR in ") }
                ?.substringAfter("ANR in ")?.trim()
                ?: normalized.firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()
                ?: ""
            val stack = JSONArray()
            block.asSequence()
                .map { cleanLogcatLine(it) }
                .filter { it.startsWith("at ") || it.startsWith("Caused by:") || it.contains("Exception:") }
                .take(50)
                .forEach { stack.put(it) }
            arr.put(
                JSONObject()
                    .put("package", pkg)
                    .put("pid", pid)
                    .put("exception", exception)
                    .put("stack", stack)
                    .put("raw", joined.take(12000)),
            )
            i = j
        }
        return arr
    }

    fun crashBuffer(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 200).coerceIn(10, 1000)
        val pkg = args.optString("package", "")
        var result = exec("logcat", "-b", "crash", "-d", "-t", limit.toString(), "-v", "threadtime")
        if (result.output.isBlank()) {
            result = exec("logcat", "-d", "-t", limit.toString(), "-v", "threadtime")
        }
        val text = result.output
        val crashes = parseCrashBlocks(text, pkg)
        val o = JSONObject()
            .put("ok", text.isNotBlank())
            .put("count", crashes.length())
            .put("crashes", crashes)
            .put("text", text.take(30000))
        if (text.isBlank()) {
            o.put("error", "crash buffer empty or not readable")
            o.put("hint", result.errorOutput.take(400))
        } else if (crashes.length() == 0) {
            o.put("hint", "crash buffer 里没有解析到崩溃块")
        }
        return o
    }

    fun crashWatch(args: JSONObject): JSONObject {
        val timeout = args.optLong("timeout_ms", 30000).coerceIn(2000, 180000)
        val pkg = args.optString("package", "")
        val deadline = System.currentTimeMillis() + timeout
        var baseline = crashBuffer(JSONObject().put("limit", 200)).optString("text", "")
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(600)
            val current = crashBuffer(JSONObject().put("limit", 200)).optString("text", "")
            if (current != baseline) {
                val fresh = current.lineSequence()
                    .filter { it.isNotBlank() }
                    .filter {
                        pkg.isBlank() ||
                            it.contains(pkg, ignoreCase = true) ||
                            it.contains("FATAL EXCEPTION") ||
                            it.contains("ANR in")
                    }
                    .joinToString("\n")
                if (fresh.isNotBlank()) {
                    val recent = logs(
                        JSONObject()
                            .put("limit", 200)
                            .put("package", pkg)
                            .put("level", "W"),
                    )
                    return JSONObject()
                        .put("ok", true)
                        .put("found", true)
                        .put("crash", fresh.take(30000))
                        .put("crashes", parseCrashBlocks(current, pkg))
                        .put("recent_logs", recent.optJSONArray("entries") ?: JSONArray())
                }
                baseline = current
            }
        }
        return JSONObject().put("ok", false).put("error", "timeout after ${timeout}ms")
    }

    // -------------------------------------------------------------------- apps

    fun apps(args: JSONObject): JSONObject {
        val query = args.optString("query", "")
        val limit = args.optInt("limit", 200).coerceIn(1, 500)
        val pm = context.packageManager
        val arr = JSONArray()
        runCatching {
            for (ai in pm.getInstalledApplications(0)) {
                if (arr.length() >= limit) break
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
                if (query.isNotBlank() &&
                    !label.contains(query, ignoreCase = true) &&
                    !ai.packageName.contains(query, ignoreCase = true)
                ) continue
                val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
                arr.put(
                    JSONObject()
                        .put("package", ai.packageName)
                        .put("label", label)
                        .put("version", pi?.versionName ?: "")
                        .put("version_code", pi?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L)
                        .put("uid", ai.uid)
                        .put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        .put("enabled", ai.enabled),
                )
            }
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("apps", arr)
    }

    fun appInfo(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "")
        if (pkg.isBlank()) return JSONObject().put("ok", false).put("error", "package is required")
        val pm = context.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "package not found: $pkg")
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val pid = pidOf(pkg)
        val o = JSONObject()
            .put("ok", true)
            .put("package", pkg)
            .put("label", runCatching { pm.getApplicationLabel(ai!!).toString() }.getOrDefault(""))
            .put("version", pi.versionName ?: "")
            .put("version_code", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong())
            .put("target_sdk", ai?.targetSdkVersion ?: 0)
            .put("min_sdk", ai?.minSdkVersion ?: 0)
            .put("uid", ai?.uid ?: 0)
            .put("debuggable", ai != null && (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("system", ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            .put("data_dir", ai?.dataDir ?: "")
            .put("source_dir", ai?.sourceDir ?: "")
            .put("install_time", pi.firstInstallTime)
            .put("update_time", pi.lastUpdateTime)
            .put("pid", pid ?: "")
            .put("pid_hint", if (pid == null) "读取其他应用 pid 需要 Shizuku/root" else "")
        return o
    }

    private fun pidOf(pkg: String): String? {
        val direct = exec("pidof", pkg)
        if (direct.isSuccessful && direct.output.isNotBlank()) {
            return direct.output.trim().split(Regex("\\s+")).firstOrNull()
        }
        val ps = exec("ps", "-A")
        return ps.output.lineSequence()
            .firstOrNull { it.trim().endsWith(" $pkg") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
    }

    fun processes(args: JSONObject): JSONObject {
        val query = args.optString("query", "")
        val limit = args.optInt("limit", 200).coerceIn(1, 500)
        val result = exec("ps", "-A")
        val arr = JSONArray()
        result.output.lineSequence().drop(1).forEach { line ->
            if (arr.length() >= limit) return@forEach
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val pid = parts[1]
            val name = parts.last()
            if (query.isNotBlank() && !name.contains(query, ignoreCase = true)) return@forEach
            runCatching {
                arr.put(
                    JSONObject()
                        .put("user", parts[0])
                        .put("pid", pid)
                        .put("name", name),
                )
            }
        }
        val o = JSONObject().put("ok", result.isSuccessful).put("count", arr.length()).put("processes", arr)
        if (arr.length() == 0) o.put("hint", "默认终端只能看到本应用进程；请授权 Shizuku/root")
        return o
    }

    // ------------------------------------------------------------------ device

    fun deviceInfo(): JSONObject {
        val dm = context.resources.displayMetrics
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        val storageTotal = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val storageFree = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        return JSONObject()
            .put("ok", true)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("screen", JSONArray().put(dm.widthPixels).put(dm.heightPixels))
            .put("density", dm.densityDpi)
            .put("mem_total", mi.totalMem)
            .put("mem_avail", mi.availMem)
            .put("low_memory", mi.lowMemory)
            .put("storage_total", storageTotal)
            .put("storage_free", storageFree)
    }

    // ------------------------------------------------------------------ report

    fun debugReport(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "")
        if (pkg.isBlank()) return JSONObject().put("ok", false).put("error", "package is required")
        val logLimit = args.optInt("log_limit", 200).coerceIn(20, 1000)
        val report = JSONObject().put("ok", true).put("package", pkg)
        runCatching { report.put("app", appInfo(JSONObject().put("package", pkg))) }
        runCatching {
            report.put(
                "errors",
                logs(
                    JSONObject()
                        .put("package", pkg)
                        .put("level", "W")
                        .put("limit", logLimit),
                ),
            )
        }
        runCatching {
            report.put(
                "crashes",
                crashes(JSONObject().put("package", pkg).put("limit", 5)),
            )
        }
        runCatching { report.put("crash_buffer", crashBuffer(JSONObject().put("package", pkg).put("limit", 100))) }
        runCatching {
            report.put(
                "stats",
                logStats(JSONObject().put("package", pkg).put("limit", logLimit)),
            )
        }
        runCatching {
            report.put("device", deviceInfo())
        }
        return report
    }

    fun dumpsys(args: JSONObject): JSONObject {
        val service = args.optString("service", "activity")
        if (!DUMPSYS_ALLOWED.contains(service)) {
            return JSONObject().put("ok", false)
                .put("error", "service not allowed: $service")
                .put("allowed", JSONArray(DUMPSYS_ALLOWED.toList()))
        }
        val result = exec("dumpsys", service)
        return JSONObject()
            .put("ok", result.isSuccessful || result.output.isNotBlank())
            .put("service", service)
            .put("text", result.output.take(30000))
            .put("error", result.errorOutput.take(1000))
            .put("hint", if (!result.isSuccessful && result.output.contains("Permission Denial")) "dumpsys 需要 Shizuku/root" else "")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiDebugEntryPoint {
    fun crashesRepository(): CrashesRepository
    fun crashLogRepository(): CrashLogRepository
    fun logLineParser(): LogLineParser
    fun getDefaultTerminalUseCase(): GetDefaultTerminalUseCase
    fun terminals(): Map<TerminalType, Terminal>
}
