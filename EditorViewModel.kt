package com.cut.editor

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import kotlinx.coroutines.*
import java.io.File

data class Clip(
    val id: Long, val uri: Uri, val srcMs: Long,
    val inMs: Long = 0, val outMs: Long = srcMs,
    val speed: Float = 1f, val mute: Boolean = false
) { val durMs get() = ((outMs - inMs) / speed).toLong() }

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    var video by mutableStateOf(listOf<Clip>()); private set
    var audio by mutableStateOf(listOf<Clip>()); private set
    var selected by mutableStateOf<Long?>(null)
    var progress by mutableStateOf(-1f); private set   // -1 = no exporting
    var message by mutableStateOf<String?>(null)
    private var nextId = 1L
    private var transformer: Transformer? = null

    private suspend fun duration(uri: Uri): Long = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().run {
            try { setDataSource(getApplication(), uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            } finally { release() }
        }
    }

    fun add(uris: List<Uri>, isAudio: Boolean) = viewModelScope.launch {
        val new = uris.map { Clip(nextId++, it, duration(it)) }.filter { it.srcMs > 0 }
        if (isAudio) audio = audio + new else video = video + new
    }

    private fun update(id: Long, f: (Clip) -> Clip) {
        video = video.map { if (it.id == id) f(it) else it }
        audio = audio.map { if (it.id == id) f(it) else it }
    }

    fun delete() { val s = selected ?: return
        video = video.filter { it.id != s }; audio = audio.filter { it.id != s }; selected = null }

    fun setSpeed(v: Float) { selected?.let { update(it) { c -> c.copy(speed = v) } } }
    fun toggleMute() { selected?.let { update(it) { c -> c.copy(mute = !c.mute) } } }

    fun move(dir: Int) { val s = selected ?: return
        fun List<Clip>.mv(): List<Clip> { val i = indexOfFirst { it.id == s }
            val j = i + dir; if (i < 0 || j !in indices) return this
            return toMutableList().also { l -> l.add(j, l.removeAt(i)) } }
        video = video.mv(); audio = audio.mv() }

    /** Corta el clip seleccionado en [posMs] (relativo al clip, en tiempo de fuente). */
    fun split(posMs: Long) {
        val s = selected ?: return
        fun List<Clip>.sp(): List<Clip> = flatMap { c ->
            val cut = c.inMs + (posMs * c.speed).toLong()
            if (c.id == s && cut > c.inMs + 200 && cut < c.outMs - 200)
                listOf(c.copy(outMs = cut), c.copy(id = nextId++, inMs = cut))
            else listOf(c) }
        video = video.sp(); audio = audio.sp()
    }

    private fun Clip.toItem(audioOnly: Boolean): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(inMs).setEndPositionMs(outMs).build()).build()
        val b = EditedMediaItem.Builder(mi).setRemoveAudio(mute).setRemoveVideo(audioOnly)
        if (speed != 1f) {
            val sonic = SonicAudioProcessor().apply { setSpeed(speed) }
            b.setEffects(Effects(listOf(sonic), if (audioOnly) listOf() else listOf(SpeedChangeEffect(speed))))
        }
        return b.build()
    }

    fun export() {
        if (video.isEmpty() || progress >= 0) return
        val seqs = mutableListOf(EditedMediaItemSequence.Builder(*video.map { it.toItem(false) }.toTypedArray()).build())
        if (audio.isNotEmpty())
            seqs += EditedMediaItemSequence.Builder(*audio.map { it.toItem(true) }.toTypedArray()).build()
        val out = File(getApplication<Application>().cacheDir, "export.mp4").also { it.delete() }
        val t = Transformer.Builder(getApplication())
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) { saveToGallery(out); progress = -1f
                    message = "Exportado en Movies/CutEditor" }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) { progress = -1f
                    message = "Error: ${e.errorCodeName}" }
            }).build()
        transformer = t; progress = 0f
        t.start(Composition.Builder(seqs).build(), out.absolutePath)
        viewModelScope.launch { val h = ProgressHolder()
            while (progress >= 0f) { if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) progress = h.progress / 100f
                delay(300) } }
    }

    fun cancelExport() { transformer?.cancel(); progress = -1f }

    private fun saveToGallery(f: File) {
        val r = getApplication<Application>().contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "cut_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CutEditor") }
        r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)?.let { u ->
            r.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
        f.delete()
    }

    override fun onCleared() { transformer?.cancel() }
}
