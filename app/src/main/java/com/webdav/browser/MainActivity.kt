package com.webdav.browser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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
    var url by remember {
        val initial = s.serverUrl.ifBlank { "http://" }
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf(s.password) }
    var cd by remember { mutableStateOf(s.confirmDelete) }
    var dp by remember { mutableStateOf(s.deleteButtonPos) }
    var ps by remember { mutableStateOf(s.pageSize) }
    var sh by remember { mutableStateOf(s.showHidden) }
    var vm by remember { mutableStateOf(s.viewMode) }

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

        Text("每页显示数量", color = Color.Gray, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20, 50, 100, 200).forEach { n ->
                FilterChip(selected = ps == n, onClick = { ps = n },
                    label = { Text("$n", fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))

        Text("默认视图", color = Color.Gray, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("grid" to "网格", "list" to "列表", "waterfall" to "瀑布流").forEach { (v, l) ->
                FilterChip(selected = vm == v, onClick = { vm = v },
                    label = { Text(l, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))

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
                s.serverUrl = url.text.trimEnd('/'); s.username = user; s.password = pass
                s.confirmDelete = cd; s.deleteButtonPos = dp
                s.pageSize = ps; s.showHidden = sh; s.viewMode = vm
                onConnect()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("连接", fontSize = 18.sp) }
    }
}

// ========== 浏览页 ==========
@Composable
fun BrowserPage(
    allItems: List<DavItem>, allMedia: List<DavItem>,
    path: String, sortBy: String, sortAsc: Boolean,
    page: Int, settings: Settings,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onTrash: () -> Unit, onPageChange: (Int) -> Unit
) {
    var viewMode by remember { mutableStateOf(settings.viewMode) }

    val pageSize = settings.pageSize
    val folders = allItems.filter { it.isDir }
    val files = allItems.filter { !it.isDir }
    val totalFiles = files.size
    val totalPages = ((totalFiles + pageSize - 1) / pageSize).coerceAtLeast(1)
    val curPage = page.coerceIn(0, totalPages - 1)
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
            // 视图切换按钮
            IBtn(when (viewMode) { "list" -> "☰"; "waterfall" -> "▥"; else -> "▦" }) {
                viewMode = when (viewMode) {
                    "grid" -> "list"
                    "list" -> "waterfall"
                    else -> "grid"
                }
                settings.viewMode = viewMode
            }
            Spacer(Modifier.width(4.dp))
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

        // 统计
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${folders.size}个文件夹  ${files.size}个文件",
                color = Color(0xFF666666), fontSize = 11.sp)
            Text(
                when (viewMode) { "list" -> "列表"; "waterfall" -> "瀑布流"; else -> "网格" },
                color = Color(0xFF666666), fontSize = 11.sp
            )
        }

        // 内容区域
        Box(Modifier.weight(1f)) {
            when (viewMode) {
                "list" -> ListView(folders, pageFiles, curPage, settings, onNav)
                "waterfall" -> WaterfallView(folders, pageFiles, curPage, settings, onNav)
                else -> GridView(folders, pageFiles, curPage, settings, onNav)
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

// ========== 网格视图（原版） ==========
@Composable
fun GridView(
    folders: List<DavItem>, files: List<DavItem>,
    curPage: Int, settings: Settings, onNav: (DavItem) -> Unit
) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (curPage == 0) {
            folders.forEach { f ->
                item(span = { GridItemSpan(3) }) { FolderRow(f, settings) { onNav(f) } }
            }
        }
        items(files.size) { i -> FileGridCell(files[i], settings) { onNav(files[i]) } }
    }
}

// ========== 列表视图（详细信息） ==========
@Composable
fun ListView(
    folders: List<DavItem>, files: List<DavItem>,
    curPage: Int, settings: Settings, onNav: (DavItem) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (curPage == 0) {
            items(folders.size) { i -> FolderListRow(folders[i], settings) { onNav(folders[i]) } }
        }
        items(files.size) { i -> FileListRow(files[i], settings) { onNav(files[i]) } }
    }
}

@Composable
fun FolderListRow(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF222222)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 22.sp, modifier = Modifier.graphicsLayer(alpha = alpha))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White.copy(alpha = alpha), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("文件夹", color = Color(0xFF666666), fontSize = 11.sp)
        }
        if (item.date.isNotEmpty()) {
            Text(item.date.take(16), color = Color(0xFF555555), fontSize = 10.sp)
        }
    }
}

@Composable
fun FileListRow(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.4f else 1f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E1E1E)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图或图标
        if (item.isImage) {
            AsyncImage(
                model = settings.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp))
                    .graphicsLayer(alpha = alpha)
            )
        } else {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A)).graphicsLayer(alpha = alpha),
                contentAlignment = Alignment.Center
            ) {
                Text(item.fileIcon, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.width(12.dp))

        // 文件信息
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White.copy(alpha = alpha), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(".${item.ext}", color = Color(0xFF4fc3f7), fontSize = 11.sp)
                if (item.size > 0) {
                    Text(fmtSize(item.size), color = Color(0xFF888888), fontSize = 11.sp)
                }
            }
        }

        // 日期
        if (item.date.isNotEmpty()) {
            Text(item.date.take(16), color = Color(0xFF555555), fontSize = 10.sp,
                maxLines = 1, modifier = Modifier.widthIn(max = 120.dp))
        }
    }
}

