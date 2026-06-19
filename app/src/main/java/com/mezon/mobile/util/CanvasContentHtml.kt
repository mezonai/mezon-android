package com.mezon.mobile.util

import org.json.JSONArray
import org.json.JSONObject

sealed class CanvasBodyContent {
    data class Html(val body: String) : CanvasBodyContent()
    data class PlainText(val text: String) : CanvasBodyContent()
}

object CanvasContentHtml {

    fun resolve(apiContent: String): CanvasBodyContent {
        val normalized = CanvasContentNormalizer.unwrap(apiContent)
        val html = when {
            normalized.startsWith("<") -> normalized
            normalized.startsWith("[") -> ComposeArrayParser.extractHtml(normalized)
            normalized.startsWith("{") -> JsonDocumentParser.toHtml(normalized)
            else -> null
        }
        if (!html.isNullOrBlank()) {
            return CanvasBodyContent.Html(CanvasHtmlSanitizer.sanitizeBody(HtmlEntityDecoder.decodeInMarkup(html)))
        }
        return CanvasBodyContent.PlainText(HtmlEntityDecoder.decodeText(normalized))
    }
}

private object CanvasContentNormalizer {

    fun unwrap(content: String): String {
        var value = content.trim()
        while (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            val unwrapped = try {
                JSONObject("""{"v":$value}""").getString("v").trim()
            } catch (_: Exception) {
                return value
            }
            if (unwrapped == value) return value
            value = unwrapped
        }
        return value
    }
}

private object ComposeArrayParser {

