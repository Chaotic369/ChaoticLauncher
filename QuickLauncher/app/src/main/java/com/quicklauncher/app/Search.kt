package com.quicklauncher.app

import android.os.Bundle
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** "Search apps" system shortcut: type a name and open any installed app, whether or not it was added to the popup. */
class AppSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val calls = intent.getBooleanExtra("calls", false)
                setContent { AppTheme { if (calls) CallSearch { finish() } else AppSearch { finish() } } }
    }
}

/** Round icon button used in the call list (call / SMS / WhatsApp / Telegram). */
@Composable
private fun ActBtn(icon: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        FaText(icon, 14.sp, tint)
    }
}

@Composable
private fun ActionIcons(number: String, onCall: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ActBtn("fa-solid fa-message", Color(0xFF5AA9FF)) { Chat.sms(ctx, number); onDone() }
        ActBtn("fa-brands fa-whatsapp", Color(0xFF25D366)) { Chat.whatsapp(ctx, number); onDone() }
        ActBtn("fa-brands fa-telegram", Color(0xFF2AABEE)) { Chat.telegram(ctx, number); onDone() }
        ActBtn("fa-solid fa-phone", Color(0xFF3CC47C), onCall)
    }
}

/** "Call contact" system shortcut: type a name or number and tap a contact to call it straight away. */
@Composable
private fun CallSearch(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { tick++ }
    var q by remember { mutableStateOf("") }
    val granted = remember(tick) { Contacts.granted(ctx) }
    val hits = remember(q, tick) { Contacts.search(ctx, q, 40) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val typed = q.trim()
    val isNumber = typed.length >= 3 && typed.all { it.isDigit() || it in "+-() ." } && typed.count { it.isDigit() } >= 3

    fun callNumber(number: String) {
        val uri = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ctx.startSafe(android.content.Intent(android.content.Intent.ACTION_CALL, uri))
        else {
            ctx.toast("Phone permission not granted, opening dialer")
            ctx.startSafe(android.content.Intent(android.content.Intent.ACTION_DIAL, uri))
        }
        onClose()
    }
    fun call(h: ContactHit) = callNumber(h.number)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 620.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(16.dp)) {
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FaText("fa-solid fa-phone", 13.sp, Dim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Search contacts or type a number", fontSize = 14.sp, color = Dim, maxLines = 1)
                        BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Amber),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { val first = hits.firstOrNull(); if (first != null) call(first) else if (isNumber) callNumber(typed) }))
                    }
                }
                if (isNumber) {
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x0DFFFFFF))
                        .clickable { callNumber(typed) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
                            FaText("fa-solid fa-user", 14.sp, Dim)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(typed, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                            Text("Typed number", fontSize = 11.sp, color = Dim, maxLines = 1)
                        }
                        ActionIcons(typed, { callNumber(typed) }, onClose)
                    }
                }
                if (!granted) {
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x0DFFFFFF))
                        .clickable { perm.launch(android.Manifest.permission.READ_CONTACTS) }.padding(12.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FaText("fa-solid fa-address-book", 12.sp, Amber); Spacer(Modifier.width(8.dp))
                        Text("Allow contacts access to search and call", fontSize = 12.sp, color = Amber, modifier = Modifier.weight(1f))
                    }
                } else if (hits.isEmpty()) {
                    if (!isNumber) Text("No contacts found", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 14.dp, start = 6.dp))
                } else {
                    LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(hits) { h ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { call(h) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
                                    Text((h.name.firstOrNull() ?: '?').uppercaseChar().toString(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(h.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                                    Text(h.number, fontSize = 11.sp, color = Dim, maxLines = 1)
                                }
                                ActionIcons(h.number, { call(h) }, onClose)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSearch(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Apps.list(ctx, true) }
    var q by remember { mutableStateOf("") }
    val shown = remember(q) { if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) || it.pkg.contains(q, true) } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun open(a: AppInfo) {
        val i = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
        if (i == null) ctx.toast("App not installed") else ctx.startSafe(i)
        onClose()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 620.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(16.dp)) {
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FaText("fa-solid fa-magnifying-glass", 13.sp, Dim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Search all apps", fontSize = 14.sp, color = Dim, maxLines = 1)
                        BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Amber),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { shown.firstOrNull()?.let { open(it) } }))
                    }
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(a) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TilePreview(Item("p", "app", a.label, a.pkg, "app"), 38)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                                Text(a.pkg, fontSize = 10.5.sp, color = Dim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
