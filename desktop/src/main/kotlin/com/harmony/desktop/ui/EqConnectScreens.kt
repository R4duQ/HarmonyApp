package com.harmony.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.ClarityPreset
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.WinampEqDesign
import com.harmony.desktop.DesktopApp
import kotlin.math.roundToInt

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalHarmonyColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(22.dp),
    ) { content() }
}

@Composable
private fun HarmonySwitch(checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Accent.Purple, checkedThumbColor = Color.White),
        modifier = Modifier.testTag(tag),
    )
}

private val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

@Composable
fun EqualizerPage(app: DesktopApp) {
    val c = LocalHarmonyColors.current
    val settings by app.settingsFlow.collectAsState()
    val eq = settings.eq
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Equalizer", "Winamp's equalizer and Clarity, the same as on your phone") {
            Text(if (eq.enabled) "On" else "Off", fontSize = 14.sp, color = c.muted)
            HarmonySwitch(eq.enabled, { app.setEq(eq.copy(enabled = it)) }, "eq_on")
        }
        Card(Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WINAMP", fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3CDB5A), fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text("Reset", fontSize = 13.sp, color = Accent.PurpleLight, modifier = Modifier.clickable { app.setEq(eq.copy(winampGainsDb = List(10) { 0f }, winampPreampDb = 0f)) }.padding(6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(250.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    EqSlider("PRE", eq.winampPreampDb, eq.enabled) { app.setEq(eq.copy(enabled = true, winampPreampDb = it)) }
                    Box(Modifier.width(1.dp).fillMaxSize().padding(vertical = 20.dp).background(c.line))
                    BAND_LABELS.forEachIndexed { i, label ->
                        EqSlider(label, eq.winampGainsDb.getOrElse(i) { 0f }, eq.enabled) { v ->
                            app.setEq(eq.copy(enabled = true, winampGainsDb = eq.winampGainsDb.toMutableList().also { it[i] = v }))
                        }
                    }
                }
                Text("PRESETS", fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WinampEqDesign.PRESETS.forEach { (name, values) ->
                        val (preamp, gains) = values
                        val on = eq.winampPreampDb == preamp && eq.winampGainsDb == gains
                        Text(
                            name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (on) Color.White else c.ink,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleDeep)) else Brush.linearGradient(listOf(c.chip, c.chip)))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(role = Role.Button) { app.setEq(eq.copy(enabled = true, winampPreampDb = preamp, winampGainsDb = gains.toList())) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Card(Modifier.padding(horizontal = 32.dp, vertical = 18.dp).fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFFB45CFF), Color(0xFFE2BBFF)))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Clarity", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                        Text("A model of human hearing brings out what's covered up and holds back what pushes forward.", fontSize = 12.sp, color = c.muted)
                    }
                    HarmonySwitch(eq.clarity, { app.setEq(eq.copy(enabled = eq.enabled || it, clarity = it)) }, "clarity_on")
                }
                if (eq.clarity) {
                    val s = eq.claritySettings
                    Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = ClarityPreset.matching(s)
                        ClarityPreset.entries.forEach { p ->
                            val on = p == current
                            Column(
                                Modifier
                                    .width(150.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (on) Brush.linearGradient(listOf(Color(0xFFB45CFF), Color(0xFF6A5CFF))) else Brush.linearGradient(listOf(c.chip, c.chip)))
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(role = Role.Button) { app.setEq(eq.copy(claritySettings = p.settings.copy(boostDb = s.boostDb))) }
                                    .padding(12.dp),
                            ) {
                                Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else c.ink)
                                Text(p.blurb, fontSize = 11.sp, color = if (on) Color.White.copy(alpha = 0.8f) else c.muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    ClarityControl("Recover", "${(s.recover * 100).roundToInt()}%", s.recover, 0f..1f, false, listOf(Accent.Cyan.copy(alpha = 0.6f), Accent.Cyan)) { app.setEq(eq.copy(claritySettings = s.copy(recover = it))) }
                    ClarityControl("Tame", "${(s.tame * 100).roundToInt()}%", s.tame, 0f..1f, false, listOf(Accent.Pink.copy(alpha = 0.6f), Accent.Pink)) { app.setEq(eq.copy(claritySettings = s.copy(tame = it))) }
                    ClarityControl("Bias", lean(s.bias, "Even", "Tame", "Recover"), s.bias, -1f..1f, true, listOf(Accent.Pink, Accent.Cyan)) { app.setEq(eq.copy(claritySettings = s.copy(bias = it))) }
                    ClarityControl("Brighten", lean(s.brighten, "Neutral", "Darker", "Brighter"), s.brighten, -1f..1f, true, listOf(Color(0xFFFF7A2E), Color(0xFFFFE066))) { app.setEq(eq.copy(claritySettings = s.copy(brighten = it))) }
                    ClarityControl("Boost", "%+.1f dB".format(s.boostDb), s.boostDb, -ClaritySettings.MAX_BOOST_DB..ClaritySettings.MAX_BOOST_DB, true, listOf(Color(0xFFB45CFF), Color(0xFFE2BBFF))) { v ->
                        app.setEq(eq.copy(claritySettings = s.copy(boostDb = (v * 2).roundToInt() / 2f)))
                    }
                }
            }
        }
        Card(Modifier.padding(start = 32.dp, end = 32.dp, bottom = 28.dp).fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = Accent.PurpleLight)
                    Text("Music folders", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(10.dp))
                FolderList(app)
            }
        }
    }
}

private fun lean(v: Float, even: String, below: String, above: String): String {
    val pct = (kotlin.math.abs(v) * 100).roundToInt()
    return when {
        pct == 0 -> even
        v < 0 -> "$below $pct%"
        else -> "$above $pct%"
    }
}