    fun extractHtml(content: String): String? {
        return try {
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("type", "")
                if (!type.equals("TEXT", ignoreCase = true) && !type.equals("HTML", ignoreCase = true)) continue
                val html = item.optString("value", "")
                if (html.isNotBlank()) return html
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private object JsonDocumentParser {

    fun toHtml(content: String): String? {
        return try {
            val root = JSONObject(content)
            when {
                root.optString("type") == "doc" -> ProseMirrorHtmlRenderer.render(root)
                root.has("ops") -> QuillDeltaHtmlRenderer.render(root.optJSONArray("ops"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

private object HtmlEntityDecoder {

    private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#\d+|[a-zA-Z][\w.-]*);""")

    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "plus" to "+",
        "equals" to "=",
        "minus" to "-",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "cent" to "\u00A2",
        "pound" to "\u00A3",
        "yen" to "\u00A5",
        "euro" to "\u20AC",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
    )

    private const val MAX_DECODE_PASSES = 3

    fun decodeText(text: String): String {
        if (!text.contains('&')) return text
        return decodeUntilStable(text) { source ->
            ENTITY.replace(source) { match -> decodeEntity(match.value) ?: match.value }
        }
    }

    fun decodeInMarkup(html: String): String {
        if (!html.contains('&')) return html
        return decodeUntilStable(html) { source ->
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when (val ch = source[i]) {
                    '<' -> {
                        val close = source.indexOf('>', startIndex = i)
                        if (close < 0) {
                            out.append(source, i, source.length)
                            i = source.length
                        } else {
                            out.append(source, i, close + 1)
                            i = close + 1
                        }
                    }
                    '&' -> {
                        val semi = source.indexOf(';', startIndex = i + 1)
                        if (semi > i) {
                            val decoded = decodeEntity(source.substring(i, semi + 1))
                            if (decoded != null) {
                                out.append(decoded)
                                i = semi + 1
                                continue
                            }
                        }
                        out.append(ch)
                        i++
                    }
                    else -> {
                        out.append(ch)
                        i++
                    }
                }
            }
            out.toString()
        }
    }

    private fun decodeUntilStable(text: String, transform: (String) -> String): String {
        var decoded = text
        repeat(MAX_DECODE_PASSES) {
            val next = transform(decoded)
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun decodeEntity(entity: String): String? {
        val body = entity.substring(1, entity.length - 1)
        return when {
            body.startsWith("#x", ignoreCase = true) -> codePointToString(body.substring(2).toIntOrNull(16))
            body.startsWith("#") -> codePointToString(body.substring(1).toIntOrNull())
            else -> NAMED[body.lowercase()]
        }
    }

    private fun codePointToString(code: Int?): String? {
        if (code == null) return null
        return if (code <= 0xFFFF) code.toChar().toString() else String(Character.toChars(code))
    }
}

private object CanvasHtmlSanitizer {

    private val BLOCKED_SCHEME = Regex("""^(javascript|data|file|vbscript|blob):""", RegexOption.IGNORE_CASE)
    private val DANGEROUS_BLOCK_TAG = Regex(
        """<(script|iframe|object|embed|form|meta|link|base|frame|frameset|applet)[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DANGEROUS_VOID_TAG = Regex(
        """<(script|iframe|object|embed|input|meta|link|base|frame|applet)[^>]*/?>""",
        RegexOption.IGNORE_CASE
    )
    private val EVENT_ATTR = Regex(
        """\s(on\w+)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
        RegexOption.IGNORE_CASE
    )
    private val URL_ATTR = Regex(
        """(?i)\s(href|src)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)"""
    )

    private const val MAX_SANITIZE_PASSES = 3

    fun sanitizeBody(html: String): String {
        if (!html.contains('<', ignoreCase = true)) return html
        var result = html
        if (DANGEROUS_VOID_TAG.containsMatchIn(html) ||
            DANGEROUS_BLOCK_TAG.containsMatchIn(html) ||
            EVENT_ATTR.containsMatchIn(html)
        ) {
            for (pass in 0 until MAX_SANITIZE_PASSES) {
                val stripped = DANGEROUS_VOID_TAG.replace(DANGEROUS_BLOCK_TAG.replace(result, ""), "")
                val next = EVENT_ATTR.replace(stripped, "")
                if (next == result) break
                result = next
            }
        }
        if (result.contains("href", ignoreCase = true) || result.contains("src", ignoreCase = true)) {
            result = sanitizeUrlAttributes(result)
        }
        return result
    }

    private fun sanitizeUrlAttributes(html: String): String {
        return URL_ATTR.replace(html) { match ->
            val attrName = match.groupValues[1].lowercase()
            val rawValue = unquoteAttrValue(match.groupValues[2])
            val safe = safeUrl(rawValue)
            if (safe != null) " $attrName=\"$safe\"" else ""
        }
    }

    private fun unquoteAttrValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2) {
            val quote = trimmed.first()
            if ((quote == '"' || quote == '\'') && trimmed.last() == quote) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    fun isAllowedUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || BLOCKED_SCHEME.containsMatchIn(trimmed)) return false
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("mailto:", ignoreCase = true)
    }

    fun safeUrl(url: String): String? {
        val trimmed = url.trim()
        val decoded = if (trimmed.contains('&')) HtmlEntityDecoder.decodeText(trimmed) else trimmed
        return decoded.takeIf { isAllowedUrl(it) }?.let { HtmlEscaper.escapeAttr(it) }
    }
}

private object HtmlEscaper {

    fun escape(text: String): String {
        var needsEscape = false
        for (ch in text) {
            when (ch) {
                '&', '<', '>', '"' -> {
                    needsEscape = true
                    break
                }
            }
        }
        if (!needsEscape) return text

        val out = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun escapeAttr(text: String): String = escape(text)
}

private object HtmlTags {

    fun wrap(name: String, inner: String, attrs: String? = null): String {
        return if (attrs.isNullOrBlank()) {
            "<$name>$inner</$name>"
        } else {
            "<$name $attrs>$inner</$name>"
        }
    }
}

private object ProseMirrorHtmlRenderer {

    fun render(root: JSONObject): String {
        return renderNodes(root.optJSONArray("content"))
    }

    private fun renderNodes(nodes: JSONArray?, inline: Boolean = false): String {
        if (nodes == null) return ""
        val out = StringBuilder()
        for (i in 0 until nodes.length()) {
            nodes.optJSONObject(i)?.let { out.append(renderNode(it, inline)) }
        }
        return out.toString()
    }

    private fun renderNode(node: JSONObject, inline: Boolean): String {
        val type = node.optString("type")
        if (inline) {
            return when (type) {
                "hardBreak" -> "<br>"
                "text" -> renderTextNode(node)
                else -> renderNode(node, inline = false)
            }
        }
        return when (type) {
            "paragraph" -> HtmlTags.wrap("p", renderNodes(node.optJSONArray("content"), inline = true), paragraphAttrs(node))
            "heading" -> {
                val level = node.optJSONObject("attrs")?.optInt("level", 1)?.coerceIn(1, 6) ?: 1
                HtmlTags.wrap("h$level", renderNodes(node.optJSONArray("content"), inline = true), paragraphAttrs(node))
            }
            "bulletList" -> HtmlTags.wrap("ul", renderNodes(node.optJSONArray("content")))
            "orderedList" -> HtmlTags.wrap("ol", renderNodes(node.optJSONArray("content")))
            "listItem" -> HtmlTags.wrap("li", renderNodes(node.optJSONArray("content")))
            "blockquote" -> HtmlTags.wrap("blockquote", renderNodes(node.optJSONArray("content")))
            "codeBlock" -> {
                val text = HtmlEntityDecoder.decodeText(extractPlainText(node.optJSONArray("content")))
                HtmlTags.wrap("pre", HtmlTags.wrap("code", HtmlEscaper.escape(text)))
            }
            "horizontalRule" -> "<hr>"
            "hardBreak" -> "<br>"
            "image" -> renderImage(node)
            "text" -> renderTextNode(node)
            else -> renderNodes(node.optJSONArray("content"))
        }
    }

    private fun renderImage(node: JSONObject): String {
        val attrs = node.optJSONObject("attrs")
        val src = CanvasHtmlSanitizer.safeUrl(attrs?.optString("src").orEmpty()) ?: return ""
        val alt = HtmlEscaper.escapeAttr(HtmlEntityDecoder.decodeText(attrs?.optString("alt").orEmpty()))
        return """<img src="$src" alt="$alt">"""
    }

    private fun paragraphAttrs(node: JSONObject): String? {
        val align = node.optJSONObject("attrs")?.optString("textAlign").orEmpty()
        return when (align) {
            "center", "right", "justify", "left" -> """style="text-align: $align;""""
            else -> null
        }
    }

    private fun renderTextNode(node: JSONObject): String {
        val escaped = HtmlEscaper.escape(HtmlEntityDecoder.decodeText(node.optString("text", "")))
        val marks = node.optJSONArray("marks") ?: return escaped
        var html = escaped
        for (i in marks.length() - 1 downTo 0) {
            val mark = marks.optJSONObject(i) ?: continue
            html = wrapMark(mark, html)
        }
        return html
    }

    private fun wrapMark(mark: JSONObject, inner: String): String {
        return when (mark.optString("type")) {
            "bold" -> HtmlTags.wrap("strong", inner)
            "italic" -> HtmlTags.wrap("em", inner)
            "underline" -> HtmlTags.wrap("u", inner)
            "strike" -> HtmlTags.wrap("s", inner)
            "code" -> HtmlTags.wrap("code", inner)
            "link" -> {
                val href = CanvasHtmlSanitizer.safeUrl(mark.optJSONObject("attrs")?.optString("href").orEmpty())
                if (href.isNullOrBlank()) inner else """<a href="$href" target="_blank" rel="noopener noreferrer">$inner</a>"""
            }
            else -> inner
        }
    }

    private fun extractPlainText(nodes: JSONArray?): String {
        if (nodes == null) return ""
        val out = StringBuilder()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            when (node.optString("type")) {
                "text" -> out.append(node.optString("text", ""))
                "hardBreak" -> out.append('\n')
                else -> out.append(extractPlainText(node.optJSONArray("content")))
            }
        }
        return out.toString()
    }
}

private object QuillDeltaHtmlRenderer {

    private data class Line(
        val html: String,
        val header: Int = 0,
        val list: String = "",
        val blockquote: Boolean = false,
        val codeBlock: Boolean = false,
        val align: String = "",
        val isImage: Boolean = false,
    )

    fun render(ops: JSONArray?): String {
        if (ops == null) return ""
        return renderLines(parseLines(ops))
    }

    private fun parseLines(ops: JSONArray): List<Line> {
        val lines = mutableListOf<Line>()
        val buffer = StringBuilder()

        fun flushLine(attrs: JSONObject?) {
            lines.add(lineFrom(buffer.toString(), attrs))
            buffer.clear()
        }

        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            val attrs = op.optJSONObject("attributes")
            when (val insert = op.opt("insert")) {
                is String -> appendInsertLines(insert, attrs, buffer, lines, ::flushLine)
                is JSONObject -> {
                    if (buffer.isNotEmpty()) flushLine(null)
                    CanvasHtmlSanitizer.safeUrl(insert.optString("image", ""))?.let { src ->
                        lines.add(Line("""<img src="$src" alt="">""", isImage = true))
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) flushLine(null)
        return lines
    }

    private fun appendInsertLines(
        insert: String,
        attrs: JSONObject?,
        buffer: StringBuilder,
        lines: MutableList<Line>,
        flushLine: (JSONObject?) -> Unit,
    ) {
        var start = 0
        var pos = 0
        while (pos <= insert.length) {
            if (pos == insert.length || insert[pos] == '\n') {
                if (pos > start) buffer.append(inlineHtml(insert.substring(start, pos), attrs))
                if (pos < insert.length) flushLine(attrs)
                start = pos + 1
            }
            pos++
        }
    }

    private fun lineFrom(content: String, attrs: JSONObject?): Line {
        val safeAttrs = attrs ?: JSONObject()
        return Line(
            html = content,
            header = safeAttrs.optInt("header", 0),
            list = safeAttrs.optString("list", ""),
            blockquote = safeAttrs.optBoolean("blockquote", false),
            codeBlock = safeAttrs.optBoolean("code-block", false),
            align = safeAttrs.optString("align", ""),
        )
    }

    private fun inlineHtml(text: String, attrs: JSONObject?): String {
        var html = HtmlEscaper.escape(HtmlEntityDecoder.decodeText(text))
        val safeAttrs = attrs ?: return html

        CanvasHtmlSanitizer.safeUrl(safeAttrs.optString("link", ""))?.let { href ->
            html = """<a href="$href" target="_blank" rel="noopener noreferrer">$html</a>"""
        }
        if (safeAttrs.optBoolean("bold", false)) html = HtmlTags.wrap("strong", html)
        if (safeAttrs.optBoolean("italic", false)) html = HtmlTags.wrap("em", html)
        if (safeAttrs.optBoolean("underline", false)) html = HtmlTags.wrap("u", html)
        if (safeAttrs.optBoolean("strike", false)) html = HtmlTags.wrap("s", html)
        if (safeAttrs.optBoolean("code", false)) html = HtmlTags.wrap("code", html)
        return html
    }

    private fun renderLines(lines: List<Line>): String {
        val out = StringBuilder()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isImage) {
                out.append(line.html)
                index++
                continue
            }
            val listType = line.list
            if (listType == "bullet" || listType == "ordered" || listType == "checked") {
                val tag = if (listType == "ordered") "ol" else "ul"
                out.append("<$tag>")
                while (index < lines.size) {
                    val item = lines[index]
                    if (item.isImage || item.list != listType) break
                    out.append(renderBlock(item, forceListItem = true))
                    index++
                }
                out.append("</$tag>")
            } else {
                out.append(renderBlock(line))
                index++
            }
        }
        return out.toString()
    }

    private fun renderBlock(line: Line, forceListItem: Boolean = false): String {
        val alignAttr = when (line.align) {
            "center", "right", "justify", "left" -> """style="text-align: ${line.align};""""
            else -> null
        }
        val inner = line.html.ifBlank { "<br>" }
        return when {
            forceListItem -> HtmlTags.wrap("li", inner, alignAttr)
            line.codeBlock -> HtmlTags.wrap("pre", HtmlTags.wrap("code", inner))
            line.blockquote -> HtmlTags.wrap("blockquote", inner, alignAttr)
            line.header in 1..6 -> HtmlTags.wrap("h${line.header}", inner, alignAttr)
            else -> HtmlTags.wrap("p", inner, alignAttr)
        }
    }
}
