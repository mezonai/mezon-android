package com.mezon.mobile.util

object FileUtils {

    fun getFileColor(filename: String, fallbackColor: Int): Int {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> 0xFFE53935.toInt()
            "doc", "docx" -> 0xFF1E88E5.toInt()
            "xls", "xlsx" -> 0xFF43A047.toInt()
            "ppt", "pptx" -> 0xFFF4511E.toInt()
            "zip", "rar", "7z", "tar", "gz" -> 0xFFFDD835.toInt()
            "mp3", "wav", "aac", "flac", "ogg" -> 0xFFE040FB.toInt()
            "txt", "csv", "log" -> 0xFF78909C.toInt()
            "apk" -> 0xFF66BB6A.toInt()
            "json", "xml", "html", "css", "js", "ts", "kt", "java", "py" -> 0xFF26C6DA.toInt()
            else -> fallbackColor
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024f * 1024f))
        }
    }
}