// ========== 瀑布流视图 ==========
@Composable
fun WaterfallView(
    folders: List<DavItem>, files: List<DavItem>,
    curPage: Int, settings: Settings, onNav: (DavItem) -> Unit
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp
    ) {
        if (curPage == 0) {
            folders.forEach { f ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    FolderRow(f, settings) { onNav(f) }
                }
            }
        }
        items(files.size) { i -> WaterfallCell(files[i], settings) { onNav(files[i]) } }
    }
}

@Composable
fun WaterfallCell(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.4f else 1f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).clickable { onClick() }
            .graphicsLayer(alpha = alpha)
    ) {
        if (item.isImage) {
            // 图片按原始比例显示（产生瀑布流效果的关键）
            AsyncImage(
                model = settings.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 80.dp, max = 400.dp)  // 限制极端比例
            )
        } else {
            // 非图片文件
            Box(
                Modifier.fillMaxWidth().height(100.dp)
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Text(item.fileIcon, fontSize = 36.sp)
            }
        }
        // 底部信息
        Column(Modifier.padding(6.dp)) {
            Text(item.name, color = Color.White, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.size > 0) {
                    Text(fmtSize(item.size), color = Color(0xFF888888), fontSize = 10.sp)
                }
                Text(".${item.ext}", color = Color(0xFF4fc3f7), fontSize = 10.sp)
            }
        }
    }
}

