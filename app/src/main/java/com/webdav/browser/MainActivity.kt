package com.webdav.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*

// ===== 配色 =====
val BG = Color(0xFF0D1117)
val CARD = Color(0xFF161B22)
val CARD2 = Color(0xFF1C2333)
val ACC = Color(0xFF58A6FF)
val ACC2 = Color(0xFF3FB950)
val DNG = Color(0xFFf85149)
val T1 = Color(0xFFE6EDF3)
val T2 = Color(0xFF8B949E)
val T3 = Color(0xFF484F58)
val SEL_BG = Color(0xFF1F3A5F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = CARD, primary = ACC)) {
                Surface(color = BG) { AppRoot(Settings(this@MainActivity)) }
            }
        }
    }
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }
}

enum class Screen { LOGIN, BROWSER, VIEWER, TRASH }

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""; b < 1024 -> "${b}B"; b < 1048576 -> "${"%.1f".format(b/1024.0)}K"
    b < 1073741824 -> "${"%.1f".format(b/1048576.0)}M"; else -> "${"%.2f".format(b/1073741824.0)}G"
}
fun fmtTime(ms: Long): String {
    val s = (ms/1000).coerceAtLeast(0); val h = s/3600; val m = (s%3600)/60; val sec = s%60
    return if (h>0) "%d:%02d:%02d".format(h,m,sec) else "%d:%02d".format(m,sec)
}

// ========== 通用美化组件 ==========
@Composable
fun TopBar(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(CARD2, CARD)))
        .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Ico(emoji: String, label: String, color: Color = T2, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(emoji, fontSize = 18.sp, color = color)
        Text(label, color = T3, fontSize = 8.sp, lineHeight = 10.sp)
    }
}

@Composable
fun Pill(text: String, active: Boolean = false, c: Color = ACC, onClick: () -> Unit) {
    val bg = if (active) c.copy(alpha = 0.18f) else Color.Transparent
    val fg = if (active) c else T2
    val bd = if (active) c.copy(alpha = 0.4f) else T3.copy(alpha = 0.25f)
    Text(text, color = fg, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, bd, RoundedCornerShape(16.dp))
            .background(bg).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp))
}

