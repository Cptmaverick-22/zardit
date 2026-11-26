package io.github.cptmaverick_22.zardit.data

import android.content.Context
import android.os.Build
import com.yausername.youtubedl_android.YoutubeDL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val MAX_LINES = 80
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val clean = redact(message).replace("\n", " | ").take(400)
        val entry = "[${fmt.format(Date())}] [$tag] $clean"
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast(entry)
    }

    @Synchronized
    fun buildReport(context: Context): String {
        val engineVer = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() ?: "unknown"
        val igConnected = InstagramAuth.hasSession(context)
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        return buildString {
            appendLine("=== Zardit Android Diagnostics ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: $abis")
            appendLine("Engine: $engineVer")
            appendLine("IG Connected: $igConnected")
            appendLine("--- Recent Log ---")
            if (lines.isEmpty()) {
                appendLine("(no entries yet)")
            } else {
                lines.forEach { appendLine(it) }
            }
        }
    }

    private fun redact(text: String): String = text
        .replace(Regex("""(sessionid|csrftoken|ds_user_id)=[^;\s]+""", RegexOption.IGNORE_CASE), "$1=<redacted>")
        .replace(Regex("""([?&](?:igsh|ig_rid|xmt|slof)=)[^&\s]+""", RegexOption.IGNORE_CASE), "$1<redacted>")
}
