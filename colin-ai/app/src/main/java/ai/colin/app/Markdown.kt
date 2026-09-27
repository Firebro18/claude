package ai.colin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val bulletRe = Regex("""^(\s*)([-*+•]|\d+[.)])\s+(.*)$""")
private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")

private fun parse(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            trimmed.isEmpty() -> flush()
            trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += MdBlock.Rule }
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += MdBlock.Bullet(marker, m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> { flush(); out += MdBlock.Quote(trimmed.removePrefix(">").trim()) }
            else -> para.append(if (para.isEmpty()) "" else "\n").append(line)
        }
        i++
    }
    flush()
    return out
}

/** Inline markdown: **bold**, *italic* / _italic_, `code`, [link](url). */
fun inline(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                val end = rest.indexOf("**", 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(rest.substring(2, end), codeBg, linkColor)) }
                i += end + 2
            }
            rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                val end = rest.indexOf('`', 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(rest.substring(1, end)) }
                i += end + 1
            }
            (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && !rest[1].isWhitespace() &&
                rest.indexOf(rest[0], 1) > 1 -> {
                val end = rest.indexOf(rest[0], 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                i += end + 1
            }
            rest.startsWith("[") && Regex("""^\[([^\]]+)]\(([^)]+)\)""").containsMatchIn(rest) -> {
                val m = Regex("""^\[([^\]]+)]\(([^)]+)\)""").find(rest)!!
                withLink(androidx.compose.ui.text.LinkAnnotation.Url(m.groupValues[2])) {
                    withStyle(SpanStyle(color = linkColor)) { append(m.groupValues[1]) }
                }
                i += m.value.length
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.typography.bodyLarge.copy(color = color, lineHeight = 23.sp)
    SelectionContainer {
        Column(modifier) {
            parse(text).forEachIndexed { idx, block ->
                val top = if (idx == 0) 0.dp else 6.dp
                when (block) {
                    is MdBlock.Para -> Text(inline(block.text, codeBg, link), style = base, modifier = Modifier.padding(top = top))
                    is MdBlock.Heading -> Text(
                        inline(block.text, codeBg, link),
                        style = base.copy(fontWeight = FontWeight.Bold, fontSize = if (block.level <= 2) 19.sp else 17.sp),
                        modifier = Modifier.padding(top = top + 4.dp),
                    )
                    is MdBlock.Bullet -> Row(Modifier.padding(top = 2.dp, start = (block.indent * 14).dp)) {
                        Text(block.marker, style = base, modifier = Modifier.width(if (block.marker == "•") 16.dp else 24.dp))
                        Text(inline(block.text, codeBg, link), style = base)
                    }
                    is MdBlock.Code -> Text(
                        block.code,
                        style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                        softWrap = false,
                        modifier = Modifier
                            .padding(top = top)
                            .fillMaxWidth()
                            .background(codeBg, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                    is MdBlock.Quote -> Text(
                        inline(block.text, codeBg, link),
                        style = base.copy(fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.8f)),
                        modifier = Modifier.padding(top = top, start = 8.dp),
                    )
                    MdBlock.Rule -> Text("⎯⎯⎯", style = base.copy(color = color.copy(alpha = 0.4f)), modifier = Modifier.padding(top = top))
                }
            }
        }
    }
}