// ========== 根 ==========
@Composable
fun AppRoot(settings: Settings) {
    var screen by remember { mutableStateOf(if (settings.isConfigured()) Screen.BROWSER else Screen.LOGIN) }
    var client by remember { mutableStateOf(
        if (settings.isConfigured()) WebDavClient(settings.serverUrl, settings.username, settings.password) else null
    )}
    // 双标签：0=WebDAV, 1=本地
    var activeTab by remember { mutableStateOf(0) }
    // WebDAV 状态
    var davPath by remember { mutableStateOf("/") }
    var davItems by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var davMedia by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var davPage by remember { mutableStateOf(0) }
    var davSort by remember { mutableStateOf(settings.sortBy) }
    var davAsc by remember { mutableStateOf(settings.sortAsc) }
    // 本地状态
    val localClient = remember { LocalFileClient() }
    var localPath by remember { mutableStateOf(localClient.getDefaultRoot()) }
    var localFiles by remember { mutableStateOf<List<LocalFile>>(emptyList()) }
    var localPage by remember { mutableStateOf(0) }
    var localSort by remember { mutableStateOf("name") }
    var localAsc by remember { mutableStateOf(true) }
    // 查看器
    var viewIdx by remember { mutableStateOf(0) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    // 多选
    var davSel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localSel by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 重命名
    var renameTarget by remember { mutableStateOf<Any?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 忙碌
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }

    fun sortDav(list: List<DavItem>): List<DavItem> {
        val f = list.filter { !it.isTrash && (settings.showHidden || !it.isHidden) }
        val dirs = f.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = f.filter { !it.isDir }.let { ff -> when (davSort) {
            "ext" -> ff.sortedWith(compareBy({ it.ext }, { it.name.lowercase() }))
            "size" -> ff.sortedBy { it.size }; "date" -> ff.sortedBy { it.date }
            else -> ff.sortedBy { it.name.lowercase() }
        }.let { if (!davAsc) it.reversed() else it } }
        return (if (davAsc) dirs else dirs.reversed()) + files
    }

    fun sortLocal(list: List<LocalFile>): List<LocalFile> {
        val f = list.filter { settings.showHidden || !it.isHidden }
        val dirs = f.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = f.filter { !it.isDir }.let { ff -> when (localSort) {
            "ext" -> ff.sortedWith(compareBy({ it.ext }, { it.name.lowercase() }))
            "size" -> ff.sortedBy { it.size }; "date" -> ff.sortedBy { it.lastModified }
            else -> ff.sortedBy { it.name.lowercase() }
        }.let { if (!localAsc) it.reversed() else it } }
        return (if (localAsc) dirs else dirs.reversed()) + files
    }

    fun loadDav(path: String) { scope.launch(Dispatchers.IO) { try {
        val r = client!!.listDir(path)
        withContext(Dispatchers.Main) { davPath = path; davItems = sortDav(r); davMedia = davItems.filter { it.isMedia }; davPage = 0; davSel = emptySet() }
    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() } } } }

    fun loadLocal() { localFiles = sortLocal(localClient.listDir(localPath, settings.showHidden)); localPage = 0; localSel = emptySet() }

    fun resortDav(by: String? = null) {
        if (by != null) { if (davSort == by) davAsc = !davAsc else { davSort = by; davAsc = true } }
        settings.sortBy = davSort; settings.sortAsc = davAsc
        davItems = sortDav(davItems); davMedia = davItems.filter { it.isMedia }
    }

    fun resortLocal(by: String? = null) {
        if (by != null) { if (localSort == by) localAsc = !localAsc else { localSort = by; localAsc = true } }
        localFiles = sortLocal(localFiles)
    }

    // 跨栏传输
    fun uploadSelected() {
        if (localSel.isEmpty()) return; busy = true
        scope.launch(Dispatchers.IO) { var ok = 0; var fail = 0
            localSel.forEach { path -> val n = path.substringAfterLast('/'); busyMsg = "↑ $n"
                val d = localClient.readBytes(path); if (d != null && client!!.uploadBytes("$davPath$n", d)) ok++ else fail++ }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "上传 ${ok}成功 ${fail}失败", Toast.LENGTH_SHORT).show()
                busy = false; busyMsg = ""; localSel = emptySet(); loadDav(davPath) } }
    }

    fun downloadSelected() {
        if (davSel.isEmpty()) return; busy = true
        scope.launch(Dispatchers.IO) { var ok = 0; var fail = 0
            davSel.forEach { href -> val n = href.trimEnd('/').substringAfterLast('/'); busyMsg = "↓ $n"
                val d = client!!.downloadBytes(href); if (d != null && localClient.writeBytes("$localPath/$n", d)) ok++ else fail++ }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "下载 ${ok}成功 ${fail}失败", Toast.LENGTH_SHORT).show()
                busy = false; busyMsg = ""; davSel = emptySet(); loadLocal() } }
    }

    // 重命名弹窗
    if (renameTarget != null) AlertDialog(onDismissRequest = { renameTarget = null },
        title = { Text("重命名", color = T1) },
        text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACC)) },
        confirmButton = { TextButton({
            when (val t = renameTarget) {
                is LocalFile -> { localClient.rename(t.path, renameText); loadLocal() }
                is DavItem -> scope.launch(Dispatchers.IO) { client?.davRename(t.href, renameText); withContext(Dispatchers.Main) { loadDav(davPath) } }
            }; renameTarget = null
        }) { Text("确定", color = ACC) } },
        dismissButton = { TextButton({ renameTarget = null }) { Text("取消", color = T2) } })

    when (screen) {
        Screen.LOGIN -> LoginPage(settings) {
            client = WebDavClient(settings.serverUrl, settings.username, settings.password)
            screen = Screen.BROWSER; loadDav("/"); loadLocal()
        }

        Screen.BROWSER -> {
            LaunchedEffect(Unit) { if (davItems.isEmpty()) loadDav("/"); if (localFiles.isEmpty()) loadLocal() }
            BackHandler {
                if (davSel.isNotEmpty() || localSel.isNotEmpty()) { davSel = emptySet(); localSel = emptySet() }
                else if (activeTab == 0 && davPath != "/") { val p = davPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }; loadDav(if (p.endsWith("/")) p else "$p/") }
                else if (activeTab == 1) { val parent = localPath.substringBeforeLast('/')
                    if (parent.isNotEmpty() && parent != localPath) { localPath = parent; loadLocal() } else (ctx as? ComponentActivity)?.finish() }
                else (ctx as? ComponentActivity)?.finish()
            }

            Column(Modifier.fillMaxSize().background(BG)) {
                // ===== 标签栏 =====
                Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 8.dp)) {
                    TabBtn("💻 电脑", activeTab == 0, ACC) { activeTab = 0 }
                    Spacer(Modifier.width(4.dp))
                    TabBtn("📱 手机", activeTab == 1, ACC2) { activeTab = 1 }
                    Spacer(Modifier.weight(1f))
                    if (busy) Text(busyMsg, color = ACC, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    // 跨栏传输按钮（有选中时显示）
                    if (localSel.isNotEmpty()) {
                        Ico("⬆", "上传", ACC) { uploadSelected() }
                    }
                    if (davSel.isNotEmpty()) {
                        Ico("⬇", "下载", ACC2) { downloadSelected() }
                    }
                }

                if (activeTab == 0) {
                    // ===== WebDAV 面板 =====
                    DavPanel(davItems, davMedia, davPath, davSort, davAsc, davPage, davSel, settings,
                        onNav = { if (it.isDir) loadDav(it.href) else if (it.isMedia) {
                            viewIdx = davMedia.indexOf(it).coerceAtLeast(0); screen = Screen.VIEWER } },
                        onUp = { val p = davPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }; loadDav(if (p.endsWith("/")) p else "$p/") },
                        onHome = { loadDav("/") },
                        onSort = { resortDav(it) },
                        onToggle = { davAsc = !davAsc; resortDav() },
                        onSettings = { screen = Screen.LOGIN },
                        onTrash = { screen = Screen.TRASH },
                        onPageChange = { davPage = it },
                        onSelChange = { davSel = it },
                        onRename = { renameText = it.name; renameTarget = it }
                    )
                } else {
                    // ===== 本地面板 =====
                    LocalPanel(localFiles, localPath, localSort, localAsc, localPage, localSel, settings, localClient,
                        onNav = { if (it.isDir) { localPath = it.path; loadLocal() } },
                        onUp = { val p = localPath.substringBeforeLast('/')
                            if (p.isNotEmpty() && p != localPath) { localPath = p; loadLocal() } },
                        onSort = { resortLocal(it) },
                        onToggle = { localAsc = !localAsc; resortLocal() },
                        onSettings = { screen = Screen.LOGIN },
                        onPageChange = { localPage = it },
                        onSelChange = { localSel = it },
                        onRename = { renameText = it.name; renameTarget = it },
                        onDelete = { it.forEach { p -> localClient.delete(p) }; loadLocal() }
                    )
                }
            }
        }

        Screen.VIEWER -> {
            BackHandler { screen = Screen.BROWSER; loadDav(davPath) }
            ViewerPage(davMedia, viewIdx, client!!, settings,
                onBack = { screen = Screen.BROWSER; loadDav(davPath) },
                onDel = { d -> davItems = davItems.filter { it.href != d.href }; davMedia = davMedia.filter { it.href != d.href } })
        }

        Screen.TRASH -> {
            BackHandler { screen = Screen.BROWSER }
            TrashPage(client!!, davPath) { screen = Screen.BROWSER; loadDav(davPath) }
        }
    }
}

