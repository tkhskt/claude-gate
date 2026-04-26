package com.tkhskt.claude.notification.popover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkhskt.claude.notification.permission.PendingRequest
import com.tkhskt.claude.notification.permission.PermissionRequest
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Hardcoded design tokens — the Figma redesign is light-only. We bypass the
// MaterialTheme color scheme so dark-mode preferences don't muddy the brand.
private val WindowBg = Color(0xFFF9F9FB)
private val HeaderBg = Color(0xFFF8FAFC)
private val HeaderBorder = Color(0x80E2E8F0)
private val FooterBg = Color(0x80F3F3F5)
private val Divider = Color(0xFFBFC9C7)
private val CardBg = Color(0xFFF3F3F5)
private val TextPrimary = Color(0xFF191C1C)
private val TextSecondary = Color(0xFF3F4947)
private val TextTertiary = Color(0xFF0F172A)
private val Brand = Color(0xFF009688)
private val BrandSoft = Color(0x33009688)
private val WarningSoft = Color(0xFFFFFBEB)
private val WarningStrong = Color(0xFFB45309)
private val DenyBg = Color(0xFFEEEEF0)
private val DiffBg = Color(0xFF1E1E1E)
private val DiffHeaderBg = Color(0xFF2D2D2D)
private val DiffHeaderBorder = Color(0x0DFFFFFF)
private val DiffText = Color(0xFFD1D5DB)
private val DiffMuted = Color(0xFF9CA3AF)
private val DiffLineNo = Color(0xFF6B7280)
private val DiffAddBg = Color(0x4D134E4A)
private val DiffAddText = Color(0xFF5EEAD4)
private val DiffRemoveBg = Color(0x4D7F1D1D)
private val DiffRemoveText = Color(0xFFFCA5A5)
private val DiffJumpBg = Color(0x33000000)

