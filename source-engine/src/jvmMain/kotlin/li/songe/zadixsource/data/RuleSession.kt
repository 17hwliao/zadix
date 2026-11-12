package li.songe.zadixsource.data

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.zadixsource.service.A11yContext

/** Owns the startup rule state for the current foreground app and submits through the host gate. */
class RuleSession(private val clock: java.util.function.LongSupplier) {
    private var app: AppInfo? = null
    private var activity: String? = null
    private var allRules: List<ResolvedRule> = emptyList()
    private var active: List<ResolvedRule> = emptyList()
    private var enteredAt = 0L
    private var lastAcceptedAt: Long? = null
    internal var lastTriggerRule: ResolvedRule? = null

    fun now(): Long = clock.asLong

    fun foreground(profile: StartupProfile, app: AppInfo, activity: String, enteredAt: Long) {
        if (app != this.app) {
            clear()
            this.app = app
            this.enteredAt = enteredAt
            allRules = profile.compile(app, this)
            allRules.forEach { it.matchChangedTime = enteredAt }
        } else if (activity == this.activity) {
            return
        }
        val next = allRules.filter { it.matchActivity(app.id, activity) }
        if (this.activity != null) {
            for (rule in next) {
                if (rule.resetMatchTypeWhenActivity) {
                    rule.actionDelayTriggerTime = 0
                    rule.actionCount.value = 0
                }
                if (rule !in active) rule.matchChangedTime = now()
            }
        }
        active = next
        this.activity = activity
    }

    fun clear() {
        app = null
        activity = null
        allRules = emptyList()
        active = emptyList()
        lastTriggerRule = null
        lastAcceptedAt = null
    }

    fun opening(): Boolean = active.isNotEmpty() && now() - enteredAt in 0..OPENING_LIMIT_MS

    fun nextWake(): Long {
        if (!opening()) return -1
        val time = now()
        val deadlines = active.mapNotNull { rule ->
            when (rule.status) {
                RuleStatus.Status3 -> rule.matchChangedTime + rule.matchDelay
                RuleStatus.Status6 -> rule.actionDelayTriggerTime + rule.actionDelay
                RuleStatus.StatusOk, RuleStatus.Status5 ->
                    if (rule.checkForced() || time - enteredAt < 5000 ||
                        lastAcceptedAt?.let { time - it < 3000 } == true
                    ) time + 300 else null
                else -> null
            }
        }
        return deadlines.minOrNull()?.let { (it - time).coerceAtLeast(1L) } ?: -1
    }

    class Match internal constructor(internal val rule: ResolvedRule, internal val node: AccessibilityNodeInfo) {
        fun id(): String = "source:${rule.subsItem.id}:${rule.type}:${rule.g.group.key}:${rule.index}"
    }

    fun query(root: AccessibilityNodeInfo): Match? {
        if (!opening()) return null
        val context = A11yContext(root)
        for (rule in active.sortedBy { if (it.isPriority()) 0 else 1 }) {
            if (!rule.status.ok) continue
            val target = context.queryRule(rule, root) ?: continue
            if (!sameWindow(root, target)) continue
            if (rule.checkDelay()) continue
            if (rule.status.ok) return Match(rule, target)
        }
        return null
    }

    fun submit(match: Match, service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val rule = match.rule
        if (!opening() || rule !in active || !rule.status.ok) return false
        val target = A11yContext(root).queryRule(rule, root) ?: return false
        if (target != match.node || !sameWindow(root, target)) return false
        if (hasSensitivePrompt(root)) return false
        // Revalidate the node and its bounds immediately before executing an upstream action.
        val before = Rect().also { target.getBoundsInScreen(it) }
        if (!target.refresh() || !sameWindow(root, target)) return false
        if (target.text?.toString() in SENSITIVE_ACTIONS || target.contentDescription?.toString() in SENSITIVE_ACTIONS) {
            return false
        }
        val after = Rect().also { target.getBoundsInScreen(it) }
        val viewport = Rect().also { root.getBoundsInScreen(it) }
        if (before != after || !viewport.contains(after) || after.isEmpty) return false
        val accepted = rule.performAction(service, target, viewport).result
        if (accepted) {
            rule.trigger()
            lastAcceptedAt = now()
        }
        return accepted
    }

    private fun sameWindow(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Boolean =
        target.windowId == root.windowId && target.packageName?.toString() == root.packageName?.toString() &&
            target.isVisibleToUser && target.isEnabled

    private fun hasSensitivePrompt(root: AccessibilityNodeInfo): Boolean =
        SENSITIVE_PROMPTS.any { phrase ->
            root.findAccessibilityNodeInfosByText(phrase).any { node ->
                sameWindow(root, node) &&
                    (node.text?.toString() == phrase || node.contentDescription?.toString() == phrase)
            }
        }

    companion object {
        private const val OPENING_LIMIT_MS = 15_000L
        private val SENSITIVE_PROMPTS = listOf("用户协议与隐私政策", "请输入密码", "请输入验证码", "确认支付")
        private val SENSITIVE_ACTIONS = setOf("同意", "同意并继续", "允许", "授权", "确认支付")
    }
}