@Composable
fun TabBtn(label: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, color = if (active) color else T3, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(if (active) Modifier.border(width = 0.dp, color = Color.Transparent,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) else Modifier))
}

// ========== WebDAV 面板 ==========
@Composable
fun DavPanel(
    allItems: List<DavItem>, allMedia: List<DavItem>, path: String,
    sortBy: String, sortAsc: Boolean, page: Int,
    selected: Set<String>, settings: Settings,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onTrash: () -> Unit, onPageChange: (Int) -> Unit,
    onSelChange: (Set<String>) -> Unit, onRename: (DavItem) -> Unit
) {
    var viewMode by remember { mutableStateOf(settings.viewMode) }
    val ps = settings.pageSize
    val folders = allItems.filter { it.isDir }
    val files = allItems.filter { !it.isDir }
    val tp = ((files.size + ps - 1) / ps).coerceAtLeast(1)
    val cp = page.coerceIn(0, tp - 1)
    val pf = files.drop(cp * ps).take(ps)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // 工具栏
        TopBar {
            Ico("‹", "上级") { onUp() }; Ico("⌂", "主页") { onHome() }
            Text(path, color = T2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Ico(when (viewMode) { "list" -> "☰"; "waterfall" -> "▥"; else -> "▦" }, "视图") {
                viewMode = when (viewMode) { "grid" -> "list"; "list" -> "waterfall"; else -> "grid" }
                settings.viewMode = viewMode }
            Ico("♻", "回收站") { onTrash() }
            Ico("⚙", "设置") { onSettings() }
        }

        // 排序
        Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("name" to "名称", "ext" to "后缀", "size" to "大小", "date" to "日期").forEach { (k, l) ->
                Pill(l, sortBy == k) { onSort(k) } }
            Pill(if (sortAsc) "↑升序" else "↓降序") { onToggle() }
        }

        // 统计 + 选中操作
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${folders.size}文件夹 ${files.size}文件", color = T3, fontSize = 10.sp)
            if (selected.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已选${selected.size}", color = ACC, fontSize = 11.sp)
                    Text("全选", color = ACC, fontSize = 11.sp, modifier = Modifier.clickable {
                        onSelChange(files.map { it.href }.toSet()) })
                    Text("取消", color = T2, fontSize = 11.sp, modifier = Modifier.clickable { onSelChange(emptySet()) })
                }
            }
        }

        // 内容
        Box(Modifier.weight(1f)) {
            when (viewMode) {
                "list" -> DavListView(folders, pf, cp, settings, selected, onNav, onSelChange, onRename)
                "waterfall" -> DavWaterfallView(folders, pf, cp, settings, selected, onNav, onSelChange, onRename)
                else -> DavGridView(folders, pf, cp, settings, selected, onNav, onSelChange, onRename)
            }
        }

        // 分页
        if (tp > 1) Row(Modifier.fillMaxWidth().background(CARD).padding(4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PgBtn("«") { onPageChange(0) }; PgBtn("‹") { onPageChange((cp-1).coerceAtLeast(0)) }
            Text(" ${cp+1}/$tp ", color = T1, fontSize = 12.sp)
            PgBtn("›") { onPageChange((cp+1).coerceAtMost(tp-1)) }; PgBtn("»") { onPageChange(tp-1) }
        }
    }
}

