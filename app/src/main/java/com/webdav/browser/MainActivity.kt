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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlin.math.abs
import androidx.compose.foundation.gestures.awaitFirstDown

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
    var allItems by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var allMedia by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var viewIdx by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(settings.sortBy) }
    var sortAsc by remember { mutableStateOf(settings.sortAsc) }
    var page by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun sortList(list: List<DavItem>): List<DavItem> {
        val showHidden = settings.showHidden
        val filtered = list.filter { !it.isTrash && (showHidden || !it.isHidden) }
        val dirs = filtered.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = filtered.filter { !it.isDir }.let { f ->
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
                    curPath = path
                    allItems = sortList(r)
                    allMedia = allItems.filter { it.isMedia }
                    page = 0
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
        allItems = sortList(allItems)
        allMedia = allItems.filter { it.isMedia }
    }

    when (screen) {
        Screen.LOGIN -> LoginPage(settings) {
            client = WebDavClient(settings.serverUrl, settings.username, settings.password)
            screen = Screen.BROWSER; load("/")
        }
        Screen.BROWSER -> {
            LaunchedEffect(Unit) { if (allItems.isEmpty()) load("/") }
            BackHandler {
                if (curPath == "/") (ctx as? ComponentActivity)?.finish()
                else {
                    val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/")
                }
            }
            BrowserPage(allItems, allMedia, curPath, sortBy, sortAsc, page, settings,
                onNav = {
                    if (it.isDir) load(it.href)
                    else if (it.isMedia) {
                        viewIdx = allMedia.indexOf(it).coerceAtLeast(0)
                        screen = Screen.VIEWER
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
                onPageChange = { page = it }
            )
        }
        Screen.VIEWER -> {
            BackHandler { screen = Screen.BROWSER; load(curPath) }
            ViewerPage(allMedia, viewIdx, client!!, settings,
                onBack = { screen = Screen.BROWSER; load(curPath) },
                onDel = { d ->
                    allItems = allItems.filter { it.href != d.href }
                    allMedia = allMedia.filter { it.href != d.href }
                }
            )
        }
        Screen.TRASH -> {
            BackHandler { screen = Screen.BROWSER }
            TrashPage(client!!, curPath) { screen = Screen.BROWSER; load(curPath) }
        }
    }
}

// ========== 登录/设置页 ==========
@Composable
fun LoginPage(s: Settings, onConnect: () -> Unit) {
    var url by remember { mutableStateOf(s.serverUrl.ifBlank { "http://" }) }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf(s.password) }
    var cd by remember { mutableStateOf(s.confirmDelete) }
    var dp by remember { mutableStateOf(s.deleteButtonPos) }
    var ps by remember { mutableStateOf(s.pageSize) }
    var sh by remember { mutableStateOf(s.showHidden) }

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
        Text("📄 显示设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))

        // 每页数量
        Text("每页显示数量", color = Color.Gray, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20, 50, 100, 200).forEach { n ->
                FilterChip(selected = ps == n, onClick = { ps = n },
                    label = { Text("$n", fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))

        // 显示隐藏文件
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示隐藏文件", color = Color.White); Spacer(Modifier.weight(1f))
            Switch(sh, { sh = it })
        }

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
                s.confirmDelete = cd; s.deleteButtonPos = dp
                s.pageSize = ps; s.showHidden = sh
                onConnect()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("连接", fontSize = 18.sp) }
    }
}

// ========== 浏览页（分页 + 全文件类型） ==========
@Composable
fun BrowserPage(
    allItems: List<DavItem>, allMedia: List<DavItem>,
    path: String, sortBy: String, sortAsc: Boolean,
    page: Int, settings: Settings,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onTrash: () -> Unit, onPageChange: (Int) -> Unit
) {
    val pageSize = settings.pageSize
    val folders = allItems.filter { it.isDir }
    val files = allItems.filter { !it.isDir }
    val totalFiles = files.size
    val totalPages = ((totalFiles + pageSize - 1) / pageSize).coerceAtLeast(1)
    val curPage = page.coerceIn(0, totalPages - 1)

    // 本页要显示的文件
    val pageFiles = files.drop(curPage * pageSize).take(pageSize)

    Column(Modifier.fillMaxSize()) {
        // 顶栏
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
        // 排序栏
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

        // 文件数量统计
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${folders.size}个文件夹  ${files.size}个文件",
                color = Color(0xFF666666), fontSize = 11.sp)
        }

        // 网格内容
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 只在第一页显示文件夹
            if (curPage == 0) {
                folders.forEach { f ->
                    item(span = { GridItemSpan(3) }) {
                        FolderRow(f, settings) { onNav(f) }
                    }
                }
            }
            // 当前页的文件
            items(pageFiles.size) { i ->
                val item = pageFiles[i]
                FileCell(item, settings) { onNav(item) }
            }
        }

        // 分页栏
        if (totalPages > 1) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PBtn("◀◀") { onPageChange(0) }
                Spacer(Modifier.width(8.dp))
                PBtn("◀") { onPageChange((curPage - 1).coerceAtLeast(0)) }
                Spacer(Modifier.width(12.dp))
                Text("${curPage + 1} / $totalPages", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                PBtn("▶") { onPageChange((curPage + 1).coerceAtMost(totalPages - 1)) }
                Spacer(Modifier.width(8.dp))
                PBtn("▶▶") { onPageChange(totalPages - 1) }
            }
        }
    }
}