@Composable
fun PopoverContent(
    holder: PermissionRequestHolder,
    onQuit: () -> Unit = {},
) {
    val pending by holder.pending.collectAsState()
    val selectedId by holder.selectedId.collectAsState()
    val selectedIndex = pending.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val selected = pending.getOrNull(selectedIndex) ?: pending.firstOrNull()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WindowBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0x14000000)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                projectName = projectNameOf(selected?.request),
                sessionShort = sessionShortOf(selected?.request),
                onQuit = onQuit,
            )
            if (pending.isEmpty() || selected == null) {
                EmptyState(modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                RequestView(
                    pending = selected,
                    onAllow = { holder.allow(selected.id) },
                    onDeny = { holder.deny(selected.id) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            if (pending.size > 1) {
                ProgressFooter(
                    count = pending.size,
                    index = selectedIndex,
                    onJump = { idx ->
                        pending.getOrNull(idx)?.let { holder.selectTab(it.id) }
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    projectName: String?,
    sessionShort: String?,
    onQuit: () -> Unit,
) {
    Surface(
        color = HeaderBg,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Claude Code",
                        color = TextTertiary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (!projectName.isNullOrBlank()) {
                        Text(
                            text = "  ·  $projectName",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (!sessionShort.isNullOrBlank()) {
                        Text(
                            text = "  ·  $sessionShort",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onQuit, size = 28.dp) {
                    PowerIcon(modifier = Modifier.size(15.dp), color = TextSecondary)
                }
            }
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
                thickness = 1.dp,
                color = HeaderBorder,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Waiting for Claude…",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun RequestView(
    pending: PendingRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(20.dp))
            HeaderSection(toolName = pending.request.toolName)
            Spacer(Modifier.height(16.dp))
            SelectionContainer { PermissionCard(pending.request) }
        }
        Spacer(Modifier.height(16.dp))
        Actions(onAllow = onAllow, onDeny = onDeny)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeaderSection(toolName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WarningSoft)
                .border(1.dp, WarningStrong.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WarningTriangleIcon(
                modifier = Modifier.size(22.dp),
                fill = WarningStrong,
                mark = Color.White,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Permission Request",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = headerSubtitleFor(toolName),
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PermissionCard(request: PermissionRequest) {
    val secondary = secondaryLineFor(request)
    val codeBlock = codeBlockSpecFor(request)
    val extras = extraFields(request)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Divider),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TOOL",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.55.sp,
                    )
                    ToolBadge(request.toolName)
                }
                if (secondary != null) {
                    Text(
                        text = secondary,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            codeBlock?.let { CodeDiffBlock(it) }
            if (extras.isNotEmpty()) {
                FieldList(JsonObject(extras))
            }
        }
    }
}

@Composable
private fun ToolBadge(toolName: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrandSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = toolName.uppercase(),
            color = Brand,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Actions(onAllow: () -> Unit, onDeny: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onAllow,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand,
                contentColor = Color.White,
            ),
        ) {
            CheckCircleIcon(modifier = Modifier.size(16.dp), color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Allow Change",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(
            onClick = onDeny,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Divider),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = DenyBg,
                contentColor = TextPrimary,
            ),
        ) {
            DenyCircleIcon(modifier = Modifier.size(16.dp), color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Deny",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ProgressFooter(count: Int, index: Int, onJump: (Int) -> Unit) {
    Surface(color = FooterBg, modifier = Modifier.fillMaxWidth()) {
        Box {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = Divider,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(count) { i ->
                            Box(
                                modifier = Modifier
                                    .size(width = if (i == index) 12.dp else 6.dp, height = 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (i == index) Brand else Color(0x333F4947))
                                    .clickable { onJump(i) },
                            )
                        }
                    }
                    Text(
                        text = "REQUEST ${index + 1} OF $count",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChevronButton(
                        enabled = index > 0,
                        onClick = { onJump(index - 1) },
                    ) { ChevronIcon(left = true, color = TextPrimary) }
                    ChevronButton(
                        enabled = index < count - 1,
                        onClick = { onJump(index + 1) },
                    ) { ChevronIcon(left = false, color = TextPrimary) }
                }
            }
        }
    }
}

@Composable
private fun ChevronButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Dim disabled buttons by overlaying a partial background — simpler
            // than threading colors through ChevronIcon for the disabled state.
            content()
            if (!enabled) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
private fun IconButton(
    onClick: () -> Unit,
    size: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------- Code / Diff block -----------------

private sealed interface CodeBlockSpec {
    val title: String
    data class Diff(
        override val title: String,
        val entries: List<DiffDisplay>,
        val adds: Int,
        val removes: Int,
    ) : CodeBlockSpec
    data class Plain(
        override val title: String,
        val text: String,
    ) : CodeBlockSpec
}

@Composable
private fun CodeDiffBlock(spec: CodeBlockSpec) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DiffBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0x1A000000)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar: filename + +/- counters
            Surface(
                color = DiffHeaderBg,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spec.title,
                        color = DiffMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    if (spec is CodeBlockSpec.Diff) {
                        if (spec.adds > 0) {
                            Text(
                                text = "+${spec.adds}",
                                color = DiffAddText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (spec.removes > 0) {
                            Text(
                                text = "-${spec.removes}",
                                color = DiffRemoveText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = DiffHeaderBorder)
            when (spec) {
                is CodeBlockSpec.Diff -> DiffBody(spec.entries)
                is CodeBlockSpec.Plain -> PlainBody(spec.text)
            }
        }
    }
}

@Composable
private fun DiffBody(entries: List<DiffDisplay>) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
    val maxLineNo = entries
        .asSequence()
        .filterIsInstance<DiffDisplay.Line>()
        .maxOfOrNull { it.line.lineNo } ?: 0
    val lineNoWidth = maxLineNo.toString().length

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val containerWidth = maxWidth
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = containerWidth)
                .width(IntrinsicSize.Max)
                .padding(vertical = 8.dp),
        ) {
            for (entry in entries) {
                when (entry) {
                    is DiffDisplay.Line -> {
                        val line = entry.line
                        val (prefix, bg, fg) = when (line.op) {
                            DiffOp.INSERT -> Triple("+", DiffAddBg, DiffAddText)
                            DiffOp.DELETE -> Triple("-", DiffRemoveBg, DiffRemoveText)
                            DiffOp.EQUAL -> Triple(" ", Color.Transparent, DiffText)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 12.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = line.lineNo.toString().padStart(lineNoWidth),
                                color = DiffLineNo,
                                style = textStyle,
                            )
                            Text(text = " $prefix ", color = fg, style = textStyle)
                            Text(text = line.text, color = fg, style = textStyle)
                        }
                    }
                    is DiffDisplay.Jump -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DiffJumpBg)
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = " ⋯ @@ -${entry.oldSkipped} +${entry.newSkipped} @@",
                                color = DiffMuted,
                                style = textStyle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainBody(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = DiffText,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ---------------- Field list (non-code metadata) -----------------

@Composable
private fun FieldList(input: JsonObject) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Divider),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val entries = input.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                FieldRow(key, value)
                if (index < entries.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = Divider.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun FieldRow(key: String, value: JsonElement) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = key.uppercase(),
            color = TextSecondary,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        FieldValue(value)
    }
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

@Composable
private fun FieldValue(value: JsonElement) {
    val style = TextStyle(fontSize = 13.sp, color = TextPrimary)
    when (value) {
        is JsonPrimitive -> {
            val text = value.content
            if ("\n" in text) {
                CodeDiffBlock(CodeBlockSpec.Plain(title = "value", text = text))
            } else {
                Text(text = text, style = style)
            }
        }
        JsonNull -> Text(text = "null", color = TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        is JsonObject, is JsonArray -> CodeDiffBlock(
            CodeBlockSpec.Plain(
                title = "value",
                text = prettyJson.encodeToString(JsonElement.serializer(), value),
            ),
        )
    }
}

// ---------------- Icons (simple Canvas glyphs) -----------------

@Composable
private fun WarningTriangleIcon(modifier: Modifier, fill: Color, mark: Color) = Canvas(modifier) {
    val s = size.minDimension
    val triangle = Path().apply {
        moveTo(s * 0.50f, s * 0.08f)
        lineTo(s * 0.96f, s * 0.86f)
        lineTo(s * 0.04f, s * 0.86f)
        close()
    }
    drawPath(triangle, fill)
    val markStroke = Stroke(width = s * 0.10f, cap = StrokeCap.Round)
    drawLine(mark, Offset(s * 0.50f, s * 0.36f), Offset(s * 0.50f, s * 0.62f), markStroke.width, markStroke.cap)
    drawCircle(mark, s * 0.06f, Offset(s * 0.50f, s * 0.76f))
}

@Composable
private fun ChevronIcon(left: Boolean, color: Color) =
    Canvas(modifier = Modifier.size(width = 6.dp, height = 10.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = h * 0.16f, cap = StrokeCap.Round)
        if (left) {
            drawLine(color, Offset(w * 0.85f, h * 0.10f), Offset(w * 0.15f, h * 0.50f), stroke.width, stroke.cap)
            drawLine(color, Offset(w * 0.15f, h * 0.50f), Offset(w * 0.85f, h * 0.90f), stroke.width, stroke.cap)
        } else {
            drawLine(color, Offset(w * 0.15f, h * 0.10f), Offset(w * 0.85f, h * 0.50f), stroke.width, stroke.cap)
            drawLine(color, Offset(w * 0.85f, h * 0.50f), Offset(w * 0.15f, h * 0.90f), stroke.width, stroke.cap)
        }
    }

@Composable
private fun PowerIcon(modifier: Modifier, color: Color) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round)
    drawArc(
        color = color,
        startAngle = -50f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(s * 0.18f, s * 0.18f),
        size = androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.64f),
        style = stroke,
    )
    drawLine(color, Offset(s * 0.50f, s * 0.10f), Offset(s * 0.50f, s * 0.50f), stroke.width, stroke.cap)
}

@Composable
private fun CheckCircleIcon(modifier: Modifier, color: Color) = Canvas(modifier) {
    val s = size.minDimension
    drawCircle(color, s * 0.50f, Offset(s / 2, s / 2))
    val stroke = Stroke(width = s * 0.15f, cap = StrokeCap.Round)
    drawLine(Brand, Offset(s * 0.30f, s * 0.50f), Offset(s * 0.45f, s * 0.65f), stroke.width, stroke.cap)
    drawLine(Brand, Offset(s * 0.45f, s * 0.65f), Offset(s * 0.72f, s * 0.36f), stroke.width, stroke.cap)
}

@Composable
private fun DenyCircleIcon(modifier: Modifier, color: Color) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round)
    drawCircle(color, s * 0.45f, Offset(s / 2, s / 2), style = stroke)
    drawLine(color, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.70f, s * 0.70f), stroke.width, stroke.cap)
}

// ---------------- Helpers (data extraction) -----------------

private fun projectNameOf(request: PermissionRequest?): String? {
    val cwd = request?.cwd?.trimEnd('/') ?: return null
    if (cwd.isEmpty()) return null
    return cwd.substringAfterLast('/').ifBlank { cwd }
}

private fun sessionShortOf(request: PermissionRequest?): String? {
    val s = request?.sessionId ?: return null
    if (s.isBlank()) return null
    return s.take(8)
}

private fun headerSubtitleFor(toolName: String): String = when (toolName) {
    "Edit", "Write", "NotebookEdit" -> "Claude wants to modify a file in your project."
    "Read" -> "Claude wants to read a file in your project."
    "Bash" -> "Claude wants to run a shell command."
    "PowerShell" -> "Claude wants to run a PowerShell command."
    "WebFetch" -> "Claude wants to fetch content from the web."
    "WebSearch" -> "Claude wants to perform a web search."
    "Monitor" -> "Claude wants to start a background monitor."
    else -> "Claude is requesting permission to use the $toolName tool."
}

private fun secondaryLineFor(request: PermissionRequest): String? {
    fileTargetPath(request.toolName, request.toolInput)?.let { path ->
        return relativeTo(request.cwd, path)
    }
    return when (request.toolName) {
        "Bash", "PowerShell" -> (request.toolInput["command"] as? JsonPrimitive)?.content
        "WebFetch" -> (request.toolInput["url"] as? JsonPrimitive)?.content
        "WebSearch" -> (request.toolInput["query"] as? JsonPrimitive)?.content
        "Agent" -> (request.toolInput["subagent_type"] as? JsonPrimitive)?.content
        else -> null
    }
}

private fun codeBlockSpecFor(request: PermissionRequest): CodeBlockSpec? {
    val title = codeBlockTitle(request)
    editDiff(request.toolName, request.toolInput)?.let { lines ->
        val compact = compactDiff(lines)
        val adds = lines.count { it.op == DiffOp.INSERT }
        val removes = lines.count { it.op == DiffOp.DELETE }
        return CodeBlockSpec.Diff(title = "$title — Diff", entries = compact, adds = adds, removes = removes)
    }
    writeContent(request.toolName, request.toolInput)?.let { lines ->
        val entries = lines.mapIndexed { idx, text ->
            DiffDisplay.Line(DiffLine(DiffOp.INSERT, text, idx + 1))
        }
        return CodeBlockSpec.Diff(title = "$title — Write", entries = entries, adds = lines.size, removes = 0)
    }
    notebookNewSource(request.toolName, request.toolInput)?.let { lines ->
        val entries = lines.mapIndexed { idx, text ->
            DiffDisplay.Line(DiffLine(DiffOp.INSERT, text, idx + 1))
        }
        return CodeBlockSpec.Diff(title = "$title — Cell", entries = entries, adds = lines.size, removes = 0)
    }
    when (request.toolName) {
        "Bash", "PowerShell" -> {
            val cmd = (request.toolInput["command"] as? JsonPrimitive)?.content
            if (!cmd.isNullOrBlank() && "\n" in cmd) {
                return CodeBlockSpec.Plain(title = "command", text = cmd)
            }
        }
        "WebFetch" -> {
            val prompt = (request.toolInput["prompt"] as? JsonPrimitive)?.content
            if (!prompt.isNullOrBlank()) {
                return CodeBlockSpec.Plain(title = "prompt", text = prompt)
            }
        }
    }
    return null
}

private fun codeBlockTitle(request: PermissionRequest): String {
    fileTargetPath(request.toolName, request.toolInput)?.let { path ->
        return path.substringAfterLast('/').ifBlank { path }
    }
    return request.toolName
}

private fun extraFields(request: PermissionRequest): Map<String, JsonElement> {
    val consumed = consumedKeys(request.toolName)
    return request.toolInput.filterNot { (k, _) -> k in consumed }
}

private fun fileTargetPath(toolName: String, input: JsonObject): String? {
    val key = when (toolName) {
        "Edit", "Write", "Read" -> "file_path"
        "NotebookEdit" -> "notebook_path"
        else -> return null
    }
    return (input[key] as? JsonPrimitive)?.content
}

private fun notebookNewSource(toolName: String, input: JsonObject): List<String>? {
    if (toolName != "NotebookEdit") return null
    val source = (input["new_source"] as? JsonPrimitive)?.content ?: return null
    return source.split("\n")
}

/**
 * Keys already rendered above (file path / agent / command / etc.) or inside
 * a specialized view (diff / insert block). They are suppressed from
 * [FieldList] so the popover doesn't show the same information twice.
 */
private fun consumedKeys(toolName: String): Set<String> = when (toolName) {
    "Edit" -> setOf("file_path", "old_string", "new_string")
    "Write" -> setOf("file_path", "content")
    "NotebookEdit" -> setOf("notebook_path", "new_source")
    "Read" -> setOf("file_path")
    "Agent" -> setOf("subagent_type")
    "Bash", "PowerShell" -> setOf("command")
    "WebFetch" -> setOf("url", "prompt")
    "WebSearch" -> setOf("query")
    else -> emptySet()
}

private fun relativeTo(cwd: String, absolutePath: String): String {
    if (cwd.isBlank()) return absolutePath
    val normalizedCwd = cwd.trimEnd('/')
    return when {
        absolutePath == normalizedCwd -> "."
        absolutePath.startsWith("$normalizedCwd/") -> absolutePath.removePrefix("$normalizedCwd/")
        else -> absolutePath
    }
}

private fun editDiff(toolName: String, input: JsonObject): List<DiffLine>? {
    if (toolName != "Edit") return null
    val oldStr = (input["old_string"] as? JsonPrimitive)?.content ?: return null
    val newStr = (input["new_string"] as? JsonPrimitive)?.content ?: return null
    return lineDiff(oldStr, newStr)
}

private fun writeContent(toolName: String, input: JsonObject): List<String>? {
    if (toolName != "Write") return null
    val content = (input["content"] as? JsonPrimitive)?.content ?: return null
    return content.split("\n")
}
