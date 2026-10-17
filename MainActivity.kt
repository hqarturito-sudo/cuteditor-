package com.cut.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DP_PER_SEC = 36
// Cache de miniaturas limitada a 4 MB para ahorrar RAM
private val thumbs = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(k: String, v: Bitmap) = v.byteCount
}

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Editor(vm) } } }
    }
}

@Composable
fun Editor(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Un solo reproductor: la lista de reproducción = clips de la pista de video
    LaunchedEffect(vm.video) {
        val i = player.currentMediaItemIndex; val p = player.currentPosition
        player.setMediaItems(vm.video.map {
            MediaItem.Builder().setUri(it.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.inMs).setEndPositionMs(it.outMs).build()).build()
        }, i.coerceIn(0, (vm.video.size - 1).coerceAtLeast(0)), p)
        player.prepare()
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.add(it, false) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { vm.add(it, true) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = false } },
            Modifier.fillMaxWidth().weight(1f).background(Color.Black))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({ if (player.isPlaying) player.pause() else player.play() }) { Text("▶/⏸") }
            TextButton({ player.seekToPreviousMediaItem() }) { Text("⏮") }
            TextButton({ player.seekToNextMediaItem() }) { Text("⏭") }
        }
        // Barra de herramientas
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({ pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("+Video") }
            Button({ pickAudio.launch("audio/*") }) { Text("+Audio") }
            OutlinedButton({ vm.split(player.currentPosition) }) { Text("Cortar") }
            OutlinedButton({ vm.delete() }) { Text("Borrar") }
            OutlinedButton({ vm.move(-1) }) { Text("◀") }
            OutlinedButton({ vm.move(1) }) { Text("▶") }
            OutlinedButton({ vm.toggleMute() }) { Text("Mute") }
            listOf(0.5f, 1f, 2f).forEach { v -> OutlinedButton({ vm.setSpeed(v) }) { Text("${v}x") } }
            Button({ vm.export() }, enabled = vm.progress < 0) { Text("Exportar") }
        }
        Spacer(Modifier.height(6.dp))
        if (vm.progress >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator({ vm.progress }, Modifier.weight(1f))
            TextButton({ vm.cancelExport() }) { Text("Cancelar") }
        }
        vm.message?.let { Text(it, color = Color.Yellow); LaunchedEffect(it) { kotlinx.coroutines.delay(3000); vm.message = null } }
        // Timeline multipista (scroll compartido)
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll).heightIn(min = 140.dp)) {
            Track("V", vm.video, vm.selected, true) { vm.selected = it }
            Spacer(Modifier.height(4.dp))
            Track("A", vm.audio, vm.selected, false) { vm.selected = it }
        }
    }
}

@Composable
fun Track(label: String, clips: List<Clip>, sel: Long?, showThumb: Boolean, onSel: (Long) -> Unit) {
    Row(Modifier.height(60.dp).background(Color(0xFF1E1E1E))) {
        clips.forEach { c ->
            Box(Modifier.width((c.durMs / 1000f * DP_PER_SEC).coerceAtLeast(24f).dp).fillMaxHeight().padding(1.dp)
                .border(if (c.id == sel) 2.dp else 1.dp, if (c.id == sel) Color.Yellow else Color.Gray)
                .background(if (showThumb) Color(0xFF2B4C7E) else Color(0xFF2E7D32))
                .clickable { onSel(c.id) }) {
                if (showThumb) Thumb(c)
                Text("$label ${if (c.speed != 1f) "${c.speed}x" else ""}${if (c.mute) " 🔇" else ""}",
                    Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(2.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun Thumb(c: Clip) {
    val ctx = LocalContext.current
    val key = "${c.uri}@${c.inMs}"
    val bmp by produceState<Bitmap?>(thumbs.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(ctx, c.uri)
                r.getScaledFrameAtTime(c.inMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 72)
                    ?.also { thumbs.put(key, it) }
            } catch (_: Exception) { null } finally { r.release() }
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}
