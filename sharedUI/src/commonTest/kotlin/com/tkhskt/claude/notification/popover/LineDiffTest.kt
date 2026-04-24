package com.tkhskt.claude.notification.popover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffTest {

    @Test
    fun identicalStrings_allEqual_withSequentialLineNumbers() {
        val diff = lineDiff("a\nb\nc", "a\nb\nc")
        assertEquals(
            listOf(
                DiffLine(DiffOp.EQUAL, "a", lineNo = 1),
                DiffLine(DiffOp.EQUAL, "b", lineNo = 2),
                DiffLine(DiffOp.EQUAL, "c", lineNo = 3),
            ),
            diff,
        )
    }

    @Test
    fun insertionAtEnd_appendsNewLineNumbered() {
        val diff = lineDiff("a", "a\nb")
        assertEquals(
            listOf(
                DiffLine(DiffOp.EQUAL, "a", lineNo = 1),
                DiffLine(DiffOp.INSERT, "b", lineNo = 2),
            ),
            diff,
        )
    }

    @Test
    fun deletionAtEnd_keepsOldLineNumberForDeletedRow() {
        val diff = lineDiff("a\nb", "a")
        assertEquals(
            listOf(
                DiffLine(DiffOp.EQUAL, "a", lineNo = 1),
                DiffLine(DiffOp.DELETE, "b", lineNo = 2),
            ),
            diff,
        )
    }

    @Test
    fun middleReplace_deleteUsesOldNumber_insertUsesNewNumber() {
        // a=[a,b,c], b=[a,B,c]
        // Old line numbers: a=1, b=2, c=3
        // New line numbers: a=1, B=2, c=3
        val diff = lineDiff("a\nb\nc", "a\nB\nc")
        assertEquals(
            listOf(
                DiffLine(DiffOp.EQUAL, "a", lineNo = 1),
                DiffLine(DiffOp.DELETE, "b", lineNo = 2), // old #2
                DiffLine(DiffOp.INSERT, "B", lineNo = 2), // new #2
                DiffLine(DiffOp.EQUAL, "c", lineNo = 3),
            ),
            diff,
        )
    }

    @Test
    fun multipleDeletesThenInserts_lineNumbersTrackSeparately() {
        // Old: 1:keep, 2:drop1, 3:drop2, 4:keep
        // New: 1:keep, 2:add1, 3:add2, 4:add3, 5:keep
        val diff = lineDiff("keep\ndrop1\ndrop2\nkeep", "keep\nadd1\nadd2\nadd3\nkeep")
        val ops = diff.map { it.op }
        val nums = diff.map { it.lineNo }
        val texts = diff.map { it.text }
        // The LCS finds 'keep' + 'keep' in common; remaining old lines become DELETE, new lines INSERT.
        assertEquals(listOf("keep", "drop1", "drop2", "add1", "add2", "add3", "keep"), texts)
        assertEquals(
            listOf(DiffOp.EQUAL, DiffOp.DELETE, DiffOp.DELETE, DiffOp.INSERT, DiffOp.INSERT, DiffOp.INSERT, DiffOp.EQUAL),
            ops,
        )
        // Line number assignment:
        // EQUAL "keep" → oldNo=1, newNo=1, display=newNo=1
        // DELETE "drop1" → oldNo=2, display=2
        // DELETE "drop2" → oldNo=3, display=3
        // INSERT "add1" → newNo=2, display=2
        // INSERT "add2" → newNo=3, display=3
        // INSERT "add3" → newNo=4, display=4
        // EQUAL "keep" → oldNo=4, newNo=5, display=newNo=5
        assertEquals(listOf(1, 2, 3, 2, 3, 4, 5), nums)
    }

    @Test
    fun leadingInsertion_insertLineNumberIsOne() {
        val diff = lineDiff("a", "x\na")
        assertEquals(
            listOf(
                DiffLine(DiffOp.INSERT, "x", lineNo = 1),
                DiffLine(DiffOp.EQUAL, "a", lineNo = 2),
            ),
            diff,
        )
    }

    @Test
    fun compactDiff_noChanges_returnsEmpty() {
        val diff = lineDiff("a\nb\nc", "a\nb\nc")
        assertEquals(emptyList(), compactDiff(diff))
    }

    @Test
    fun compactDiff_shortDiff_leadingAndTrailingJumpsOnly() {
        // 5 equal + 1 change + 5 equal, contextLines=3 → 3 before + change + 3 after
        val oldStr = "a\nb\nc\nd\ne\nX\nf\ng\nh\ni\nj"
        val newStr = "a\nb\nc\nd\ne\nY\nf\ng\nh\ni\nj"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 3)
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()
        // Change at index 5 (0-based). Keep window [2..8] → indices 2,3,4,5(DEL),6(INS),7,8
        // But the change expands into two entries (DEL X + INS Y). Let me reason again.
        // Raw diff: [a,b,c,d,e,X(DEL),Y(INS),f,g,h,i,j] — 12 entries.
        // Changes at 5 (X DELETE) and 6 (Y INSERT).
        // Keep window for 5: indices [2..8]. For 6: indices [3..9].
        // Union: [2..9]. Kept indices = {2,3,4,5,6,7,8,9}.
        // Not kept: 0,1 (leading), 10,11 (trailing) → 2 jumps.
        assertEquals(2, jumps.size, "expected leading and trailing jumps")
        // Leading jump: 2 old + 2 new skipped
        assertEquals(DiffDisplay.Jump(oldSkipped = 2, newSkipped = 2), jumps[0])
        // Trailing jump: 2 old + 2 new skipped
        assertEquals(DiffDisplay.Jump(oldSkipped = 2, newSkipped = 2), jumps[1])
    }

    @Test
    fun compactDiff_keepsThreeContextLinesBeforeAndAfterChange() {
        // Lots of common, then change, then lots of common
        val common = (1..10).joinToString("\n") { "line$it" }
        val oldStr = "$common\nOLD\n$common"
        val newStr = "$common\nNEW\n$common"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 3)
        val lines = compact.filterIsInstance<DiffDisplay.Line>()
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()

        // Expect: Jump(leading 7) + 3 context + DELETE OLD + INSERT NEW + 3 context + Jump(trailing 7)
        assertEquals(2, jumps.size)
        assertEquals(DiffDisplay.Jump(7, 7), jumps[0])
        assertEquals(DiffDisplay.Jump(7, 7), jumps[1])

        val kept = lines.map { it.line }
        // Kept lines text check: line8..line10 (context before), OLD (delete), NEW (insert), line1..line3 (context after)
        val keptTexts = kept.map { it.text }
        assertEquals(
            listOf("line8", "line9", "line10", "OLD", "NEW", "line1", "line2", "line3"),
            keptTexts,
        )
    }

    @Test
    fun compactDiff_shortGapBetweenChangesIsMerged_noInnerJump() {
        // Two changes with only 4 common lines between them, contextLines=3:
        // With 3 lines of context after change1 and 3 before change2, only 4-3-3 = -2 (overlaps), no jump.
        val oldStr = "A\nb\nc\nd\ne\nf\nG"
        val newStr = "AA\nb\nc\nd\ne\nf\nGG"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 3)
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()
        assertTrue(jumps.isEmpty(), "expected all context kept, got jumps=$jumps")
    }

    @Test
    fun compactDiff_jumpCountsMatchEqualRun() {
        // Change, then 20 equals, then change → one middle jump covering the gap beyond 2·contextLines
        val mid = (1..20).joinToString("\n") { "m$it" }
        val oldStr = "A\n$mid\nZ"
        val newStr = "AA\n$mid\nZZ"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 3)
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()
        // Raw diff: DEL A, INS AA, m1..m20 EQUAL, DEL Z, INS ZZ.
        // Changes at indices 0, 1, 22, 23.
        // Keep windows: 0→[0..3], 1→[0..4], 22→[19..23], 23→[20..23].
        // Union: [0..4] ∪ [19..23].
        // Not kept: [5..18] → 14 middle equals; skipped both in old and new.
        assertEquals(1, jumps.size)
        assertEquals(DiffDisplay.Jump(oldSkipped = 14, newSkipped = 14), jumps[0])
    }

    @Test
    fun compactDiff_preservesLineCountInvariants() {
        // Every source line ends up either in a Line entry or in a Jump's skipped count,
        // separately for old and new. DELETE/INSERT rows always fall inside a keep window
        // (they are change indices), so in practice jumps only elide EQUAL runs and
        // oldSkipped == newSkipped — but that's an invariant of the implementation, not
        // what this test nails down. Here we assert only the conservation law.
        val preEq = (1..10).joinToString("\n") { "p$it" }
        val midEq = (1..10).joinToString("\n") { "m$it" }
        val oldStr = "$preEq\nd1\nd2\nd3\nd4\nd5\n$midEq\nA"
        val newStr = "$preEq\n$midEq\nAA"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 3)
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()
        val totalOldSkipped = jumps.sumOf { it.oldSkipped }
        val totalNewSkipped = jumps.sumOf { it.newSkipped }
        val keptOld = compact.filterIsInstance<DiffDisplay.Line>().count {
            it.line.op == DiffOp.EQUAL || it.line.op == DiffOp.DELETE
        }
        val keptNew = compact.filterIsInstance<DiffDisplay.Line>().count {
            it.line.op == DiffOp.EQUAL || it.line.op == DiffOp.INSERT
        }
        // old = preEq(10) + 5 deletes + midEq(10) + A = 26
        assertEquals(26, keptOld + totalOldSkipped)
        // new = preEq(10) + midEq(10) + AA = 21
        assertEquals(21, keptNew + totalNewSkipped)
    }

    @Test
    fun emptyStrings_produceSingleEqualBlankLine() {
        // Kotlin's String.split("\n") returns [""] for "", so empty→empty has one EQUAL row.
        assertEquals(
            listOf(DiffLine(DiffOp.EQUAL, "", lineNo = 1)),
            lineDiff("", ""),
        )
    }

    @Test
    fun emptyOldString_treatedAsOneBlankLine_followedByInserts() {
        // Old is [""], new is ["x", "y"]. LCS is 0 (neither "x" nor "y" equals ""), so
        // we get DELETE "" + INSERT x + INSERT y — the leading blank is reported as
        // a deleted empty line, and the two real lines as insertions.
        val diff = lineDiff("", "x\ny")
        assertEquals(
            listOf(
                DiffLine(DiffOp.DELETE, "", lineNo = 1),
                DiffLine(DiffOp.INSERT, "x", lineNo = 1),
                DiffLine(DiffOp.INSERT, "y", lineNo = 2),
            ),
            diff,
        )
    }

    @Test
    fun emptyNewString_allLinesDeleted_plusLeadingBlankInsert() {
        val diff = lineDiff("x\ny", "")
        assertEquals(
            listOf(
                DiffLine(DiffOp.DELETE, "x", lineNo = 1),
                DiffLine(DiffOp.DELETE, "y", lineNo = 2),
                DiffLine(DiffOp.INSERT, "", lineNo = 1),
            ),
            diff,
        )
    }

    @Test
    fun compactDiff_contextLinesZero_keepsOnlyChanges() {
        val oldStr = "a\nb\nOLD\nc\nd"
        val newStr = "a\nb\nNEW\nc\nd"
        val diff = lineDiff(oldStr, newStr)
        val compact = compactDiff(diff, contextLines = 0)
        val keptTexts = compact.filterIsInstance<DiffDisplay.Line>().map { it.line.text }
        assertEquals(listOf("OLD", "NEW"), keptTexts)
        val jumps = compact.filterIsInstance<DiffDisplay.Jump>()
        assertEquals(listOf(DiffDisplay.Jump(2, 2), DiffDisplay.Jump(2, 2)), jumps)
    }

    @Test
    fun singleLineReplacement_noContextOnEitherSide() {
        // Inputs of exactly one line each, fully replaced — no EQUAL context exists.
        val diff = lineDiff("old", "new")
        assertEquals(
            listOf(
                DiffLine(DiffOp.DELETE, "old", lineNo = 1),
                DiffLine(DiffOp.INSERT, "new", lineNo = 1),
            ),
            diff,
        )
        val compact = compactDiff(diff, contextLines = 3)
        // No EQUAL lines at all, so compactDiff returns exactly the two change entries, no Jumps.
        assertEquals(
            listOf(
                DiffDisplay.Line(DiffLine(DiffOp.DELETE, "old", 1)),
                DiffDisplay.Line(DiffLine(DiffOp.INSERT, "new", 1)),
            ),
            compact,
        )
    }
}

