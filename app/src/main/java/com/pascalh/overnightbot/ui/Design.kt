package com.pascalh.overnightbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pascalh.overnightbot.R

// ---------------------------------------------------------------------------------------------
// Palette. Two looks share one screen:
//  * "Ink": dark angled cards with a green accent. Used by the whole app.
//  * "Alpaca": white card with yellow accents. Only for the Alpaca API connection, so the user
//    sees at a glance that the keys belong to Alpaca and not to this app.
// ---------------------------------------------------------------------------------------------

val Paper = Color(0xFFF6F5F2)
val Ink = Color(0xFF211F1D)
val InkRaised = Color(0xFF33302C)
val InkLine = Color(0xFF45413B)
val OnInk = Color(0xFFFFFFFF)
val OnInkMuted = Color(0xFFA4A097)
val Gold = Color(0xFFD9B64A)
val Green = Color(0xFF5DE39E)
val Stone = Color(0xFFE4E1DA)
val PaperMuted = Color(0xFF6E6A63)
val Good = Color(0xFF5DE39E)
val Bad = Color(0xFFFF7A6B)
val BadOnPaper = Color(0xFFC6382B)
val Muted = OnInkMuted

val AlpacaYellow = Color(0xFFFFDE32)
val AlpacaMark = Color(0xFFFCD62B)
val AlpacaNotice = Color(0xFFFFF8D1)
val AlpacaNoticeText = Color(0xFF655A23)
val AlpacaOlive = Color(0xFF6E6630)
val AlpacaField = Color(0xFFFAFBF7)
val AlpacaFieldLine = Color(0xFFB6BDAC)
val AlpacaStone = Color(0xFFEEEEE6)
val AlpacaText = Color(0xFF24271F)
val AlpacaMuted = Color(0xFF5B5F57)

val MoonYellow = Color(0xFFF4B342)
val MoonNavy = Color(0xFF0D1328)

val AppColors = lightColorScheme(
    primary = Ink,
    onPrimary = OnInk,
    secondary = Green,
    onSecondary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Stone,
    onSurfaceVariant = PaperMuted,
    surfaceContainerHigh = Color.White,
    error = BadOnPaper,
)

val Mono = FontFamily.Monospace

/** Parallelogram: the top edge is pushed [skew] to the right. This is the "angled surface" of the design. */
class SkewShape(private val skew: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { skew.toPx() }.coerceAtMost(size.width / 4)
        val p = Path().apply {
            moveTo(s, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width - s, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(p)
    }
}

val CardShape = SkewShape(12.dp)
val ButtonShape = SkewShape(12.dp)
val ChipShape = SkewShape(8.dp)

// ----------------------------------------------------------------------------- page structure

/** Kicker line with the logo, big title, optional element on the right. */
@Composable
fun AppHeader(kicker: String, title: String, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(kicker, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Ink)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f), lineHeight = 40.sp)
            trailing()
        }
    }
}

/** Small angled badge, e.g. PAPER / LIVE in the header. */
@Composable
fun Badge(text: String, fill: Color, content: Color = Ink) {
    Box(
        Modifier.clip(ChipShape).background(fill).border(1.dp, Ink, ChipShape).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, fontFamily = Mono, fontSize = 11.sp, color = content) }
}

/** "01 / ALPACA ACCOUNT" style label. */
@Composable
fun Kicker(text: String, color: Color = Gold) {
    Text(text.uppercase(), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.5.sp, color = color)
}

/** Dark angled card. Everything inside defaults to white text. */
@Composable
fun InkCard(
    kicker: String = "",
    title: String = "",
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp).clip(CardShape).background(Ink)) {
        CompositionLocalProvider(LocalContentColor provides OnInk) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 24.dp)) {
                if (kicker.isNotEmpty()) {
                    Kicker(kicker)
                    Spacer(Modifier.height(14.dp))
                }
                if (title.isNotEmpty()) {
                    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                    Spacer(Modifier.height(14.dp))
                }
                content()
            }
        }
    }
}

/** White angled card with a hairline border. Used for the Alpaca connection and phone notices. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp)
            .clip(CardShape).background(Color.White).border(1.dp, Color(0xFFE3E2DA), CardShape),
    ) {
        CompositionLocalProvider(LocalContentColor provides AlpacaText) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 24.dp)) { content() }
        }
    }
}

@Composable
fun InkDivider(color: Color = InkLine) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(color))
}

/** Label left, value right. */
@Composable
fun Line(label: String, value: String, color: Color = Color.Unspecified, labelColor: Color = OnInkMuted) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = labelColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.End)
    }
}

/** Small explanatory text. Colour follows the card unless given. */
@Composable
fun Hint(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val c = if (color != Color.Unspecified) color else LocalContentColor.current.copy(alpha = 0.62f)
    Text(text, color = c, fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier)
}

// ----------------------------------------------------------------------------------- controls

/** Angled button. Primary actions get the accent fill, secondary ones stone. */
@Composable
fun SkewButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Green,
    content: Color = Ink,
    enabled: Boolean = true,
    arrow: Boolean = false,
    height: Dp = 50.dp,
) {
    Box(
        modifier.height(height).clip(ButtonShape)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .border(1.dp, Ink.copy(alpha = if (fill == Ink) 0f else 0.85f), ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (arrow) "$text  ↗" else text,
            color = if (enabled) content else content.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
        )
    }
}

