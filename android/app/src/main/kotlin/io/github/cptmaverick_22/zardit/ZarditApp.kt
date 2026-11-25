package io.github.cptmaverick_22.zardit

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ZarditApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _initResult = MutableStateFlow<Result<Unit>?>(null)
    val initResult: StateFlow<Result<Unit>?> = _initResult.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            _initResult.value = runCatching {
                YoutubeDL.getInstance().init(this@ZarditApp)
                FFmpeg.getInstance().init(this@ZarditApp)
            }
        }
    }
}
