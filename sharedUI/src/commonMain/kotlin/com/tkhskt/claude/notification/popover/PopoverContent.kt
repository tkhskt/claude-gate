package com.tkhskt.claude.notification.popover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider // used by FieldList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkhskt.claude.notification.permission.PendingRequest
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun PopoverContent(
    holder: PermissionRequestHolder,
    onQuit: () -> Unit = {},
) {
    val pending by holder.pending.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Claude Code",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onQuit) {
                    Text(
                        text = "Quit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (pending != null) {
                RequestView(
                    pending = pending!!,
                    onAllow = holder::allow,
                    onDeny = holder::deny,
                )
            } else {
                EmptyState()
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Waiting for Claude…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RequestView(
    pending: PendingRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Permission requested",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            // Selectable area: tool metadata + input view. The "Permission
            // requested" header, "Input" label, and action buttons stay outside
            // so the user can't copy the app's own chrome text.
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LabeledLine("Tool", pending.request.toolName)
                    if (pending.request.cwd.isNotBlank()) {
                        LabeledLine("cwd", pending.request.cwd)
                    }
                    agentName(pending.request.toolName, pending.request.toolInput)?.let { name ->
                        LabeledLine("Agent", name)
                    }
                    fileTargetPath(pending.request.toolName, pending.request.toolInput)?.let { path ->
                        LabeledLine("File", relativeTo(pending.request.cwd, path))
                    }
                    Spacer(Modifier.height(12.dp))
                    ToolInputBlock(pending.request.toolName, pending.request.toolInput)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
            ) { Text("Deny") }
            Button(
                onClick = onAllow,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Allow") }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

@Composable
private fun ToolInputBlock(toolName: String, input: JsonObject) {
    val editDiff = editDiff(toolName, input)
    val writeContent = writeContent(toolName, input)
    val notebookSource = notebookNewSource(toolName, input)
    val consumed = consumedKeys(toolName)
    val remaining = input.filterNot { (k, _) -> k in consumed }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        editDiff?.let { DiffBlock(compactDiff(it)) }
        writeContent?.let { lines ->
            DiffBlock(
                lines.mapIndexed { idx, text ->
                    DiffDisplay.Line(DiffLine(DiffOp.INSERT, text, idx + 1))
                },
            )
        }
        notebookSource?.let { lines ->
            DiffBlock(
                lines.mapIndexed { idx, text ->
                    DiffDisplay.Line(DiffLine(DiffOp.INSERT, text, idx + 1))
                },
            )
        }
        if (remaining.isNotEmpty()) {
            FieldList(JsonObject(remaining))
        }
    }
}

@Composable
private fun FieldList(input: JsonObject) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val entries = input.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                FieldRow(key, value)
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(key: String, value: JsonElement) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = key.uppercase(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FieldValue(value)
    }
}

@Composable
private fun FieldValue(value: JsonElement) {
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    when (value) {
        is JsonPrimitive -> {
            val text = value.content
            if ("\n" in text) {
                CodeBlock(text)
            } else {
                Text(text = text, style = valueStyle)
            }
        }
        JsonNull -> Text(
            text = "null",
            style = valueStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        is JsonObject, is JsonArray -> CodeBlock(
            prettyJson.encodeToString(JsonElement.serializer(), value),
        )
    }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = text,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DiffBlock(entries: List<DiffDisplay>) {
    val addColor = Color(0x3322C55E)
    val removeColor = Color(0x33EF4444)
    val jumpColor = Color(0x22808080)
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val lineNoStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Pad single-digit line numbers to align with multi-digit ones, but only
    // if the diff actually contains multi-digit line numbers (≥10 lines).
    val maxLineNo = entries
        .asSequence()
        .filterIsInstance<DiffDisplay.Line>()
        .maxOfOrNull { it.line.lineNo }
        ?: 0
    val lineNoWidth = maxLineNo.toString().length

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            for (entry in entries) {
                when (entry) {
                    is DiffDisplay.Line -> {
                        val line = entry.line
                        val (prefix, bg) = when (line.op) {
                            DiffOp.INSERT -> "+" to addColor
                            DiffOp.DELETE -> "−" to removeColor
                            DiffOp.EQUAL -> " " to Color.Transparent
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = line.lineNo.toString().padStart(lineNoWidth),
                                style = lineNoStyle,
                            )
                            Text(text = " $prefix ", style = textStyle)
                            Text(text = line.text, style = textStyle)
                        }
                    }
                    is DiffDisplay.Jump -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(jumpColor)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = " ⋯ @@ −${entry.oldSkipped} +${entry.newSkipped} @@",
                                style = lineNoStyle,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fileTargetPath(toolName: String, input: JsonObject): String? {
    val key = when (toolName) {
        "Edit", "Write", "Read" -> "file_path"
        "NotebookEdit" -> "notebook_path"
        else -> return null
    }
    return (input[key] as? JsonPrimitive)?.content
}

private fun agentName(toolName: String, input: JsonObject): String? {
    if (toolName != "Agent") return null
    return (input["subagent_type"] as? JsonPrimitive)?.content
}

private fun notebookNewSource(toolName: String, input: JsonObject): List<String>? {
    if (toolName != "NotebookEdit") return null
    val source = (input["new_source"] as? JsonPrimitive)?.content ?: return null
    return source.split("\n")
}

/**
 * Keys already rendered above (File / Agent) or inside a specialized view
 * (diff / insert block). They are suppressed from [FieldList] so the popover
 * doesn't show the same information twice.
 */
private fun consumedKeys(toolName: String): Set<String> = when (toolName) {
    "Edit" -> setOf("file_path", "old_string", "new_string")
    "Write" -> setOf("file_path", "content")
    "NotebookEdit" -> setOf("notebook_path", "new_source")
    "Read" -> setOf("file_path")
    "Agent" -> setOf("subagent_type")
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