// ========== 本地面板 ==========
@Composable
fun LocalPanel(
    allFiles: List<LocalFile>, path: String,
    sortBy: String, sortAsc: Boolean, page: Int,
    selected: Set<String>, settings: Settings, localClient: LocalFileClient,
    onNav: (LocalFile) -> Unit, onUp: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onPageChange: (Int) -> Unit,
    onSelChange: (Set<String>) -> Unit, onRename: (LocalFile) -> Unit,
    onDelete: (Set<String>) -> Unit
) {
    val ps = settings.pageSize
    val folders = allFiles.filter { it.isDir }
    val files = allFiles.filter { !it.isDir }
    val tp = ((files.size + ps - 1) / ps).coerceAtLeast(1)
    val cp = page.coerceIn(0, tp - 1)
    val pf = files.drop(cp * ps).take(ps)
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopBar {
            Ico("‹", "上级") { onUp() }
            Text(path.removePrefix(localClient.getDefaultRoot()).ifEmpty { "/" },
                color = T2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Ico("⚙", "设置") { onSettings() }
        }

        Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("name" to "名称", "ext" to "后缀", "size" to "大小", "date" to "日期").forEach { (k, l) ->
                Pill(l, sortBy == k) { onSort(k) } }
            Pill(if (sortAsc) "↑升序" else "↓降序") { onToggle() }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${folders.size}文件夹 ${files.size}文件", color = T3, fontSize = 10.sp)
            if (selected.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已选${selected.size}", color = ACC2, fontSize = 11.sp)
                Text("删除", color = DNG, fontSize = 11.sp, modifier = Modifier.clickable {
                    onDelete(selected); onSelChange(emptySet()) })
                Text("全选", color = ACC2, fontSize = 11.sp, modifier = Modifier.clickable {
                    onSelChange(files.map { it.path }.toSet()) })
                Text("取消", color = T2, fontSize = 11.sp, modifier = Modifier.clickable { onSelChange(emptySet()) })
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (cp == 0) items(folders.size) { i -> val f = folders[i]
                SwipeSelectRow(
                    selected = selected.contains(f.path),
                    onSelect = { onSelChange(if (selected.contains(f.path)) selected - f.path else selected + f.path) },
                    onTap = { if (selected.isNotEmpty()) onSelChange(if (selected.contains(f.path)) selected - f.path else selected + f.path) else onNav(f) },
                    onLongPress = { onRename(f) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📁", fontSize = 18.sp, modifier = Modifier.graphicsLayer(alpha = if (f.isHidden) 0.4f else 1f))
                        Spacer(Modifier.width(8.dp))
                        Text(f.name, color = if (f.isHidden) T3 else T1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(pf.size) { i -> val f = pf[i]
                SwipeSelectRow(
                    selected = selected.contains(f.path),
                    onSelect = { onSelChange(if (selected.contains(f.path)) selected - f.path else selected + f.path) },
                    onTap = { if (selected.isNotEmpty()) onSelChange(if (selected.contains(f.path)) selected - f.path else selected + f.path) else onNav(f) },
                    onLongPress = { onRename(f) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(f.fileIcon, fontSize = 18.sp, modifier = Modifier.graphicsLayer(alpha = if (f.isHidden) 0.4f else 1f))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = if (f.isHidden) T3 else T1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.size > 0) Text(fmtSize(f.size), color = T3, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        if (tp > 1) Row(Modifier.fillMaxWidth().background(CARD).padding(4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PgBtn("«") { onPageChange(0) }; PgBtn("‹") { onPageChange((cp-1).coerceAtLeast(0)) }
            Text(" ${cp+1}/$tp ", color = T1, fontSize = 12.sp)
            PgBtn("›") { onPageChange((cp+1).coerceAtMost(tp-1)) }; PgBtn("»") { onPageChange(tp-1) }
        }
    }
}

// ========== 滑动选择行（类似 MT 管理器） ==========
@Composable
fun SwipeSelectRow(
    selected: Boolean,
    onSelect: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) SEL_BG else Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val sX = down.position.x; val sY = down.position.y; val sT = System.currentTimeMillis()
                        var eX = sX; var eY = sY; var moved = false

                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            eX = c.position.x; eY = c.position.y
                            if (kotlin.math.abs(eX - sX) > 15 || kotlin.math.abs(eY - sY) > 15) moved = true
                            if (!c.pressed) break
                        }

                        val dx = eX - sX; val dt = System.currentTimeMillis() - sT

                        if (kotlin.math.abs(dx) > 60 && kotlin.math.abs(dx) > kotlin.math.abs(eY - sY) * 2) {
                            // 左右滑动 → 选中/取消
                            onSelect()
                        } else if (!moved && dt > 500) {
                            // 长按 → 重命名
                            onLongPress()
                        } else if (!moved && dt < 300) {
                            // 短按 → 导航或切换选中
                            onTap()
                        }
                    }
                }
            }
    ) { content() }
}

// ========== WebDAV 三种视图（带滑动选择） ==========
@Composable
fun DavGridView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings,
    sel: Set<String>, onNav: (DavItem) -> Unit, onSel: (Set<String>) -> Unit, onRename: (DavItem) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (p == 0) folders.forEach { f -> item(span = { GridItemSpan(3) }) {
            SwipeSelectRow(sel.contains(f.href), { onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) },
                { if (sel.isNotEmpty()) onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) else onNav(f) },
                { onRename(f) }) { FolderRow(f) }
        }}
        items(files.size) { i -> val it = files[i]
            Box(Modifier.then(if (sel.contains(it.href)) Modifier.border(2.dp, ACC, RoundedCornerShape(10.dp)) else Modifier)) {
                FileGridCell(it, s, sel.isNotEmpty(),
                    { if (sel.isNotEmpty()) onSel(if (sel.contains(it.href)) sel-it.href else sel+it.href) else onNav(it) },
                    { onSel(if (sel.contains(it.href)) sel-it.href else sel+it.href) },
                    { onRename(it) })
            }
        }
    }
}