/** Plain text action (e.g. "Delete keys"). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontWeight = FontWeight.Medium, fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/** One option of a segmented row; place several inside a Row. */
@Composable
fun RowScope.Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedFill: Color,
    selectedText: Color,
    idleFill: Color,
    idleText: Color,
) {
    Box(
        Modifier.weight(1f).heightIn(min = 40.dp).clip(ChipShape)
            .background(if (selected) selectedFill else idleFill)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) selectedText else idleText, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
fun InkSegments(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            Segment(label, sel, { onPick(i) }, Green, Ink, InkRaised, OnInkMuted)
        }
    }
}

@Composable
fun AlpacaSegments(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            Segment(label, sel, { onPick(i) }, AlpacaYellow, AlpacaText, AlpacaStone, AlpacaMuted)
        }
    }
}

/** Text field with the label above and a square box, in the dark or the Alpaca look. */
@Composable
fun BoxField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    alpaca: Boolean = false,
    placeholder: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    suffix: String = "",
    big: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fill = if (alpaca) AlpacaField else InkRaised
    val line = if (alpaca) AlpacaFieldLine else InkLine
    val text = if (alpaca) AlpacaText else OnInk
    val hint = if (alpaca) Color(0xFF8A8E84) else OnInkMuted
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 13.sp, color = if (alpaca) AlpacaMuted else OnInkMuted, fontFamily = if (big) Mono else null)
            Spacer(Modifier.height(7.dp))
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).background(fill).border(1.dp, line).padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = hint, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = text, fontSize = if (big) 24.sp else 17.sp),
                    cursorBrush = SolidColor(if (alpaca) AlpacaText else Green),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix.isNotEmpty()) Text(suffix, color = hint, fontSize = 15.sp)
        }
    }
}

/** Angled on/off switch with a square thumb. */
@Composable
fun SkewToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val track = when {
        !enabled -> InkLine
        checked -> Green
        else -> InkRaised
    }
    Box(
        Modifier.size(width = 54.dp, height = 30.dp).clip(ChipShape).background(track)
            .border(1.dp, if (checked) Green else InkLine, ChipShape)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(width = 18.dp, height = 20.dp).background(if (checked) Ink else OnInkMuted))
    }
}

/** PLUG/RIOT split bar: green part = PLUG, stone part = RIOT, a handle in between. 5% steps. */
@Composable
fun SplitBar(plugPct: Int, onChange: (Int) -> Unit, min: Int = 5, max: Int = 95) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val pick: (Float) -> Unit = { x ->
            val raw = (x / widthPx * 100f)
            onChange(((Math.round(raw / 5f) * 5)).coerceIn(min, max))
        }
        val latest = rememberUpdatedState(pick)
        Canvas(
            Modifier.fillMaxWidth().height(32.dp)
                .pointerInput(Unit) { detectTapGestures { latest.value(it.x) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> latest.value(change.position.x) } },
        ) {
            val split = size.width * plugPct / 100f
            val barH = 8.dp.toPx()
            val y = (size.height - barH) / 2
            drawRect(Green, Offset(0f, y), Size(split, barH))
            drawRect(InkLine, Offset(split, y), Size(size.width - split, barH))
            val hw = 4.dp.toPx()
            drawRect(OnInk, Offset(split - hw / 2, 0f), Size(hw, size.height))
        }
    }
}

// -------------------------------------------------------------------------------- brand marks

/** Yellow round mark with a simple alpaca head: tells the user these keys are Alpaca's. */
@Composable
fun AlpacaBadge(size: Dp = 40.dp) {
    Canvas(Modifier.size(size).clip(CircleShape)) {
        drawCircle(AlpacaMark)
        val k = this.size.width / 40f
        val p = Path().apply {
            moveTo(15f * k, 42f * k)
            lineTo(15f * k, 18f * k)
            cubicTo(15f * k, 14f * k, 16f * k, 12f * k, 18f * k, 11f * k)
            lineTo(18f * k, 6f * k)
            lineTo(20.5f * k, 9.5f * k)
            lineTo(23f * k, 6f * k)
            lineTo(23f * k, 11f * k)
            cubicTo(25f * k, 12f * k, 26.5f * k, 14f * k, 26.5f * k, 16f * k)
            lineTo(26.5f * k, 18f * k)
            cubicTo(26.5f * k, 19.2f * k, 25.6f * k, 20f * k, 24.5f * k, 20f * k)
            lineTo(22f * k, 20f * k)
            lineTo(22f * k, 42f * k)
            close()
        }
        drawPath(p, Color.White)
    }
}

// --------------------------------------------------------------------------------- navigation

@Composable
fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("Bots", "Log", "Settings").forEachIndexed { i, label ->
            val on = tab == i
            Column(Modifier.weight(1f).clickable { onTab(i) }.padding(vertical = 4.dp)) {
                Box(Modifier.size(width = 20.dp, height = 2.dp).background(if (on) Green else InkLine))
                Spacer(Modifier.height(9.dp))
                Text(label, color = if (on) Green else OnInkMuted, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
