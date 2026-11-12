package li.songe.zadixsource.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.InputStream
import java.util.zip.ZipInputStream

data class AppInfo(
    val id: String,
    val versionCode: Long,
    val versionName: String?,
    val system: Boolean,
    val launcher: Boolean,
)

data class SubsItem(val id: Long, val order: Int, val enable: Boolean)
data class SubsConfig(val enable: Boolean?, val exclude: String?)

private data class GroupKey(val subscription: Long, val type: Int, val app: String, val group: Int)
private data class CategoryKey(val subscription: Long, val category: Int)

/** Enabled startup rules and the matching source snapshots. */
class StartupProfile private constructor(
    private val subscriptions: List<Pair<SubsItem, RawSubscription>>,
    private val catalogSources: List<RawSubscription>,
    private val configs: Map<GroupKey, SubsConfig>,
    private val categories: Map<CategoryKey, Boolean?>,
) {
    val appCategories: Map<String, Set<String>> by lazy {
        buildMap<String, MutableSet<String>> {
            for (source in catalogSources) {
                for (app in source.apps) {
                    val categories = getOrPut(app.id) { linkedSetOf<String>() }
                    for (group in app.groups) {
                        categories.add(when {
                            group.name.startsWith("开屏广告") -> "opening"
                            group.name.startsWith("全屏广告") -> "fullscreen"
                            group.name.startsWith("局部广告") -> "partial"
                            group.name.startsWith("分段广告") -> "inline"
                            group.name.startsWith("综合广告") -> "mixed"
                            else -> "other"
                        })
                    }
                }
            }
        }
    }

    private fun config(id: Long, type: Int, app: String = "", key: Int = -1) =
        configs[GroupKey(id, type, app, key)]

    private fun RawSubscription.RawGroupProps.startup() =
        name.startsWith("开屏广告") && valid

    fun supportsOpening(app: AppInfo): Boolean =
        compile(app, RuleSession { 0L }).any { it.matchActivity(app.id, null) }

    fun compile(app: AppInfo, session: RuleSession): List<ResolvedRule> {
        val appRules = mutableListOf<AppRule>()
        val globalRules = mutableListOf<GlobalRule>()
        for ((item, subscription) in subscriptions) {
            val globals = linkedMapOf<RawSubscription.RawGlobalGroup, List<GlobalRule>>()
            for (group in subscription.globalGroups) {
                val config = config(item.id, 3, key = group.key)
                if (!(config?.enable ?: group.enable ?: true) || !group.startup()) continue
                val resolved = ResolvedGlobalGroup(group, subscription, item, config, session)
                globals[group] = group.rules.map { GlobalRule(it, resolved, app) }
            }
            for (rule in globals.values.flatten()) {
                rule.groupToRules = globals
                globalRules += rule
            }
            val rawApp = subscription.apps.find { it.id == app.id } ?: continue
            if (config(item.id, 1, app.id)?.enable == false) continue
            val groups = linkedMapOf<RawSubscription.RawAppGroup, List<AppRule>>()
            for (group in rawApp.groups) {
                val config = config(item.id, 2, app.id, group.key)
                val category = subscription.groupToCategoryMap[group]
                val categoryEnable = category?.let {
                    categories.getOrDefault(CategoryKey(item.id, it.key), it.enable)
                }
                if (!(config?.enable ?: categoryEnable ?: group.enable ?: true) || !group.startup()) continue
                val resolved = ResolvedAppGroup(group, subscription, item, config, session, rawApp, true)
                groups[group] = group.rules.map { AppRule(it, resolved, app) }.filter { it.enable }
            }
            for (rule in groups.values.flatten()) {
                rule.groupToRules = groups
                appRules += rule
            }
        }
        return (appRules + globalRules).sortedBy { it.order }
    }

    fun description(): String = subscriptions.joinToString("、") { (_, raw) ->
        val label = when (raw.id) {
            825L -> "Adpro"
            666L -> "AIsouler"
            else -> if (raw.id < 0) "本地规则 #${-raw.id}" else "资料来源 #${raw.id}"
        }
        "$label v${raw.version}"
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        @JvmStatic
        fun read(backup: InputStream, remoteSources: List<String>): StartupProfile {
            val localSources = mutableListOf<String>()
            var remaining = MAX_BYTES
            var transfer: String? = null
            ZipInputStream(backup).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 128) { "旧规则文件过多" }
                    val bytes = zip.readNBytes(remaining + 1)
                    require(bytes.size <= remaining) { "旧规则解压后过大" }
                    remaining -= bytes.size
                    if (entry.name == "transfer_data.json") {
                        require(transfer == null) { "备份包含重复的配置" }
                        transfer = bytes.toString(Charsets.UTF_8)
                    } else if (entry.name.matches(Regex("files/-[0-9]+\\.json"))) {
                        localSources += bytes.toString(Charsets.UTF_8)
                    }
                }
            }
            val data = Json.parseToJsonElement(requireNotNull(transfer) { "缺少旧规则配置" }).jsonObject
            require(data.string("type") == "transfer_data") { "无法识别旧规则配置" }
            return compileSource(data, localSources + remoteSources, "subsItems", "subsConfigs", "categoryConfigs")
        }

        @JvmStatic
        fun readSource(profile: String, sources: List<String>): StartupProfile = compileSource(
            Json.parseToJsonElement(profile).jsonObject, sources, "sources", "groups", "categories",
        )

        private fun compileSource(data: JsonObject, snapshots: List<String>, sourceKey: String,
                                  groupKey: String, categoryKey: String): StartupProfile {
            val sources = snapshots.map { RawSubscription.parse(it) }
            require(sources.distinctBy { it.id }.size == sources.size) { "订阅文件 ID 重复" }
            val byId = sources.associateBy { it.id }
            val selected = data.objects(sourceKey).map {
                SubsItem(it.long("id"), it.int("order"), it.boolean("enable") ?: true)
            }
            require(selected.distinctBy { it.id }.size == selected.size) { "备份订阅 ID 重复" }
            val subscriptions = selected.filter { it.enable }.sortedBy { it.order }.map { item ->
                item to requireNotNull(byId[item.id]) { "缺少已启用的订阅文件 ${item.id}" }
            }
            require(subscriptions.isNotEmpty()) { "备份中没有启用的订阅" }
            val configs = data.objects(groupKey).associate {
                GroupKey(it.long("subsItemId"), it.int("type"), it.string("appId") ?: "",
                    it["groupKey"]?.jsonPrimitive?.int ?: -1) to SubsConfig(it.boolean("enable"), it.string("exclude"))
            }
            val categories = data.objects(categoryKey).associate {
                CategoryKey(it.long("subsItemId"), it.int("categoryKey")) to it.boolean("enable")
            }
            return StartupProfile(subscriptions, sources, configs, categories)
        }

        private fun JsonObject.objects(key: String) = this[key]?.jsonArray?.map { it.jsonObject }.orEmpty()
        private fun JsonObject.string(key: String) = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        private fun JsonObject.boolean(key: String) = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean
        private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
        private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    }
}