@Composable
fun DavListView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings,
    sel: Set<String>, onNav: (DavItem) -> Unit, onSel: (Set<String>) -> Unit, onRename: (DavItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (p == 0) items(folders.size) { val f = folders[it]
            SwipeSelectRow(sel.contains(f.href), { onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) },
                { if (sel.isNotEmpty()) onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) else onNav(f) },
                { onRename(f) }) { FolderRow(f) }
        }
        items(files.size) { val f = files[it]
            SwipeSelectRow(sel.contains(f.href), { onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) },
                { if (sel.isNotEmpty()) onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) else onNav(f) },
                { onRename(f) }) { FileListRow(f, s) }
        }
    }
}

@Composable
fun DavWaterfallView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings,
    sel: Set<String>, onNav: (DavItem) -> Unit, onSel: (Set<String>) -> Unit, onRename: (DavItem) -> Unit) {
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), Modifier.fillMaxSize().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalItemSpacing = 4.dp) {
        if (p == 0) folders.forEach { f -> item(span = StaggeredGridItemSpan.FullLine) {
            SwipeSelectRow(sel.contains(f.href), { onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) },
                { if (sel.isNotEmpty()) onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) else onNav(f) },
                { onRename(f) }) { FolderRow(f) }
        }}
        items(files.size) { val f = files[it]
            Box(Modifier.then(if (sel.contains(f.href)) Modifier.border(2.dp, ACC, RoundedCornerShape(10.dp)) else Modifier)) {
                WaterfallCell(f, s, sel.isNotEmpty(),
                    { if (sel.isNotEmpty()) onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) else onNav(f) },
                    { onSel(if (sel.contains(f.href)) sel-f.href else sel+f.href) },
                    { onRename(f) })
            }
        }
    }
}

// ========== 文件格子 ==========
@Composable
fun FileGridCell(item: DavItem, s: Settings, hasSel: Boolean, onClick: () -> Unit, onSwipeSel: () -> Unit, onLong: () -> Unit) {
    val a = if (item.isHidden) 0.4f else 1f
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(CARD).graphicsLayer(alpha = a)
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                val d = awaitFirstDown(false); val sx = d.position.x; val sy = d.position.y; val st = System.currentTimeMillis()
                var ex = sx; var ey = sy; var mv = false
                while (true) { val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break
                    ex = c.position.x; ey = c.position.y; if (kotlin.math.abs(ex-sx)>15||kotlin.math.abs(ey-sy)>15) mv = true; if (!c.pressed) break }
                val dx = ex-sx; val dt = System.currentTimeMillis()-st
                if (kotlin.math.abs(dx)>50&&kotlin.math.abs(dx)>kotlin.math.abs(ey-sy)*2) onSwipeSel()
                else if (!mv&&dt>500) onLong()
                else if (!mv&&dt<300) onClick()
            }}
        }
    ) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/')+"/"+item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(item.fileIcon, fontSize = 28.sp) }
        if (item.size>0) Text(fmtSize(item.size), fontSize = 8.sp, color = T2,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xCC0D1117), RoundedCornerShape(0.dp,0.dp,6.dp,0.dp)).padding(2.dp))
        Text(item.name, fontSize = 9.sp, color = T1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0D1117)).padding(3.dp))
    }
}

@Composable
fun FileListRow(item: DavItem, s: Settings) {
    val a = if (item.isHidden) 0.4f else 1f
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).graphicsLayer(alpha = a),
        verticalAlignment = Alignment.CenterVertically) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/')+"/"+item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(CARD2),
            contentAlignment = Alignment.Center) { Text(item.fileIcon, fontSize = 18.sp) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = T1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(".${item.ext}", color = ACC, fontSize = 10.sp)
                if (item.size>0) Text(fmtSize(item.size), color = T2, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun WaterfallCell(item: DavItem, s: Settings, hasSel: Boolean, onClick: () -> Unit, onSwipeSel: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CARD)
        .graphicsLayer(alpha = if (item.isHidden) 0.4f else 1f)
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                val d = awaitFirstDown(false); val sx = d.position.x; val sy = d.position.y; val st = System.currentTimeMillis()
                var ex = sx; var ey = sy; var mv = false
                while (true) { val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break
                    ex = c.position.x; ey = c.position.y; if (kotlin.math.abs(ex-sx)>15||kotlin.math.abs(ey-sy)>15) mv = true; if (!c.pressed) break }
                val dx = ex-sx; val dt = System.currentTimeMillis()-st
                if (kotlin.math.abs(dx)>50&&kotlin.math.abs(dx)>kotlin.math.abs(ey-sy)*2) onSwipeSel()
                else if (!mv&&dt>500) onLong()
                else if (!mv&&dt<300) onClick()
            }}
        }
    ) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/')+"/"+item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp))
        else Box(Modifier.fillMaxWidth().height(80.dp).background(CARD2), contentAlignment = Alignment.Center) {
            Text(item.fileIcon, fontSize = 30.sp) }
        Column(Modifier.padding(5.dp)) {
            Text(item.name, color = T1, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.size>0) Text(fmtSize(item.size), color = T2, fontSize = 9.sp) }
    }
}

