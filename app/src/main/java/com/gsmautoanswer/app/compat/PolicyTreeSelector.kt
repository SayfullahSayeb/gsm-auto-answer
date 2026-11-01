package com.gsmautoanswer.app.compat

object PolicyTreeSelector {
    private val INCLUDE_REGEX =
        Regex("""<xi:include[^>]*\bhref\s*=\s*["']([^"']+)["']""")
    private val CONFIG_SOURCE_REGEX = Regex("""Config source:\s*(\S+)""")
    private val FALLBACK_PATHS = listOf(
        "/vendor/etc/audio_policy_configuration_sec.xml",
        "/vendor/etc/audio_policy_configuration.xml",
        "/odm/etc/audio_policy_configuration_sec.xml",
        "/odm/etc/audio_policy_configuration.xml",
        "/system/etc/audio_policy_configuration.xml",
    )

    fun selectActive(
        dumpsys: String,
        localPath: String?,
        available: Set<String>,
    ): String? {
        val fromDumpsys = CONFIG_SOURCE_REGEX.find(dumpsys)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!fromDumpsys.isNullOrBlank() && fromDumpsys in available) return fromDumpsys
        if (!localPath.isNullOrBlank() && localPath in available) return localPath
        return FALLBACK_PATHS.firstOrNull { it in available }
    }

    fun collect(activePath: String?, contents: Map<String, String>): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (activePath.isNullOrBlank()) return result
        val visited = mutableSetOf<String>()
        fun visit(path: String) {
            if (!visited.add(path)) return
            val content = contents[path] ?: return
            result[path] = content
            INCLUDE_REGEX.findAll(content).forEach { match ->
                visit(resolve(path, match.groupValues[1]))
            }
        }
        visit(activePath)
        return result
    }

    private fun resolve(parent: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.startsWith("/")) return trimmed
        val directory = parent.substringBeforeLast('/', "")
        val raw = if (directory.isEmpty()) trimmed else "$directory/$trimmed"
        val parts = mutableListOf<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
