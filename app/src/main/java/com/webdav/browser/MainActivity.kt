package com.webdav.browser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot(settings) }
            }
        }
    }
}

enum class Screen { LOGIN, BROWSER, VIEWER, TRASH }

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 -> "${b}B"
    b < 1048576 -> "${"%.1f".format(b / 1024.0)}K"
    b < 1073741824 -> "${"%.1f".format(b / 1048576.0)}M"
    else -> "${"%.2f".format(b / 1073741824.0)}G"
}

@Composable
fun AppRoot(settings: Settings) {
    var screen by remember { mutableStateOf(if (settings.isConfigured()) Screen.BROWSER else Screen.LOGIN) }
    var client by remember {
        mutableStateOf(
            if (settings.isConfigured()) WebDavClient(settings.serverUrl, settings.username, settings.password) else null
        )
    }
    var curPath by remember { mutableStateOf("/") }
    var items by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var media by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var viewIdx by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(settings.sortBy) }
    var sortAsc by remember { mutableStateOf(settings.sortAsc) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun sortList(list: List<DavItem>): List<DavItem> {
        val dirs = list.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = list.filter { !it.isDir }.let { f ->
            when (sortBy) {
                "ext" -> f.sortedWith(compareBy({ it.ext }, { it.name.lowercase() }))
                "size" -> f.sortedBy { it.size }
                "date" -> f.sortedBy { it.date }
                else -> f.sortedBy { it.name.lowercase() }
            }.let { if (!sortAsc) it.reversed() else it }
        }
        return (if (sortAsc) dirs else dirs.reversed()) + files
    }

    fun load(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = client!!.listDir(path)
                withContext(Dispatchers.Main) {
                    curPath = path; items = sortList(r); media = items.filter { it.isMedia }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun resort(by: String? = null) {
        if (by != null) {
            if (sortBy == by) sortAsc = !sortAsc else { sortBy = by; sortAsc = true }
        }
        settings.sortBy = sortBy; settings.sortAsc = sortAsc
        items = sortList(items); media = items.filter { it.isMedia }
    }

    when (screen) {
        Screen.LOGIN -> LoginPage(settings) {
            client = WebDavClient(settings.serverUrl, settings.username, settings.password)
            screen = Screen.BROWSER; load("/")
        }

        Screen.BROWSER -> {
            LaunchedEffect(Unit) { if (items.isEmpty()) load("/") }
            BackHandler {
                if (curPath == "/") (ctx as? ComponentActivity)?.finish()
                else {
                    val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/")
                }
            }
            BrowserPage(items, curPath, sortBy, sortAsc,
                onNav = {
                    if (it.isDir) load(it.href)
                    else if (it.isMedia) {
                        viewIdx = media.indexOf(it).coerceAtLeast(0); screen = Screen.VIEWER
                    }
                },
                onUp = {
                    val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/")
                },
                onHome = { load("/") },
                onSort = { resort(it) },
                onToggle = { sortAsc = !sortAsc; resort() },
                onSettings = { screen = Screen.LOGIN },
                onTrash = { screen = Screen.TRASH },
                settings = settings
            )
        }

        Screen.VIEWER -> {
            BackHandler { screen = Screen.BROWSER; load(curPath) }
            ViewerPage(media, viewIdx, client!!, settings,
                onBack = { screen = Screen.BROWSER; load(curPath) },
                onDel = { d ->
                    items = items.filter { it.href != d.href }
                    media = media.filter { it.href != d.href }
                }
            )
        }

        Screen.TRASH -> {
            BackHandler { screen = Screen.BROWSER }
            TrashPage(client!!, curPath) { screen = Screen.BROWSER; load(curPath) }
        }
    }
}

// ========== 登录页 ==========
@Composable
fun LoginPage(s: Settings, onConnect: () -> Unit) {
    var url by remember { mutableStateOf(s.serverUrl.ifBlank { "http://" }) }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf(s.password) }
    var cd by remember { mutableStateOf(s.confirmDelete) }
    var dp by remember { mutableStateOf(s.deleteButtonPos) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("🌐 WebDAV 连接", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("http://192.168.1.100:5000") })
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(user, { user = it }, label = { Text("用户名（可空）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("密码（可空）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(20.dp))

        Text("🗑️ 删除设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("删除前确认", color = Color.White); Spacer(Modifier.weight(1f))
            Switch(cd, { cd = it })
        }
        Spacer(Modifier.height(8.dp))
        Text("删除按钮位置", color = Color.Gray, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "top-left" to "↖左上", "top-right" to "↗右上",
                "bottom-left" to "↙左下", "bottom-right" to "↘右下"
            ).forEach { (v, l) ->
                FilterChip(selected = dp == v, onClick = { dp = v },
                    label = { Text(l, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                s.serverUrl = url.trimEnd('/'); s.username = user; s.password = pass
                s.confirmDelete = cd; s.deleteButtonPos = dp; onConnect()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("连接", fontSize = 18.sp) }
    }
}

// ========== 浏览页 ==========
@Composable
fun BrowserPage(
    items: List<DavItem>, path: String, sortBy: String, sortAsc: Boolean,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onTrash: () -> Unit, settings: Settings
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IBtn("⬆") { onUp() }; IBtn("🏠") { onHome() }
            Text(path, color = Color.Gray, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IBtn("♻") { onTrash() }
            Spacer(Modifier.width(4.dp))
            IBtn("⚙") { onSettings() }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1A1A))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("name" to "名称", "ext" to "后缀", "size" to "大小", "date" to "日期")
                .forEach { (k, l) ->
                    FilterChip(sortBy == k, { onSort(k) },
                        { Text(l, fontSize = 12.sp) }, Modifier.height(32.dp))
                }
            FilterChip(false, { onToggle() },
                { Text(if (sortAsc) "↑升序" else "↓降序", fontSize = 12.sp) },
                Modifier.height(32.dp))
        }

        val folders = items.filter { it.isDir }
        val mediaList = items.filter { it.isMedia }

        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            folders.forEach { f ->
                item(span = { GridItemSpan(3) }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF222222)).clickable { onNav(f) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📁", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                        Text(f.name, color = Color.White, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(mediaList.size) { i ->
                val item = mediaList[i]
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222)).clickable { onNav(item) }
                ) {
                    if (item.isImage) {
                        AsyncImage(
                            model = settings.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
                            contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("🎬", fontSize = 32.sp)
                        }
                    }
                    if (item.size > 0) {
                        Text(fmtSize(item.size), fontSize = 9.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart)
                                .background(Color(0xBB000000), RoundedCornerShape(0.dp, 0.dp, 4.dp, 0.dp))
                                .padding(3.dp))
                    }
                    Text(item.name, fontSize = 9.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color(0xAA000000)).padding(3.dp))
                }
            }
        }
    }
}