@Composable
private fun ClarityControl(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, bipolar: Boolean, colors: List<Color>, onChange: (Float) -> Unit) {
    val c = LocalHarmonyColors.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.width(110.dp))
        HSlider(v, { onChange((it * 100).roundToInt() / 100f) }, Modifier.weight(1f).height(28.dp), range, colors, bipolar)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.last(), modifier = Modifier.width(110.dp).padding(start = 14.dp))
    }
}

/** A Winamp slider: an LED ladder lighting green to red, dB on top, the band underneath. */
@Composable
private fun EqSlider(label: String, value: Float, lit: Boolean, onChange: (Float) -> Unit) {
    val c = LocalHarmonyColors.current
    val change by rememberUpdatedState(onChange)
    val max = WinampEqDesign.MAX_DB
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
        Text(if (value > 0) "+${value.roundToInt()}" else "${value.roundToInt()}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (lit) Color(0xFF3CDB5A) else c.muted)
        Canvas(
            Modifier
                .weight(1f)
                .width(34.dp)
                .padding(vertical = 6.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    fun at(y: Float) = ((1f - (y / size.height)) * 2f - 1f).coerceIn(-1f, 1f) * max
                    detectTapGestures(onDoubleTap = { change(0f) }, onTap = { change((at(it.y) * 2.5f).roundToInt() / 2.5f) })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ch, _ ->
                        ch.consume()
                        val y = ch.position.y
                        change((((1f - y / size.height) * 2f - 1f).coerceIn(-1f, 1f) * max * 2.5f).roundToInt() / 2.5f)
                    }
                },
        ) {
            val cells = 20
            val cellH = size.height / cells
            val cx = size.width / 2
            val w = size.width * 0.42f
            val t = value / max
            val valueY = size.height * (1f - (t + 1f) / 2f)
            val centre = size.height / 2
            for (k in 0 until cells) {
                val top = k * cellH
                val mid = top + cellH / 2
                val level = 1f - mid / size.height * 2f
                val on = lit && ((valueY <= mid && mid <= centre) || (centre <= mid && mid <= valueY))
                val color = when {
                    level > 0.6f -> Color(0xFFFF4D4D)
                    level > 0.2f -> Color(0xFFF2D21B)
                    else -> Color(0xFF3CDB5A)
                }
                drawRoundRect(if (on) color else c.ink.copy(alpha = 0.06f), Offset(cx - w / 2, top + 1.5f), Size(w, cellH - 3f), CornerRadius(2f))
            }
            drawLine(c.muted, Offset(cx - w, centre), Offset(cx + w, centre), strokeWidth = 1.5f)
            drawRoundRect(Color(0xFFD7D9E0), Offset(cx - size.width * 0.38f, valueY - 6.dp.toPx()), Size(size.width * 0.76f, 12.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawRoundRect(Color(0xFF8A8F9C), Offset(cx - size.width * 0.38f, valueY - 6.dp.toPx()), Size(size.width * 0.76f, 12.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(1f))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
    }
}

/** Where a phone pairs: the code, how to use it, and the phones already allowed. */
@Composable
fun ConnectPage(app: DesktopApp) {
    val c = LocalHarmonyColors.current
    val state by app.connect.state.collectAsState()
    val phone by app.player.phone.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    // The code changes once a phone uses it; the host refreshes this state when that happens.
    LaunchedEffect(Unit) { app.connect.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Connect", "Play the music on your phone through this computer")
        Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Card(Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.Purple, Color(0xFF39B9D3)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Text(
                        if (phone != null) "Playing from $phone" else if (state.running) "Ready for your phone" else "Not listening",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(settings.pcName, fontSize = 14.sp, color = c.muted)
                    Text("PAIRING CODE", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(top = 22.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.code.forEach { d ->
                            Box(
                                Modifier
                                    .size(width = 58.dp, height = 74.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(c.surfaceHigh)
                                    .border(1.5.dp, Brush.linearGradient(Accent.Rim), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.testTag("code_digit"))
                            }
                        }
                    }
                    if (state.error != null) Text(state.error!!, fontSize = 12.sp, color = Accent.Pink, modifier = Modifier.padding(top = 14.dp))
                }
            }
            Card(Modifier.weight(1f)) {
                Column {
                    Text("On your phone", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    Step(1, "Connect the phone to the same Wi-Fi as this computer.", Icons.Rounded.Wifi)
                    Step(2, "In Harmony, open the song that's playing and tap the device under the title.", Icons.Rounded.PhoneAndroid)
                    Step(3, "Choose ${settings.pcName} and type the code on the left. You only do this once.", Icons.Rounded.Cast)
                    Text(
                        "If Windows asks whether Harmony may use the network, allow it on private networks.",
                        fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 14.dp),
                    )
                    if (state.addresses.isNotEmpty()) {
                        Text(
                            "This computer: " + state.addresses.joinToString(", ") { "$it:${state.port}" },
                            fontSize = 12.sp, color = c.muted, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Card(Modifier.padding(32.dp).fillMaxWidth()) {
            Column {
                Text("Phones allowed to play here", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                if (state.phones.isEmpty()) {
                    Text("None yet.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                }
                state.phones.forEach { (token, name) ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = Accent.PurpleLight, modifier = Modifier.size(20.dp))
                        Text(name, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (name == phone) Pill("PLAYING", Color(0xFF3CDB5A))
                        Text("Forget", fontSize = 13.sp, color = Accent.Pink, modifier = Modifier.clickable { app.connect.forget(token) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = LocalHarmonyColors.current
    Row(Modifier.padding(top = 14.dp).widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Accent.Purple.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent.PurpleLight)
        }
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.padding(start = 12.dp).size(18.dp))
        Text(text, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 10.dp))
    }
}