@Composable
fun FolderRow(item: DavItem) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD2).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("📁", fontSize = 18.sp, modifier = Modifier.graphicsLayer(alpha = if (item.isHidden) 0.4f else 1f))
        Spacer(Modifier.width(8.dp))
        Text(item.name, color = if (item.isHidden) T3 else T1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun PgBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 16.sp, color = T1, modifier = Modifier.clip(RoundedCornerShape(6.dp))
        .background(CARD2).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 3.dp))
}

// ========== 登录页（紧凑版） ==========
@Composable
fun LoginPage(s: Settings, onConnect: () -> Unit) {
    var url by remember { val init = s.serverUrl.ifBlank { "http://" }
        mutableStateOf(TextFieldValue(init, selection = TextRange(init.length))) }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf(s.password) }
    var cd by remember { mutableStateOf(s.confirmDelete) }
    var dp by remember { mutableStateOf(s.deleteButtonPos) }
    var ps by remember { mutableStateOf(s.pageSize) }
    var sh by remember { mutableStateOf(s.showHidden) }
    var vm by remember { mutableStateOf(s.viewMode) }

    Column(Modifier.fillMaxSize().background(BG).padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("🌐 WebDAV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = T1)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(url, { url = it }, label = { Text("服务器地址", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().height(54.dp), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACC),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(user, { user = it }, label = { Text("用户名", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).height(52.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACC),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
            OutlinedTextField(pass, { pass = it }, label = { Text("密码", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).height(52.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACC),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            s.serverUrl = url.text.trimEnd('/'); s.username = user; s.password = pass
            s.confirmDelete = cd; s.deleteButtonPos = dp; s.pageSize = ps; s.showHidden = sh; s.viewMode = vm
            onConnect()
        }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACC)) {
            Text("连接", fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(16.dp))
        // 设置区（紧凑）
        Text("设置", color = T2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每页", color = T2, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(20,50,100,200).forEach { Pill("$it", ps==it) { ps = it } } }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("视图", color = T2, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("grid" to "网格","list" to "列表","waterfall" to "瀑布").forEach { (v,l) -> Pill(l, vm==v) { vm = v } } }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("隐藏文件", color = T2, fontSize = 12.sp); Spacer(Modifier.weight(1f))
            Switch(checked = sh, onCheckedChange = { sh = it }, modifier = Modifier.height(28.dp), colors = SwitchDefaults.colors(checkedThumbColor = ACC, checkedTrackColor = ACC.copy(0.3f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("删除确认", color = T2, fontSize = 12.sp); Spacer(Modifier.weight(1f))
            Switch(checked = cd, onCheckedChange = { cd = it }, modifier = Modifier.height(28.dp), colors = SwitchDefaults.colors(checkedThumbColor = DNG, checkedTrackColor = DNG.copy(0.3f)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("删除按钮", color = T2, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("top-left" to "左上","top-right" to "右上","bottom-left" to "左下","bottom-right" to "右下").forEach { (v,l) ->
                    Pill(l, dp==v, DNG) { dp = v } } }
        }
    }
}

// ========== 全屏浏览页 ==========
@Composable
fun ViewerPage(items: List<DavItem>, startIdx: Int, client: WebDavClient, settings: Settings,
    onBack: () -> Unit, onDel: (DavItem) -> Unit) {
    var idx by remember { mutableStateOf(startIdx.coerceIn(0, (items.size-1).coerceAtLeast(0))) }
    var local by remember { mutableStateOf(items.toList()) }
    var showDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }; var offY by remember { mutableStateOf(0f) }
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }
    var lastTap by remember { mutableStateOf(0L) }
    LaunchedEffect(idx) { scale = 1f; offX = 0f; offY = 0f }
    if (local.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val cur = local[idx.coerceIn(0, local.size-1)]

    fun del() { val d = cur; scope.launch(Dispatchers.IO) {
        if (client.moveToTrash(d.href)) withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "已移到回收站", Toast.LENGTH_SHORT).show(); onDel(d)
            local = local.filter { it.href != d.href }; if (local.isEmpty()) { onBack(); return@withContext }
            if (idx >= local.size) idx = 0
        } else withContext(Dispatchers.Main) { Toast.makeText(ctx, "失败", Toast.LENGTH_SHORT).show() }
    }}
    fun nav(d: Int) { if (local.isNotEmpty()) idx = (idx+d+local.size)%local.size }

    if (showDlg) AlertDialog(onDismissRequest = { showDlg = false },
        title = { Text("移到回收站") }, text = { Text(cur.name) },
        confirmButton = { TextButton({ showDlg = false; del() }) { Text("确定", color = DNG) }},
        dismissButton = { TextButton({ showDlg = false }) { Text("取消") }})

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cur.isImage) AsyncImage(model = client.fileUrl(cur.href), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offX, translationY = offY))
            else if (cur.isVideo) VPlayer(client.fileUrl(cur.href), client, { playerRef = it }, { playerRef = null })
        }

        // 手势层
        Box(Modifier.fillMaxWidth().then(if (cur.isVideo) Modifier.fillMaxHeight(0.85f) else Modifier.fillMaxHeight())
            .pointerInput(idx, cur.isVideo, scale) {
                awaitPointerEventScope { while (true) {
                    val dn = awaitFirstDown(false); val sx = dn.position.x; val sy = dn.position.y; val st = System.currentTimeMillis()
                    var ex = sx; var ey = sy; var mv = false; var pts = 1; var iD = 0f; var iS = scale
                    while (true) { val ev = awaitPointerEvent(); val ps = ev.changes.filter { it.pressed }
                        if (ps.isEmpty()) { ev.changes.firstOrNull()?.let { ex=it.position.x; ey=it.position.y }; break }
                        if (ps.size>=2 && cur.isImage) { pts=2
                            val p1=ps[0].position; val p2=ps[1].position
                            val d = kotlin.math.sqrt((p2.x-p1.x)*(p2.x-p1.x)+(p2.y-p1.y)*(p2.y-p1.y))
                            if (iD==0f){iD=d;iS=scale} else if(d>0f){scale=(iS*d/iD).coerceIn(1f,5f)
                                if(scale>1f){val mx=(ps[0].position.x+ps[1].position.x)/2;val my=(ps[0].position.y+ps[1].position.y)/2
                                    val px=(ps[0].previousPosition.x+ps[1].previousPosition.x)/2;val py=(ps[0].previousPosition.y+ps[1].previousPosition.y)/2
                                    offX+=mx-px;offY+=my-py;val mxx=(scale-1f)*size.width/2;val mxy=(scale-1f)*size.height/2
                                    offX=offX.coerceIn(-mxx,mxx);offY=offY.coerceIn(-mxy,mxy)} else{offX=0f;offY=0f}}
                            ps.forEach{it.consume()}
                        } else if(ps.size==1){val c=ps[0];ex=c.position.x;ey=c.position.y
                            if(scale>1.05f&&cur.isImage&&pts==1){val px=c.position.x-c.previousPosition.x;val py=c.position.y-c.previousPosition.y
                                if(kotlin.math.abs(px)>1||kotlin.math.abs(py)>1){mv=true;offX+=px;offY+=py
                                    val mxx=(scale-1f)*size.width/2;val mxy=(scale-1f)*size.height/2
                                    offX=offX.coerceIn(-mxx,mxx);offY=offY.coerceIn(-mxy,mxy);c.consume()}}
                            else{if(kotlin.math.abs(ex-sx)>20||kotlin.math.abs(ey-sy)>20)mv=true}
                        }
                    }
                    if(pts>=2)continue; val dx=ex-sx;val dy=ey-sy;val dt=System.currentTimeMillis()-st
                    if(scale>1.05f&&mv)continue
                    if(kotlin.math.abs(dy)>80&&kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f&&scale<=1.05f){if(dy>0)nav(-1) else nav(1)}
                    else if(!mv&&dt<300){val now=System.currentTimeMillis()
                        if(cur.isImage&&now-lastTap<350){if(scale>1.1f){scale=1f;offX=0f;offY=0f}else scale=2.5f;lastTap=0L}
                        else{lastTap=now;val tx=sx;val tw=size.width
                            scope.launch{delay(360);if(lastTap==now){
                                if(cur.isVideo){playerRef?.let{p->when{tx<tw*0.35f->p.seekTo((p.currentPosition-5000).coerceAtLeast(0))
                                    tx>tw*0.65f->p.seekTo((p.currentPosition+5000).coerceAtMost(p.duration));else->p.playWhenReady=!p.playWhenReady}}}
                                else if(scale<=1.05f){if(tx<tw*0.4f)nav(-1) else if(tx>tw*0.6f)nav(1)}
                            }}
                        }
                    }
                }}
            })

        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x88000000)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("${idx+1}/${local.size}  ${if(cur.size>0)"(${fmtSize(cur.size)}) " else ""}${cur.name}",
                color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Text("✕", color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopStart)
            .padding(6.dp).clip(CircleShape).background(Color(0x55333333)).clickable { onBack() }
            .padding(horizontal = 12.dp, vertical = 6.dp))

        if (scale > 1.05f) Text("${(scale*100).toInt()}%  双击复原", color = Color(0xAAFFFFFF), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)
                .background(Color(0x55000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))

        val al = when(settings.deleteButtonPos){"top-left"->Alignment.TopStart;"top-right"->Alignment.TopEnd
            "bottom-left"->Alignment.BottomStart;else->Alignment.BottomEnd}
        val pd = when(settings.deleteButtonPos){"top-left"->Modifier.padding(start=6.dp,top=48.dp)
            "top-right"->Modifier.padding(end=6.dp,top=48.dp);"bottom-left"->Modifier.padding(start=6.dp,bottom=12.dp)
            else->Modifier.padding(end=6.dp,bottom=12.dp)}
        Text("🗑", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(al).then(pd)
            .clip(CircleShape).background(Color(0x88DD2222)).clickable {
                if (settings.confirmDelete) showDlg = true else del()
            }.padding(12.dp))
    }
}

