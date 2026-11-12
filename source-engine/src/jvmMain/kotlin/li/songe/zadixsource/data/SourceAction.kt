package li.songe.zadixsource.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.content.res.Resources

data class ActionResult(
    val action: String?,
    val result: Boolean,
)

sealed class ActionPerformer(val action: String) {
    abstract fun perform(
        context: AccessibilityService,
        node: AccessibilityNodeInfo,
        position: RawSubscription.Position?,
        viewport: Rect,
    ): ActionResult

    data object ClickNode : ActionPerformer("clickNode") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
        }
    }

    data object ClickCenter : ActionPerformer("clickCenter") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val p = position?.let { it.calc(rect) ?: return ActionResult(action, false) }
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            return ActionResult(
                action = action,
                result = if (inViewport(x, y, viewport)) {
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, ViewConfiguration.getTapTimeout().toLong()
                        )
                    )
                    context.dispatchGesture(gestureDescription.build(), null, null)
                } else {
                    false
                }
            )
        }
    }

    data object Click : ActionPerformer("click") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            if (node.isClickable) {
                val result = ClickNode.perform(context, node, position, viewport)
                if (result.result) {
                    return result
                }
            }
            return ClickCenter.perform(context, node, position, viewport)
        }
    }

    data object LongClickNode : ActionPerformer("longClickNode") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            )
        }
    }

    data object LongClickCenter : ActionPerformer("longClickCenter") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val p = position?.let { it.calc(rect) ?: return ActionResult(action, false) }
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            // 内部的 DEFAULT_LONG_PRESS_TIMEOUT 常量是 400
            // 而 ViewConfiguration.getLongPressTimeout() 返回 300, 这将导致触发普通的 click 事件
            return ActionResult(
                action = action,
                result = if (inViewport(x, y, viewport)) {
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, 400L
                        )
                    )
                    // TODO 传入处理 callback
                    context.dispatchGesture(gestureDescription.build(), null, null)
                } else {
                    false
                }
            )
        }
    }

    data object LongClick : ActionPerformer("longClick") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            if (node.isLongClickable) {
                val result = LongClickNode.perform(context, node, position, viewport)
                if (result.result) {
                    return result
                }
            }
            return LongClickCenter.perform(context, node, position, viewport)
        }
    }

    data object Back : ActionPerformer("back") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            viewport: Rect,
        ): ActionResult {
            return ActionResult(
                action = action,
                result = context.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            )
        }
    }

    protected fun inViewport(x: Float, y: Float, viewport: Rect): Boolean {
        val display = Resources.getSystem().displayMetrics
        return x >= maxOf(0, viewport.left) && y >= maxOf(0, viewport.top) &&
            x < minOf(display.widthPixels, viewport.right) && y < minOf(display.heightPixels, viewport.bottom)
    }

    companion object {
        private val allSubObjects by lazy {
            arrayOf(ClickNode, ClickCenter, Click, LongClickNode, LongClickCenter, LongClick, Back)
        }

        fun getAction(action: String?): ActionPerformer {
            return allSubObjects.find { it.action == action } ?: Click
        }
    }
}
