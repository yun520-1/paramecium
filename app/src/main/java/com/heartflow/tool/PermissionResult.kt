package com.heartflow.tool

/**
 * 权限结果数据类
 */
data class PermissionResult(
    val allowed: Boolean,
    val reason: String
) {
    companion object {
        /**
         * 允许执行
         */
        fun allowed(): PermissionResult {
            return PermissionResult(allowed = true, reason = "")
        }

        /**
         * 拒绝执行
         */
        fun denied(reason: String): PermissionResult {
            return PermissionResult(allowed = false, reason = reason)
        }
    }

    fun isAllowed(): Boolean = allowed
}