// ========== 视频播放器 ==========
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VPlayer(url: String, client: WebDavClient, onC: (ExoPlayer) -> Unit, onR: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember(url) { ExoPlayer.Builder(ctx).setMediaSourceFactory(
        DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.getClient()))
    ).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    var pos by remember { mutableStateOf(0L) }; var dur by remember { mutableStateOf(0L) }
    var sk by remember { mutableStateOf(false) }; var sv by remember { mutableStateOf(0f) }
    LaunchedEffect(p) { onC(p); while (true) { if (!sk) { pos=p.currentPosition.coerceAtLeast(0); dur=p.duration.let{if(it<0)0 else it} }; delay(500) } }
    DisposableEffect(url) { onDispose { onR(); p.release() } }
    Column(Modifier.fillMaxSize()) {
        AndroidView({ PlayerView(it).apply { player=p; useController=false } }, { it.player=p }, Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(start=10.dp,end=90.dp,top=2.dp,bottom=3.dp)) {
            Slider(if(sk)sv else if(dur>0)pos.toFloat()/dur.toFloat() else 0f, { sk=true;sv=it }, Modifier.fillMaxWidth().height(18.dp),
                onValueChangeFinished = { p.seekTo((sv*dur).toLong());sk=false },
                colors = SliderDefaults.colors(thumbColor=ACC,activeTrackColor=ACC,inactiveTrackColor=Color(0xFF333333)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(if(sk)(sv*dur).toLong() else pos), color=T2, fontSize=10.sp)
                Text(fmtTime(dur), color=T3, fontSize=10.sp)
            }
        }
    }
}