@Composable
fun FolderRow(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 20.sp, modifier = Modifier.graphicsLayer(alpha = alpha))
        Spacer(Modifier.width(10.dp))
        Text(item.name, color = Color.White.copy(alpha = alpha), fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FileCell(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.4f else 1f
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
            .clickable { onClick() }
            .graphicsLayer(alpha = alpha)
    ) {
        if (item.isImage) {
            AsyncImage(
                model = settings.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 非图片文件用图标
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(item.fileIcon, fontSize = 30.sp)
            }
        }
        // 文件大小
        if (item.size > 0) {
            Text(fmtSize(item.size), fontSize = 9.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart)
                    .background(Color(0xBB000000), RoundedCornerShape(0.dp, 0.dp, 4.dp, 0.dp))
                    .padding(3.dp))
        }
        // 文件名
        Text(item.name, fontSize = 9.sp, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAA000000)).padding(3.dp))
    }
}

@Composable
fun IBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 20.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
fun PBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 16.sp, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333)).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp))
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

    // 缩放状态（仅图片）
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // 视频播放器引用
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }

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

        // ===== 内容 =====
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
                VPlayer(
                    url = client.fileUrl(cur.href),
                    client = client,
                    onPlayerCreated = { playerRef = it },
                    onPlayerReleased = { playerRef = null }
                )
            }
        }

        // ===== 手势层 =====
        Box(
            Modifier.fillMaxSize().pointerInput(idx, cur.isVideo, scale) {
                // 自定义手势识别：区分点击和滑动
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        val startTime = System.currentTimeMillis()
                        var endX = startX
                        var endY = startY
                        var moved = false

                        // 跟踪手指移动
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            endX = change.position.x
                            endY = change.position.y
                            if (kotlin.math.abs(endX - startX) > 20 || kotlin.math.abs(endY - startY) > 20) moved = true
                            if (!change.pressed) break
                        }

                        val dx = endX - startX
                        val dy = endY - startY
                        val dt = System.currentTimeMillis() - startTime
                        val adx = kotlin.math.abs(dx)
                        val ady = kotlin.math.abs(dy)

                        // 上下滑动 → 切换文件（图片和视频通用）
                        if (ady > 80 && ady > adx * 1.5f) {
                            if (dy > 0) nav(-1) else nav(1)  // 下滑=上一个, 上滑=下一个
                        }
                        // 点击（没有明显移动 且 时间短）
                        else if (!moved && dt < 300) {
                            val w = size.width
                            if (cur.isVideo) {
                                // 视频：左点=快退5s, 右点=快进5s, 中间=暂停/播放
                                val p = playerRef
                                if (p != null) {
                                    if (startX < w * 0.35f) {
                                        p.seekTo((p.currentPosition - 5000).coerceAtLeast(0))
                                    } else if (startX > w * 0.65f) {
                                        p.seekTo((p.currentPosition + 5000).coerceAtMost(p.duration))
                                    } else {
                                        p.playWhenReady = !p.playWhenReady
                                    }
                                }
                            } else if (scale <= 1.05f) {
                                // 图片（未缩放）：左点=上一个, 右点=下一个
                                if (startX < w * 0.4f) nav(-1)
                                else if (startX > w * 0.6f) nav(1)
                            }
                        }
                    }
                }
            }
        )

        // ===== 顶部信息 =====
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("${idx + 1}/${local.size}  ${if (cur.size > 0) "(${fmtSize(cur.size)}) " else ""}${cur.name}",
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // ===== 返回 =====
        Text("✕", color = Color.White, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xAA333333))
                .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp))

        // ===== 缩放提示 =====
        if (scale > 1.05f) {
            Text("${(scale * 100).toInt()}%  双击复原",
                color = Color(0xAAFFFFFF), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }

        // ===== 删除按钮 =====
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

        // ===== 底部操作提示 =====
        if (scale <= 1.05f) {
            val hint = if (cur.isVideo) "左右点按快进退 · 上下滑动切换"
                       else "左右点按切换 · 上下滑动切换"
            Text(hint, color = Color(0x66FFFFFF), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

// ========== 视频播放器（无覆盖层） ==========
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VPlayer(
    url: String, client: WebDavClient,
    onPlayerCreated: (ExoPlayer) -> Unit,
    onPlayerReleased: () -> Unit
) {
    val ctx = LocalContext.current

    val player = remember(url) {
        ExoPlayer.Builder(ctx).setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.getClient()))
        ).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(player) { onPlayerCreated(player) }

    DisposableEffect(url) {
        onDispose {
            onPlayerReleased()
            player.release()
        }
    }

    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = false   // ← 无覆盖层！
            }
        },
        update = { view ->
            view.player = player
        },
        modifier = Modifier.fillMaxSize()
    )
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
            text = { Text("永久删除 ${trashItems.size} 个文件？不可恢复！") },
            confirmButton = {
                TextButton({
                    showEmptyDlg = false
                    scope.launch(Dispatchers.IO) {
                        client.emptyTrash(currentPath)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
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
                Text("加载中...", color = Color.Gray)
            }
        } else if (trashItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♻", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("回收站是空的", color = Color.Gray)
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
                    TrashRow(entry, client, ctx, scope,
                        onRestored = { trashItems = trashItems.filter { it.id != entry.id } },
                        onDeleted = { trashItems = trashItems.filter { it.id != entry.id } })
                }
            }
        }
    }
}

@Composable
fun TrashRow(
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
        Text(when { entry.isImage -> "🖼️"; entry.isVideo -> "🎬"; else -> "📄" }, fontSize = 28.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.fileName, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                if (entry.size > 0) Text(fmtSize(entry.size), color = Color(0xFF666666), fontSize = 10.sp)
                Text("  ${entry.date}", color = Color(0xFF555555), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (busy) {
            Text("...", color = Color.Yellow, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
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
                    }.padding(horizontal = 12.dp, vertical = 6.dp))
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
                    }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
