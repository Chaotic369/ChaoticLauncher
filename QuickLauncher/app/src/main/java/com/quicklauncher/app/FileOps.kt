package com.quicklauncher.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.IOException

/**
 * Real file operations behind the folder tree's three-dot file menu (Send / Copy / Move / Rename / Delete).
 * Works for Storage Access Framework trees (needs the write grant given when the folder was picked) and for raw
 * /storage/emulated/0 paths (needs All-files access). Everything here blocks: call it from a background thread.
 */
object FileOps {
    class Res(val ok: Boolean, val msg: String)

    private fun docUri(v: String, key: String): Uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(v), key)

    private fun noWrite(v: String) =
        if (Fs.isTreeUri(v)) "No write access to this folder. Remove and re-add the shortcut" else "Allow file access first (Overview > System Permissions)"

    private fun accessProblem(ctx: Context, v: String): Res? =
        if (!Fs.isTreeUri(v) && !Fs.hasFullAccess(ctx)) Res(false, noWrite(v)) else null

    /** Opens the system share sheet for the file. Runs on the main thread (starts an Activity). */
    fun send(ctx: Context, v: String, e: FsEntry): Boolean {
        val uri = Fs.shareUri(ctx, v, e) ?: run { ctx.toast("File not accessible"); return false }
        val i = Intent(Intent.ACTION_SEND).setType(Fs.mimeFor(e.name, e.mime)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(e.name, uri)
        return ctx.startSafe(Intent.createChooser(i, "Send " + e.name), "No app can receive this file")
    }

    private fun exists(ctx: Context, v: String, dirKey: String, name: String): Boolean =
        if (Fs.isTreeUri(v)) Fs.list(ctx, v, dirKey).entries.any { it.name == name } else File(dirKey, name).exists()

    /** "name.ext" -> "name (copy).ext" -> "name (copy 2).ext" ... until it is free in [dirKey]. */
    private fun uniqueName(ctx: Context, v: String, dirKey: String, name: String): String {
        if (!exists(ctx, v, dirKey, name)) return name
        val i = name.lastIndexOf('.')
        val base = if (i > 0) name.substring(0, i) else name
        val ext = if (i > 0) name.substring(i) else ""
        var n = 1
        while (true) {
            val c = base + " (copy" + (if (n > 1) " $n" else "") + ")" + ext
            if (!exists(ctx, v, dirKey, c)) return c
            n++
        }
    }

    private fun label(key: String) = key.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Storage" }

    fun rename(ctx: Context, v: String, e: FsEntry, parentKey: String, newName: String): Res {
        accessProblem(ctx, v)?.let { return it }
        val nn = newName.trim()
        if (nn.isEmpty() || nn.contains('/')) return Res(false, "Enter a valid name")
        if (nn == e.name) return Res(true, "")
        if (exists(ctx, v, parentKey, nn)) return Res(false, "A file with that name exists")
        return try {
            val ok = if (Fs.isTreeUri(v)) DocumentsContract.renameDocument(ctx.contentResolver, docUri(v, e.key), nn) != null
            else File(e.key).renameTo(File(parentKey, nn))
            if (ok) Res(true, "Renamed") else Res(false, "Couldn't rename")
        } catch (x: SecurityException) { Res(false, noWrite(v)) } catch (x: Exception) { Res(false, "Couldn't rename") }
    }

    fun delete(ctx: Context, v: String, e: FsEntry): Res {
        accessProblem(ctx, v)?.let { return it }
        return try {
            val ok = if (Fs.isTreeUri(v)) DocumentsContract.deleteDocument(ctx.contentResolver, docUri(v, e.key)) else File(e.key).delete()
            if (ok) Res(true, "File deleted") else Res(false, "Couldn't delete")
        } catch (x: SecurityException) { Res(false, noWrite(v)) } catch (x: Exception) { Res(false, "Couldn't delete") }
    }

    fun copy(ctx: Context, v: String, e: FsEntry, destKey: String): Res {
        accessProblem(ctx, v)?.let { return it }
        val name = uniqueName(ctx, v, destKey, e.name)
        return try {
            if (Fs.isTreeUri(v)) {
                val cr = ctx.contentResolver
                val out = DocumentsContract.createDocument(cr, docUri(v, destKey), Fs.mimeFor(e.name, e.mime), name)
                    ?: return Res(false, "Couldn't copy")
                try {
                    val i = cr.openInputStream(docUri(v, e.key)) ?: throw IOException("no input")
                    val o = cr.openOutputStream(out) ?: throw IOException("no output")
                    i.use { a -> o.use { b -> a.copyTo(b) } }
                } catch (x: Exception) {
                    runCatching { DocumentsContract.deleteDocument(cr, out) }
                    throw x
                }
            } else {
                File(e.key).copyTo(File(destKey, name), overwrite = false)
            }
            Res(true, "Copied to " + label(destKey))
        } catch (x: SecurityException) { Res(false, noWrite(v)) } catch (x: Exception) { Res(false, "Couldn't copy") }
    }

    fun move(ctx: Context, v: String, e: FsEntry, parentKey: String, destKey: String): Res {
        accessProblem(ctx, v)?.let { return it }
        if (destKey == parentKey) return Res(false, "Already in this folder")
        if (exists(ctx, v, destKey, e.name)) return Res(false, "A file with that name exists there")
        val done = Res(true, "Moved to " + label(destKey))
        try {
            if (Fs.isTreeUri(v)) {
                val moved = runCatching { DocumentsContract.moveDocument(ctx.contentResolver, docUri(v, e.key), docUri(v, parentKey), docUri(v, destKey)) }.getOrNull()
                if (moved != null) return done
            } else if (File(e.key).renameTo(File(destKey, e.name))) return done
        } catch (x: SecurityException) { return Res(false, noWrite(v)) } catch (x: Exception) { /* fall through to copy + delete */ }
        // providers / volumes that can't move in place: copy then remove the original
        val c = copy(ctx, v, e, destKey)
        if (!c.ok) return c
        val d = delete(ctx, v, e)
        return if (d.ok) done else Res(false, "Copied, but couldn't remove the original")
    }

    /** Parent folder key inside the shortcut, or null at the shortcut's own root. */
    fun parentOf(v: String, root: String, key: String): String? {
        if (key == root) return null
        return if (Fs.isTreeUri(v)) key.substringBeforeLast('/', root).let { if (it.length < root.length) root else it }
        else (File(key).parent ?: root).let { if (it.length < root.length) root else it }
    }

    /** "Internal storage/Download › Sub › Deeper" style breadcrumb for [key]. */
    fun crumb(v: String, root: String, key: String): String {
        val base = Fs.displayPath(v)
        if (key == root) return base
        val rel = key.removePrefix(root).trim('/', ':')
        return if (rel.isEmpty()) base else base + " › " + rel.replace("/", " › ")
    }
}