// ========== 回收站 ==========
@Composable
fun TrashPage(client: WebDavClient, curPath: String, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }; var dlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    fun load() { loading=true; scope.launch(Dispatchers.IO) {
        val r=client.listTrash(curPath); withContext(Dispatchers.Main){items=r;loading=false} }}
    LaunchedEffect(Unit) { load() }
    if (dlg) AlertDialog(onDismissRequest = { dlg=false }, title = { Text("清空回收站") },
        text = { Text("永久删除 ${items.size} 个文件？") },
        confirmButton = { TextButton({ dlg=false; scope.launch(Dispatchers.IO) { client.emptyTrash(curPath)
            withContext(Dispatchers.Main) { Toast.makeText(ctx,"已清空",Toast.LENGTH_SHORT).show(); items=emptyList() } }}) { Text("删除",color=DNG) }},
        dismissButton = { TextButton({ dlg=false }) { Text("取消") }})

    Column(Modifier.fillMaxSize().background(BG)) {
        TopBar { Ico("✕","返回") { onBack() }; Spacer(Modifier.width(6.dp))
            Text("♻ 回收站", color=T1, fontSize=15.sp, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
            Ico("↻","刷新") { load() }; Spacer(Modifier.width(4.dp))
            if (items.isNotEmpty()) Text("清空",color=Color.White,fontSize=12.sp,
                modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(DNG.copy(0.7f)).clickable{dlg=true}.padding(horizontal=10.dp,vertical=4.dp))
        }
        when { loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("加载中...",color=T2)}
            items.isEmpty() -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text("♻",fontSize=40.sp);Text("空",color=T2,modifier=Modifier.padding(top=6.dp))}}
            else -> { Text("  ${items.size}个文件",color=T3,fontSize=10.sp,modifier=Modifier.padding(6.dp))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    itemsIndexed(items){_,e->TRow(e,client,ctx,scope,{items=items.filter{it.id!=e.id}},{items=items.filter{it.id!=e.id}})}
                }
            }
        }
    }
}

@Composable
fun TRow(e: TrashEntry, cl: WebDavClient, ctx: android.content.Context, sc: CoroutineScope, onOk: ()->Unit, onDel: ()->Unit) {
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD).padding(8.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Text(when{e.isImage->"🖼️";e.isVideo->"🎬";else->"📄"},fontSize=22.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) { Text(e.fileName,color=T1,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Row{if(e.size>0)Text(fmtSize(e.size),color=T3,fontSize=9.sp); Text(" ${e.date}",color=T3,fontSize=9.sp,maxLines=1)} }
        if(busy)Text("...",color=ACC) else {
            Text("还原",color=ACC,fontSize=11.sp,modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(ACC.copy(0.12f))
                .clickable{busy=true;sc.launch(Dispatchers.IO){val(ok,msg)=cl.restoreFromTrash(e)
                    withContext(Dispatchers.Main){Toast.makeText(ctx,msg,Toast.LENGTH_SHORT).show();busy=false;if(ok)onOk()}}}.padding(horizontal=8.dp,vertical=4.dp))
            Spacer(Modifier.width(3.dp))
            Text("彻删",color=DNG,fontSize=11.sp,modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(DNG.copy(0.12f))
                .clickable{busy=true;sc.launch(Dispatchers.IO){cl.permanentDeleteTrashEntry(e)
                    withContext(Dispatchers.Main){Toast.makeText(ctx,"已删除",Toast.LENGTH_SHORT).show();busy=false;onDel()}}}.padding(horizontal=8.dp,vertical=4.dp))
        }
    }
}
