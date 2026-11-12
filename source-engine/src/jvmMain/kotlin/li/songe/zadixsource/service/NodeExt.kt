package li.songe.zadixsource.service

import android.view.accessibility.AccessibilityNodeInfo
import li.songe.selector.MismatchExpressionTypeException
import li.songe.selector.MismatchOperatorTypeException
import li.songe.selector.MismatchParamTypeException
import li.songe.selector.Selector
import li.songe.selector.UnknownIdentifierException
import li.songe.selector.UnknownIdentifierMethodException
import li.songe.selector.UnknownIdentifierMethodParamsException
import li.songe.selector.UnknownMemberException
import li.songe.selector.UnknownMemberMethodException
import li.songe.selector.UnknownMemberMethodParamsException
import li.songe.selector.initDefaultTypeInfo


fun AccessibilityNodeInfo.getVid(): CharSequence? {
    val id = viewIdResourceName ?: return null
    val appId = packageName ?: return null
    if (id.startsWith(appId) && id.startsWith(":id/", appId.length)) {
        return id.subSequence(
            appId.length + ":id/".length,
            id.length
        )
    }
    return null
}

// https://github.com/gkd-kit/gkd/issues/115
// https://github.com/gkd-kit/gkd/issues/650
// 限制节点遍历的数量避免内存溢出
const val MAX_CHILD_SIZE = 512
const val MAX_DESCENDANTS_SIZE = 4096

private const val A11Y_NODE_TIME_KEY = "generatedTime"
fun AccessibilityNodeInfo.setGeneratedTime() {
    extras.putLong(A11Y_NODE_TIME_KEY, System.currentTimeMillis())
}

fun AccessibilityNodeInfo.isExpired(expiryMillis: Long): Boolean {
    val generatedTime = extras.getLong(A11Y_NODE_TIME_KEY, -1)
    if (generatedTime == -1L) {
        setGeneratedTime()
        return false
    }
    return (System.currentTimeMillis() - generatedTime) > expiryMillis
}

private val typeInfo by lazy { initDefaultTypeInfo().globalType }

fun Selector.checkSelector(): String? {
    val error = checkType(typeInfo) ?: return null
    return when (error) {
        is MismatchExpressionTypeException -> "不匹配表达式类型:${error.exception.stringify()}"
        is MismatchOperatorTypeException -> "不匹配操作符类型:${error.exception.stringify()}"
        is MismatchParamTypeException -> "不匹配参数类型:${error.call.stringify()}"
        is UnknownIdentifierException -> "未知属性:${error.value.stringify()}"
        is UnknownIdentifierMethodException -> "未知方法:${error.value.stringify()}"
        is UnknownMemberException -> "未知属性:${error.value.stringify()}"
        is UnknownMemberMethodException -> "未知方法:${error.value.stringify()}"
        is UnknownIdentifierMethodParamsException -> "未知方法参数:${error.value.stringify()}"
        is UnknownMemberMethodParamsException -> "未知方法参数:${error.value.stringify()}"
    }
}