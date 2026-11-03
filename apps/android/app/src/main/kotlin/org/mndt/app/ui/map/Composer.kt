package org.mndt.app.ui.map

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import org.mndt.app.ui.post.Category
import org.mndt.app.ui.theme.Mosimo

private const val MAX_DETAIL = 140

/** 「状況を伝える」 sheet (Figma 5:410). Location is required here even though the protocol keeps it optional. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Composer(vm: MapViewModel, fix: Location?, address: String?, online: Boolean, onPick: () -> Unit) {
    val point = vm.picked ?: fix?.let { it.latitude to it.longitude }
    val fromFix = vm.picked == null && fix != null
    MapSheet({ vm.composing = false }, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状況を伝える", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Mosimo.textPrimary)
                IconButton({ vm.composing = false }) { Icon(Icons.Filled.Close, "閉じる", tint = Mosimo.textSecondary) }
            }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Mosimo.bgSurface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, null, tint = Mosimo.info)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(when {
                        point == null -> "位置が未設定です"
                        fromFix -> "${address ?: coordsLabel(point.first, point.second)} 付近"
                        else -> coordsLabel(point.first, point.second)
                    }, style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
                    Text(when {
                        point == null -> "地図をタップして投稿する位置を指定してください"
                        fromFix -> listOfNotNull("GPS", fix?.takeIf { it.hasAccuracy() }?.let { "精度 ${accuracyLabel(it.accuracy)}" }, "この位置がそのまま付きます").joinToString("・")
                        else -> "地図で指定した位置・この位置がそのまま付きます"
                    }, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
                }
                TextButton(onPick) { Text(if (point == null) "地図で指定" else "変更", color = Mosimo.info) }
            }
            Label("状況の種類")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c -> MosimoChip(c.label, vm.category == c) { vm.category = c } }
            }
            Label("詳しく（任意）")
            OutlinedTextField(
                vm.detail, { if (it.length <= MAX_DETAIL) vm.detail = it }, Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text("例: 遊歩道が冠水。膝下まで水が来ています") },
                supportingText = {
                    Row {
                        Text("本文のみでも、どのアプリでも読めます", Modifier.weight(1f))
                        Text("${vm.detail.length} / $MAX_DETAIL")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mosimo.primary, cursorColor = Mosimo.primary),
            )
            Label("範囲")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "なし", 50 to "50m", 200 to "200m", 1000 to "1km").forEach { (m, l) -> MosimoChip(l, vm.radius == m) { vm.radius = m } }
            }
            Expiry(vm.hours) { vm.hours = it }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Mosimo.info.copy(alpha = 0.14f)).padding(12.dp)) {
                Icon(Icons.Filled.Sync, null, tint = Mosimo.info, modifier = Modifier.size(20.dp))
                Text(
                    if (online) "オンラインで公開し、近くの端末へも中継して届けます。"
                    else "インターネット圏外のため、近くの端末へ中継して届けます。接続が戻り次第、自動でオンラインにも公開されます。",
                    Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Mosimo.info,
                )
            }
            val ready = vm.category != null && point != null
            Button({ vm.publish(fix, address) }, Modifier.fillMaxWidth().height(52.dp), enabled = ready && !vm.sending,
                colors = ButtonDefaults.buttonColors(containerColor = Mosimo.primary, contentColor = Mosimo.onPrimary)) {
                if (vm.sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Mosimo.onPrimary, strokeWidth = 2.dp)
                    Text("送信中…", Modifier.padding(start = 8.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Text("この場所の状況を投稿する", Modifier.padding(start = 8.dp))
                }
            }
            if (!ready) Text(if (vm.category == null) "状況の種類を選んでください" else "投稿する位置を地図で指定してください",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = Mosimo.warning)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = Mosimo.textTertiary, modifier = Modifier.size(14.dp))
                Text("投稿はあなたの鍵で署名され、なりすましを防ぎます", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
            }
        }
    }
}

/**
 * ModalBottomSheet whose height never depends on its own offset. The default contentWindowInsets pad the top by
 * (status bar − offset), so a sheet tall enough to reach the status bar grows as it rises, which moves the Expanded
 * anchor, which restarts the settle animation: an endless bounce on some screens. Bottom-only insets plus a cap that
 * keeps the sheet below the status bar break that loop; overflow scrolls inside the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(onDismiss: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(), content: @Composable ColumnScope.() -> Unit) =
    ModalBottomSheet(onDismiss, sheetState = sheetState, containerColor = Mosimo.bgSurface,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) }) {
        val top = WindowInsets.safeDrawing
        Column(Modifier.fillMaxWidth().layout { m, c ->
            val p = m.measure(if (c.hasBoundedHeight) c.copy(maxHeight = (c.maxHeight - top.getTop(this)).coerceAtLeast(c.minHeight)) else c)
            layout(p.width, p.height) { p.place(0, 0) }
        }, content = content)
    }

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = Mosimo.textSecondary)

@Composable
private fun Expiry(hours: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) {
            Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp), tint = Mosimo.textSecondary)
            Text("情報の有効期限 ${hours}時間", Modifier.padding(horizontal = 8.dp), color = Mosimo.textPrimary)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Mosimo.textSecondary)
        }
        DropdownMenu(open, { open = false }) {
            listOf(1, 3, 6, 12, 24).forEach { h -> DropdownMenuItem({ Text("${h}時間") }, { onChange(h); open = false }) }
        }
    }
}
