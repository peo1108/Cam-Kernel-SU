package me.weishu.kernelsu.ui.util.module

import java.io.File
import java.util.zip.ZipFile

/** The `id` from a module zip's `module.prop`, or null when the zip has none. */
fun readModuleIdFromZip(file: File): String? = runCatching {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("module.prop") ?: return@use null
        zip.getInputStream(entry).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.substringBefore('=').trim() == "id" && it.contains('=') }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}.getOrNull()
