package com.webdav.browser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(color = MaterialTheme.colorScheme.background) { AppRoot(settings) }
        }}
    }
}

enum class Screen { LOGIN, BROWSER, VIEWER }

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 -> "${b}B"
    b < 1048576 -> "${"%.1f".format(b/1024.0)}K"
    b < 1073741824 -> "${"%.1f".format(b/1048576.0)}M"
    else -> "${"%.2f".format(b/1073741824.0)}G"
}

@Composable
fun AppRoot(settings: Settings) {
    var screen by remember { mutableStateOf(if (settings.isConfigured()) Screen.BROWSER else Screen.LOGIN) }
    var client by remember { mutableStateOf(
        if (settings.isConfigured()) WebDavClient(settings.serverUrl, settings.username, settings.password) else null
    )}
    var curPath by remember { mutableStateOf("/") }
    var items by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var media by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var viewIdx by remember { mutableIntStateOf(0) }
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

    fun load(path: String) { scope.launch(Dispatchers.IO) {
        try {
            val r = client!!.listDir(path)
            withContext(Dispatchers.Main) {
                curPath = path; items = sortList(r); media = items.filter { it.isMedia }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }}
    }}

    fun resort(by: String? = null) {
        if (by != null) { if (sortBy == by) sortAsc = !sortAsc else { sortBy = by; sortAsc = true } }
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
                else { val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/") }
            }
            BrowserPage(items, curPath, sortBy, sortAsc,
                onNav = { if (it.isDir) load(it.href) else if (it.isMedia) {
                    viewIdx = media.indexOf(it).coerceAtLeast(0); screen = Screen.VIEWER
                }},
                onUp = { val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/") },
                onHome = { load("/") },
                onSort = { resort(it) },
                onToggle = { sortAsc = !sortAsc; resort() },
                onSettings = { screen = Screen.LOGIN }, settings = settings
            )
        }
        Screen.VIEWER -> {
            BackHandler { screen = Screen.BROWSER; load(curPath) }
            ViewerPage(media, viewIdx, client!!, settings,
                onBack = { screen = Screen.BROWSER; load(curPath) },
                onDel = { d -> items = items.filter { it.href != d.href }; media = media.filter { it.href != d.href } }
            )
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

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center) {
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
            listOf("top-left" to "↖左上", "top-right" to "↗右上",
                "bottom-left" to "↙左下", "bottom-right" to "↘右下").forEach { (v, l) ->
                FilterChip(selected = dp == v, onClick = { dp = v }, label = { Text(l, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            s.serverUrl = url.trimEnd('/'); s.username = user; s.password = pass
            s.confirmDelete = cd; s.deleteButtonPos = dp; onConnect()
        }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("连接", fontSize = 18.sp)
        }
    }
}

// ========== 浏览页 ==========
@Composable
fun BrowserPage(
    items: List<DavItem>, path: String, sortBy: String, sortAsc: Boolean,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit, settings: Settings
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IBtn("⬆") { onUp() }; IBtn("🏠") { onHome() }
            Text(path, color = Color.Gray, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IBtn("⚙") { onSettings() }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("name" to "名称","ext" to "后缀","size" to "大小","date" to "日期").forEach { (k, l) ->
                FilterChip(sortBy == k, { onSort(k) }, { Text(l, fontSize = 12.sp) }, Modifier.height(32.dp))
            }
            FilterChip(false, { onToggle() },
                { Text(if (sortAsc) "↑升序" else "↓降序", fontSize = 12.sp) }, Modifier.height(32.dp))
        }

        val folders = items.filter { it.isDir }
        val mediaList = items.filter { it.isMedia }

        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {

            folders.forEach { f -> item(span = { GridItemSpan(3) }) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222222)).clickable { onNav(f) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("📁", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                    Text(f.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }}

            items(mediaList.size) { i -> val it = mediaList[i]
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222222)).clickable { onNav(it) }) {
                    if (it.isImage) AsyncImage(
                        model = settings.serverUrl.trimEnd('/') + "/" + it.href.trimStart('/'),
                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("🎬", fontSize = 32.sp) }
                    if (it.size > 0) Text(fmtSize(it.size), fontSize = 9.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart)
                            .background(Color(0xBB000000), RoundedCornerShape(0.dp,0.dp,4.dp,0.dp)).padding(3.dp))
                    Text(it.name, fontSize = 9.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color(0xAA000000)).padding(3.dp))
                }
            }
        }
    }
}

@Composable
fun IBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
        .background(Color(0xFF333333)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp))
}

// ========== 全屏浏览页 ==========
@Composable
fun ViewerPage(
    items: List<DavItem>, startIdx: Int, client: WebDavClient, settings: Settings,
    onBack: () -> Unit, onDel: (DavItem) -> Unit
) {
    var idx by remember { mutableIntStateOf(startIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var local by remember { mutableStateOf(items.toList()) }
    var showDlg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (local.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val cur = local[idx.coerceIn(0, local.size - 1)]

    fun doDelete() { scope.launch(Dispatchers.IO) {
        client.delete(cur.href)
        withContext(Dispatchers.Main) {
            onDel(cur); local = local.filter { it.href != cur.href }
            if (local.isEmpty()) { onBack(); return@withContext }
            if (idx >= local.size) idx = 0
        }
    }}

    fun nav(d: Int) { if (local.isNotEmpty()) idx = (idx + d + local.size) % local.size }

    if (showDlg) AlertDialog(
        onDismissRequest = { showDlg = false },
        title = { Text("确认删除") }, text = { Text("删除 ${cur.name}？") },
        confirmButton = { TextButton({ showDlg = false; doDelete() }) { Text("删除", color = Color.Red) } },
        dismissButton = { TextButton({ showDlg = false }) { Text("取消") } }
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 内容
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cur.isImage) AsyncImage(model = client.fileUrl(cur.href), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else if (cur.isVideo) VPlayer(client.fileUrl(cur.href), client)
        }

        // 左右点击
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.4f).fillMaxHeight().clickable(
                indication = null, interactionSource = remember { MutableInteractionSource() }) { nav(-1) })
            Spacer(Modifier.weight(0.2f))
            Box(Modifier.weight(0.4f).fillMaxHeight().clickable(
                indication = null, interactionSource = remember { MutableInteractionSource() }) { nav(1) })
        }

        // 顶部信息
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("${idx+1}/${local.size}  ${if(cur.size>0)"(${fmtSize(cur.size)}) " else ""}${cur.name}",
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // 返回按钮
        Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopStart)
            .padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA333333))
            .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp))

        // 删除按钮（位置可配置）
        val al = when(settings.deleteButtonPos) {
            "top-left" -> Alignment.TopStart; "top-right" -> Alignment.TopEnd
            "bottom-left" -> Alignment.BottomStart; else -> Alignment.BottomEnd
        }
        val pd = when(settings.deleteButtonPos) {
            "top-left" -> Modifier.padding(start=8.dp,top=52.dp)
            "top-right" -> Modifier.padding(end=8.dp,top=52.dp)
            "bottom-left" -> Modifier.padding(start=8.dp,bottom=16.dp)
            else -> Modifier.padding(end=8.dp,bottom=16.dp)
        }
        Text("🗑️ 删除", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(al).then(pd).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFDD2222))
                .clickable { if(settings.confirmDelete) showDlg=true else doDelete() }
                .padding(horizontal = 20.dp, vertical = 12.dp))

        // 底部计数
        Text("◀ ${idx+1}/${local.size} ▶", color = Color(0x99FFFFFF), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
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
    AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxSize())
}