// ========== 通用网格格子 ==========
@Composable
fun FileGridCell(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.4f else 1f
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).clickable { onClick() }
            .graphicsLayer(alpha = alpha)
    ) {
        if (item.isImage) {
            AsyncImage(
                model = settings.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(item.fileIcon, fontSize = 30.sp)
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

@Composable
fun FolderRow(item: DavItem, settings: Settings, onClick: () -> Unit) {
    val alpha = if (item.isHidden) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 20.sp, modifier = Modifier.graphicsLayer(alpha = alpha))
        Spacer(Modifier.width(10.dp))
        Text(item.name, color = Color.White.copy(alpha = alpha), fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var offsetY by remember { mutableStateOf(0f) }
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
        // 内容
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cur.isImage) {
                AsyncImage(
                    model = client.fileUrl(cur.href), contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY)
                )
            } else if (cur.isVideo) {
                VPlayer(
                    url = client.fileUrl(cur.href), client = client,
                    onPlayerCreated = { playerRef = it },
                    onPlayerReleased = { playerRef = null }
                )
            }
        }

        // 手势层（统一处理：点击、滑动、双指缩放、双击缩放）
        Box(
            Modifier.fillMaxWidth()
                .then(if (cur.isVideo) Modifier.fillMaxHeight(0.85f) else Modifier.fillMaxHeight())
                .pointerInput(idx, cur.isVideo, scale) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            val startTime = System.currentTimeMillis()
                            var endX = startX
                            var endY = startY
                            var moved = false
                            var pointersUsed = 1
        
                            // 跟踪缩放
                            var initialDist = 0f
                            var initialScale = scale
        
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointers = event.changes.filter { it.pressed }
        
                                if (pointers.isEmpty()) {
                                    // 所有手指抬起
                                    val lastChange = event.changes.firstOrNull()
                                    if (lastChange != null) {
                                        endX = lastChange.position.x
                                        endY = lastChange.position.y
                                    }
                                    break
                                }
        
                                if (pointers.size >= 2 && cur.isImage) {
                                    pointersUsed = 2
                                    val p1 = pointers[0].position
                                    val p2 = pointers[1].position
                                    val dist = kotlin.math.sqrt(
                                        (p2.x - p1.x) * (p2.x - p1.x) +
                                        (p2.y - p1.y) * (p2.y - p1.y)
                                    )
        
                                    if (initialDist == 0f) {
                                        initialDist = dist
                                        initialScale = scale
                                    } else if (dist > 0f) {
                                        val newScale = (initialScale * dist / initialDist).coerceIn(1f, 5f)
                                        scale = newScale
        
                                        if (scale > 1f) {
                                            // 双指中点的平移
                                            val midX = (p1.x + p2.x) / 2
                                            val midY = (p1.y + p2.y) / 2
                                            val prevMidX = (pointers[0].previousPosition.x + pointers[1].previousPosition.x) / 2
                                            val prevMidY = (pointers[0].previousPosition.y + pointers[1].previousPosition.y) / 2
                                            offsetX += midX - prevMidX
                                            offsetY += midY - prevMidY
                                            val maxX = (scale - 1f) * size.width / 2
                                            val maxY = (scale - 1f) * size.height / 2
                                            offsetX = offsetX.coerceIn(-maxX, maxX)
                                            offsetY = offsetY.coerceIn(-maxY, maxY)
                                        } else {
                                            offsetX = 0f; offsetY = 0f
                                        }
                                    }
                                    // 消费事件防止滚动
                                    pointers.forEach { it.consume() }
                                } else if (pointers.size == 1) {
                                    val change = pointers[0]
                                    endX = change.position.x
                                    endY = change.position.y
        
                                    // 放大后单指拖动
                                    if (scale > 1.05f && cur.isImage && pointersUsed == 1) {
                                        val panX = change.position.x - change.previousPosition.x
                                        val panY = change.position.y - change.previousPosition.y
                                        if (kotlin.math.abs(panX) > 1 || kotlin.math.abs(panY) > 1) {
                                            moved = true
                                            offsetX += panX
                                            offsetY += panY
                                            val maxX = (scale - 1f) * size.width / 2
                                            val maxY = (scale - 1f) * size.height / 2
                                            offsetX = offsetX.coerceIn(-maxX, maxX)
                                            offsetY = offsetY.coerceIn(-maxY, maxY)
                                            change.consume()
                                        }
                                    } else {
                                        if (kotlin.math.abs(endX - startX) > 20 ||
                                            kotlin.math.abs(endY - startY) > 20) moved = true
                                    }
                                }
                            }
        
                            // 双指缩放后不做其他动作
                            if (pointersUsed >= 2) continue
        
                            val dx = endX - startX
                            val dy = endY - startY
                            val dt = System.currentTimeMillis() - startTime
                            val adx = kotlin.math.abs(dx)
                            val ady = kotlin.math.abs(dy)
        
                            // 放大状态下拖动过就不触发其他手势
                            if (scale > 1.05f && moved) continue
        
                            // 上下滑动 → 切换（图片视频通用）
                            if (ady > 80 && ady > adx * 1.5f && scale <= 1.05f) {
                                if (dy > 0) nav(-1) else nav(1)
                            }
                            // 点击（没有明显移动 且 时间短）
                            else if (!moved && dt < 300) {
                                // 双击检测
                                val now = System.currentTimeMillis()
                                if (cur.isImage && now - lastTapTime < 350) {
                                    // 双击缩放
                                    if (scale > 1.1f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                    // 延迟判断是否是单击（等待可能的双击）
                                    val tapX = startX
                                    val tapW = size.width
                                    scope.launch {
                                        delay(360)
                                        if (lastTapTime == now) {
                                            // 确认是单击
                                            if (cur.isVideo) {
                                                val p = playerRef
                                                if (p != null) {
                                                    when {
                                                        tapX < tapW * 0.35f ->
                                                            p.seekTo((p.currentPosition - 5000).coerceAtLeast(0))
                                                        tapX > tapW * 0.65f ->
                                                            p.seekTo((p.currentPosition + 5000).coerceAtMost(p.duration))
                                                        else ->
                                                            p.playWhenReady = !p.playWhenReady
                                                    }
                                                }
                                            } else if (scale <= 1.05f) {
                                                if (tapX < tapW * 0.4f) nav(-1)
                                                else if (tapX > tapW * 0.6f) nav(1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        )

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
                .background(Color(0x99DD2222))
                .clickable { if (settings.confirmDelete) showDlg = true else doDelete() }
                .padding(horizontal = 20.dp, vertical = 12.dp))

        // 底部提示
        if (scale <= 1.05f) {
            val hint = if (cur.isVideo) "左右点按快进退 · 上下滑动切换"
            else "左右点按切换 · 上下滑动切换"
            Text(hint, color = Color(0x66FFFFFF), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

// ========== 视频播放器 ==========
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
        onDispose { onPlayerReleased(); player.release() }
    }

    // 跟踪播放进度
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }

    // 每 500ms 刷新进度
    LaunchedEffect(player) {
        while (true) {
            if (!isSeeking) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.let { if (it < 0) 0 else it }
                isPlaying = player.playWhenReady && player.playbackState != ExoPlayer.STATE_ENDED
            }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 视频画面（无任何覆盖层）
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false  // 不要系统控制层
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        // 自定义底部进度条（常驻）
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF111111))
                .padding(start = 12.dp, end = 100.dp, top = 4.dp, bottom = 4.dp)
        ) {
            // 进度条
            Slider(
                value = if (isSeeking) seekPos
                        else if (duration > 0) position.toFloat() / duration.toFloat()
                        else 0f,
                onValueChange = { v ->
                    isSeeking = true
                    seekPos = v
                },
                onValueChangeFinished = {
                    player.seekTo((seekPos * duration).toLong())
                    isSeeking = false
                },
                modifier = Modifier.fillMaxWidth().height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF4fc3f7),
                    activeTrackColor = Color(0xFF4fc3f7),
                    inactiveTrackColor = Color(0xFF333333)
                )
            )

            // 时间显示
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    fmtTime(if (isSeeking) (seekPos * duration).toLong() else position),
                    color = Color(0xFFCCCCCC), fontSize = 12.sp
                )
                Text(
                    fmtTime(duration),
                    color = Color(0xFF888888), fontSize = 12.sp
                )
            }
        }
    }
}

/** 格式化时间 ms → HH:MM:SS 或 MM:SS */
fun fmtTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
// ========== 回收站 ==========
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