/**
 * Dialogs for the file menu: Rename, and the "Copy to / Move to" folder picker. Delete is confirmed inside the popup
 * and Send opens the share sheet directly, so neither needs this screen.
 */
class FileOpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val act = intent.getIntExtra("act", -1)
        val v = intent.getStringExtra("v").orEmpty()
        val key = intent.getStringExtra("key").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        val parent = intent.getStringExtra("parent").orEmpty()
        if (act !in FileAct.COPY..FileAct.RENAME || v.isEmpty() || key.isEmpty()) { finish(); return }
        val e = FsEntry(name, false, key, intent.getStringExtra("mime"))
        val close = { Handler(Looper.getMainLooper()).post { finish() }; Unit }
        setContent {
            AppTheme {
                if (act == FileAct.RENAME) RenameDialog(v, e, parent.ifEmpty { Fs.rootKey(v) }, close)
                else PickDialog(v, e, parent.ifEmpty { Fs.rootKey(v) }, act == FileAct.MOVE, close)
            }
        }
    }
}

private val DialogBg = Color(0xFA0C0C0C)

@Composable
private fun DialogTitle(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        FaText(icon, 15.sp, Acc); Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RenameDialog(v: String, e: FsEntry, parentKey: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dot = e.name.lastIndexOf('.')
    var tf by remember { mutableStateOf(TextFieldValue(e.name, TextRange(0, if (dot > 0) dot else e.name.length))) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun go() {
        if (busy) return
        val nn = tf.text.trim()
        if (nn.isEmpty() || nn.contains('/')) { ctx.toast("Enter a valid name"); return }
        if (nn == e.name) { onClose(); return }
        busy = true
        Thread {
            val r = FileOps.rename(ctx, v, e, parentKey, nn)
            if (r.msg.isNotEmpty()) ctx.toast(r.msg)
            if (r.ok) onClose() else busy = false
        }.start()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp), shape = RoundedCornerShape(26.dp), color = DialogBg) {
            Column(Modifier.padding(18.dp)) {
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                DialogTitle("fa-solid fa-pen", "Rename")
                Text("Name", fontSize = 11.sp, color = Dim, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(tf, { tf = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SBtn("Cancel", onClick = onClose)
                    Spacer(Modifier.weight(1f))
                    SBtn("Rename", color = Color.White, bg = Acc, onClick = { go() })
                }
            }
        }
    }
}

/** Folder picker (inside the shortcut's own folder) for Copy / Move. */
@Composable
private fun PickDialog(v: String, e: FsEntry, parentKey: String, move: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember { Fs.rootKey(v) }
    var cur by remember { mutableStateOf(parentKey) }
    var dirs by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var state by remember { mutableStateOf(0) }     // 0 loading, 1 listed, 2 no access
    var busy by remember { mutableStateOf(false) }
    val token = remember { intArrayOf(0) }

    LaunchedEffect(cur) {
        val t = ++token[0]
        state = 0
        Fs.listAsync(ctx, v, cur) { r -> if (t == token[0]) { dirs = r.entries.filter { it.dir }; state = if (r.denied) 2 else 1 } }
    }

    val up = FileOps.parentOf(v, root, cur)

    fun commit() {
        if (busy) return
        if (move && cur == parentKey) { ctx.toast("Already in this folder"); return }
        busy = true
        val dest = cur
        Thread {
            val r = if (move) FileOps.move(ctx, v, e, parentKey, dest) else FileOps.copy(ctx, v, e, dest)
            ctx.toast(r.msg)
            if (r.ok) onClose() else busy = false
        }.start()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 560.dp), shape = RoundedCornerShape(26.dp), color = DialogBg) {
            Column(Modifier.padding(18.dp)) {
                DialogTitle("fa-solid fa-folder-tree", if (move) "Move to" else "Copy to")
                Text(e.name, fontSize = 11.5.sp, color = Dim, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
                Text(FileOps.crumb(v, root, cur), fontSize = 11.5.sp, color = Dim, maxLines = 2, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    if (up != null) item {
                        PickRow("fa-solid fa-turn-up", "..", "Up one level", Color(0xFF3A3552), Ink) { cur = up }
                    }
                    items(dirs, key = { it.key }) { d ->
                        PickRow("fa-solid fa-folder", d.name, "Folder", Color(0xFFF0A93B), Color(0xFF111111)) { cur = d.key }
                    }
                    if (state == 0) item { Text("Loading…", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(10.dp)) }
                    else if (state == 2) item { Text("No access to this folder", fontSize = 12.sp, color = Acc, modifier = Modifier.padding(10.dp)) }
                    else if (dirs.isEmpty()) item { Text("No subfolders", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(10.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SBtn("Cancel", onClick = onClose)
                    Spacer(Modifier.weight(1f))
                    SBtn(if (move) "Move here" else "Copy here", color = Color.White, bg = Acc, onClick = { commit() })
                }
            }
        }
    }
}

@Composable
private fun PickRow(icon: String, title: String, sub: String, tile: Color, glyph: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) { FaText(icon, 14.sp, glyph) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
            Text(sub, fontSize = 10.5.sp, color = Dim, maxLines = 1)
        }
    }
}