@Composable
fun IBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 20.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

// ========== 全屏浏览页 ==========
@Composable
fun ViewerPage(
    items: List<DavItem>, startIdx: Int, client: WebDavClient, settings: Settings,
    onBack: () -> Unit, onDel: (DavItem) -> Unit
) {
    var idx by remember { mutableStateOf(startIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var local by remember { mutableStateOf(items.toList()) }
    var showDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    LaunchedEffect(idx) { scale = 1f; offsetX = 0f; offsetY = 0f }

    if (local.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val cur = local[idx.coerceIn(0, local.size - 1)]

    fun doDelete() {
        val toDelete = cur
        scope.launch(Dispatchers.IO) {
            val success = client.moveToTrash(toDelete.href)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(ctx, "已移到回收站", Toast.LENGTH_SHORT).show()
                    onDel(toDelete)
                    local = local.filter { it.href != toDelete.href }
                    if (local.isEmpty()) { onBack(); return@withContext }
                    if (idx >= local.size) idx = 0
                } else {
                    Toast.makeText(ctx, "移动失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun nav(d: Int) {
        if (local.isNotEmpty()) idx = (idx + d + local.size) % local.size
    }

    if (showDlg) {
        AlertDialog(
            onDismissRequest = { showDlg = false },
            title = { Text("移到回收站") },
            text = { Text("将 ${cur.name} 移到回收站？") },
            confirmButton = {
                TextButton({ showDlg = false; doDelete() }) { Text("确定", color = Color.Red) }
            },
            dismissButton = { TextButton({ showDlg = false }) { Text("取消") } }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cur.isImage) {
                AsyncImage(
                    model = client.fileUrl(cur.href), contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(idx) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x; offsetY += pan.y
                                    val maxX = (scale - 1f) * size.width / 2
                                    val maxY = (scale - 1f) * size.height / 2
                                    offsetX = offsetX.coerceIn(-maxX, maxX)
                                    offsetY = offsetY.coerceIn(-maxY, maxY)
                                } else { offsetX = 0f; offsetY = 0f }
                            }
                        }
                        .pointerInput(idx) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1.1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                                else scale = 2.5f
                            })
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY)
                )
            } else if (cur.isVideo) {
                VPlayer(client.fileUrl(cur.href), client)
            }
        }

        // 左右点击切换（仅未缩放时）
        if (scale <= 1.05f) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.4f).fillMaxHeight().clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() }
                ) { nav(-1) })
                Spacer(Modifier.weight(0.2f))
                Box(Modifier.weight(0.4f).fillMaxHeight().clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() }
                ) { nav(1) })
            }
        }

        // 顶部信息
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("${idx + 1}/${local.size}  ${if (cur.size > 0) "(${fmtSize(cur.size)}) " else ""}${cur.name}",
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // 返回
        Text("✕", color = Color.White, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xAA333333))
                .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp))

        // 缩放提示
        if (scale > 1.05f) {
            Text("${(scale * 100).toInt()}%  双击复原",
                color = Color(0xAAFFFFFF), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }

        // 删除按钮
        val al = when (settings.deleteButtonPos) {
            "top-left" -> Alignment.TopStart; "top-right" -> Alignment.TopEnd
            "bottom-left" -> Alignment.BottomStart; else -> Alignment.BottomEnd
        }
        val pd = when (settings.deleteButtonPos) {
            "top-left" -> Modifier.padding(start = 8.dp, top = 52.dp)
            "top-right" -> Modifier.padding(end = 8.dp, top = 52.dp)
            "bottom-left" -> Modifier.padding(start = 8.dp, bottom = 16.dp)
            else -> Modifier.padding(end = 8.dp, bottom = 16.dp)
        }
        Text("🗑️ 删除", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(al).then(pd).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFDD2222))
                .clickable { if (settings.confirmDelete) showDlg = true else doDelete() }
                .padding(horizontal = 20.dp, vertical = 12.dp))

        // 底部计数
        if (scale <= 1.05f) {
            Text("◀ ${idx + 1}/${local.size} ▶", color = Color(0x99FFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

// ========== 回收站页面 ==========
@Composable
fun TrashPage(client: WebDavClient, currentPath: String, onBack: () -> Unit) {
    var trashItems by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showEmptyDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun loadTrash() {
        loading = true
        scope.launch(Dispatchers.IO) {
            val items = client.listTrash(currentPath)
            withContext(Dispatchers.Main) { trashItems = items; loading = false }
        }
    }

    LaunchedEffect(Unit) { loadTrash() }

    if (showEmptyDlg) {
        AlertDialog(
            onDismissRequest = { showEmptyDlg = false },
            title = { Text("清空回收站") },
            text = { Text("永久删除回收站中的 ${trashItems.size} 个文件？\n此操作不可恢复！") },
            confirmButton = {
                TextButton({
                    showEmptyDlg = false
                    scope.launch(Dispatchers.IO) {
                        client.emptyTrash(currentPath)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "回收站已清空", Toast.LENGTH_SHORT).show()
                            trashItems = emptyList()
                        }
                    }
                }) { Text("永久删除", color = Color.Red) }
            },
            dismissButton = { TextButton({ showEmptyDlg = false }) { Text("取消") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IBtn("✕") { onBack() }
            Spacer(Modifier.width(8.dp))
            Text("♻ 回收站", color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IBtn("🔄") { loadTrash() }
            Spacer(Modifier.width(4.dp))
            if (trashItems.isNotEmpty()) {
                Text("清空", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFCC3333)).clickable { showEmptyDlg = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", color = Color.Gray, fontSize = 16.sp)
            }
        } else if (trashItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♻", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("回收站是空的", color = Color.Gray, fontSize = 16.sp)
                }
            }
        } else {
            Text("  共 ${trashItems.size} 个文件", color = Color.Gray, fontSize = 12.sp,
                modifier = Modifier.padding(8.dp))

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(trashItems) { _, entry ->
                    TrashEntryRow(entry, client, ctx, scope,
                        onRestored = { trashItems = trashItems.filter { it.id != entry.id } },
                        onDeleted = { trashItems = trashItems.filter { it.id != entry.id } }
                    )
                }
            }
        }
    }
}

@Composable
fun TrashEntryRow(
    entry: TrashEntry, client: WebDavClient,
    ctx: android.content.Context, scope: CoroutineScope,
    onRestored: () -> Unit, onDeleted: () -> Unit
) {
    var busy by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when { entry.isImage -> "🖼️"; entry.isVideo -> "🎬"; else -> "📄" },
            fontSize = 28.sp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.fileName, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.size > 0) {
                Text(fmtSize(entry.size), color = Color(0xFF666666), fontSize = 10.sp)
            }
            Text(entry.date, color = Color(0xFF555555), fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (busy) {
            Text("...", color = Color.Yellow, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            Text("还原", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2266CC))
                    .clickable {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val (ok, msg) = client.restoreFromTrash(entry)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                busy = false; if (ok) onRestored()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            Spacer(Modifier.width(6.dp))
            Text("彻删", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF993333))
                    .clickable {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            client.permanentDeleteTrashEntry(entry)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已永久删除", Toast.LENGTH_SHORT).show()
                                busy = false; onDeleted()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VPlayer(url: String, client: WebDavClient) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.getClient()))
        ).build()
    }
    DisposableEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
        onDispose { player.release() }
    }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize()
    )
}
