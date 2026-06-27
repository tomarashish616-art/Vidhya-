package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class ListItem(val ordered: Boolean, val number: String, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trim().startsWith("```")) {
            val lang = line.trim().substring(3).trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        // Table block
        if (line.trim().startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().startsWith("|") && lines[i + 1].contains("-")) {
            val headers = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val rows = mutableListOf<List<String>>()
            i += 2 // Skip headers and separator line
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                val rowCells = lines[i].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (rowCells.isNotEmpty()) {
                    rows.add(rowCells)
                }
                i++
            }
            blocks.add(MarkdownBlock.TableBlock(headers, rows))
            continue
        }

        // Header
        if (line.trim().startsWith("#")) {
            val level = line.takeWhile { it == '#' }.length
            val headerText = line.drop(level).trim()
            if (level in 1..6 && headerText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Header(level, headerText))
                i++
                continue
            }
        }

        // List item (Unordered)
        if (line.trim().startsWith("- ") || line.trim().startsWith("* ") || line.trim().startsWith("• ")) {
            val itemText = line.trim().substring(2).trim()
            blocks.add(MarkdownBlock.ListItem(ordered = false, number = "", text = itemText))
            i++
            continue
        }

        // List item (Ordered)
        val orderedMatch = "^(\\d+)\\.\\s+(.*)$".toRegex().find(line.trim())
        if (orderedMatch != null) {
            val num = orderedMatch.groupValues[1]
            val itemText = orderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.ListItem(ordered = true, number = num, text = itemText))
            i++
            continue
        }

        // Paragraph
        if (line.isNotBlank()) {
            val paragraphText = StringBuilder(line)
            i++
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trim().startsWith("#") &&
                !lines[i].trim().startsWith("```") &&
                !lines[i].trim().startsWith("- ") &&
                !lines[i].trim().startsWith("* ") &&
                !lines[i].trim().startsWith("|") &&
                !"^(\\d+)\\.\\s+(.*)$".toRegex().matches(lines[i].trim())
            ) {
                paragraphText.append("\n").append(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(paragraphText.toString()))
            continue
        }

        i++
    }
    return blocks
}

@Composable
fun renderInlineMarkdown(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0
    while (index < text.length) {
        val boldStart = text.indexOf("**", index)
        val codeStart = text.indexOf("`", index)

        val nextSpecial = when {
            boldStart == -1 -> codeStart
            codeStart == -1 -> boldStart
            else -> minOf(boldStart, codeStart)
        }

        if (nextSpecial == -1) {
            builder.append(text.substring(index))
            break
        }

        builder.append(text.substring(index, nextSpecial))

        if (nextSpecial == boldStart) {
            val boldEnd = text.indexOf("**", boldStart + 2)
            if (boldEnd != -1) {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(boldStart + 2, boldEnd))
                }
                index = boldEnd + 2
            } else {
                builder.append("**")
                index = boldStart + 2
            }
        } else {
            val codeEnd = text.indexOf("`", codeStart + 1)
            if (codeEnd != -1) {
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    append(text.substring(codeStart + 1, codeEnd))
                }
                index = codeEnd + 1
            } else {
                builder.append("`")
                index = codeStart + 1
            }
        }
    }
    return builder.toAnnotatedString()
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    bodyTextStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val blocks = parseMarkdown(text)
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    val fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.sp
                    }
                    val fontWeight = FontWeight.Bold
                    Text(
                        text = renderInlineMarkdown(block.text),
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = bodyTextStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = if (block.ordered) "${block.number}. " else "• ",
                            style = bodyTextStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = renderInlineMarkdown(block.text),
                            style = bodyTextStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        // Header Bar of Code Block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = block.language.ifEmpty { "code" }.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(block.code))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.width(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Code",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // Code contents
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = block.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is MarkdownBlock.TableBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Column {
                            // Headers
                            Row(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(vertical = 8.dp)
                            ) {
                                block.headers.forEach { header ->
                                    Text(
                                        text = header,
                                        fontWeight = FontWeight.Bold,
                                        style = bodyTextStyle,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Divider()
                            // Rows
                            block.rows.forEach { row ->
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    row.forEach { cell ->
                                        Text(
                                            text = renderInlineMarkdown(cell),
                                            style = bodyTextStyle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
