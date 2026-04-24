package com.tkhskt.claude.notification.popover

internal enum class DiffOp { EQUAL, DELETE, INSERT }

internal data class DiffLine(
    val op: DiffOp,
    val text: String,
    /** 1-based line number to display next to the row. Uses old for DELETE, new for INSERT/EQUAL. */
    val lineNo: Int,
)

internal sealed interface DiffDisplay {
    data class Line(val line: DiffLine) : DiffDisplay
    data class Jump(val oldSkipped: Int, val newSkipped: Int) : DiffDisplay
}

/**
 * Produces a line-level diff of [a] → [b] using a standard LCS table. O(n·m)
 * time and space; fine for the short snippets we render in the popover.
 * Each returned [DiffLine] carries a display-ready line number.
 */
internal fun lineDiff(a: String, b: String): List<DiffLine> {
    val aLines = a.split("\n")
    val bLines = b.split("\n")
    val n = aLines.size
    val m = bLines.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (aLines[i - 1] == bLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }
    val raw = ArrayDeque<Pair<DiffOp, String>>()
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        when {
            aLines[i - 1] == bLines[j - 1] -> {
                raw.addFirst(DiffOp.EQUAL to aLines[i - 1]); i--; j--
            }
            // Strict `>` so ties pick INSERT during traceback. Because traceback
            // builds the result back-to-front, preferring INSERT here causes
            // DELETE to appear *before* INSERT in the final output — matching
            // git/unified-diff conventions where removals are shown first.
            dp[i - 1][j] > dp[i][j - 1] -> {
                raw.addFirst(DiffOp.DELETE to aLines[i - 1]); i--
            }
            else -> {
                raw.addFirst(DiffOp.INSERT to bLines[j - 1]); j--
            }
        }
    }
    while (i > 0) { raw.addFirst(DiffOp.DELETE to aLines[i - 1]); i-- }
    while (j > 0) { raw.addFirst(DiffOp.INSERT to bLines[j - 1]); j-- }

    var oldNo = 0
    var newNo = 0
    return raw.map { (op, text) ->
        val display = when (op) {
            DiffOp.EQUAL -> { oldNo++; newNo++; newNo }
            DiffOp.DELETE -> { oldNo++; oldNo }
            DiffOp.INSERT -> { newNo++; newNo }
        }
        DiffLine(op, text, display)
    }
}

/**
 * Collapses runs of EQUAL context longer than 2·[contextLines] into a single
 * [DiffDisplay.Jump] marker so the popover can render a unified-diff style view.
 */
internal fun compactDiff(lines: List<DiffLine>, contextLines: Int = 3): List<DiffDisplay> {
    if (lines.isEmpty()) return emptyList()
    val changeIndices = lines.indices.filter { lines[it].op != DiffOp.EQUAL }
    if (changeIndices.isEmpty()) return emptyList()

    // Pick which EQUAL lines to keep: those within [contextLines] of any change.
    val keep = BooleanArray(lines.size)
    for (idx in changeIndices) {
        val lo = (idx - contextLines).coerceAtLeast(0)
        val hi = (idx + contextLines).coerceAtMost(lines.lastIndex)
        for (k in lo..hi) keep[k] = true
    }

    val out = mutableListOf<DiffDisplay>()
    var i = 0
    while (i < lines.size) {
        if (keep[i]) {
            out.add(DiffDisplay.Line(lines[i]))
            i++
        } else {
            var j = i
            var oldSkipped = 0
            var newSkipped = 0
            while (j < lines.size && !keep[j]) {
                when (lines[j].op) {
                    DiffOp.EQUAL -> { oldSkipped++; newSkipped++ }
                    DiffOp.DELETE -> oldSkipped++
                    DiffOp.INSERT -> newSkipped++
                }
                j++
            }
            out.add(DiffDisplay.Jump(oldSkipped, newSkipped))
            i = j
        }
    }
    return out
}
