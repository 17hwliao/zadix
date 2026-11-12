package li.songe.zadixsource.data

data class ExcludeData(
    val appIds: Map<String, Boolean>,
    val activityIds: Set<Pair<String, String>>,
) {
    val excludeAppIds = appIds.entries.filter { e -> e.value }.map { e -> e.key }.toHashSet()
    val includeAppIds = appIds.entries.filter { e -> !e.value }.map { e -> e.key }.toHashSet()

    companion object {
        fun parse(exclude: String?): ExcludeData {
            val appIds = mutableMapOf<String, Boolean>()
            val activityIds = mutableSetOf<Pair<String, String>>()
            (exclude ?: "").split('\n', ',').filter { s -> s.isNotBlank() }.map { s -> s.trim() }
                .forEach { s ->
                    if (s[0] == '!') {
                        appIds[s.substring(1)] = false
                    } else {
                        val a = s.split('/')
                        val appId = a[0]
                        val activityId = a.getOrNull(1)
                        if (activityId != null) {
                            activityIds.add(appId to activityId)
                        } else {
                            appIds[appId] = true
                        }
                    }
                }
            return ExcludeData(
                appIds = appIds,
                activityIds = activityIds,
            )
        }

        fun parse(appId: String, exclude: String?): ExcludeData {
            return parse((exclude ?: "").split('\n', ',').joinToString("\n") { "$appId/$it" })
        }
    }
}
