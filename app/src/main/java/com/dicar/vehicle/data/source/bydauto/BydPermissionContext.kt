package com.dicar.vehicle.data.source.bydauto

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * 需求 2.6「签名权限问题 → 反射 + 自定义 Context」。
 *
 * BYDAuto 设备类在 getInstance(Context) / get / set 时，用传入的 Context 在【本进程内】做
 * android.permission.BYDAUTO_* 权限检查（_GET/_SET 是签名级，第三方 App 拿不到）。
 * 把这些检查直接放行即可正常调用 —— 来源：wheregoes/byd-apps BydPermissionContext.java，
 * DiLink 3（Dolphin / Song Pro GS）实测有效。DiLink 4.0 未经验证；若服务端（autoservice）
 * 自己再校验一次，这里无能为力，会表现为 SecurityException，请改用迪加数据源。
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)

    override fun checkSelfPermission(permission: String): Int =
        if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!isByd(permission)) super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!isByd(permission)) super.enforcePermission(permission, pid, uid, message)
    }

    private fun isByd(permission: String?) = permission?.startsWith(BYD_PERMISSION_PREFIX) == true

    companion object {
        private const val BYD_PERMISSION_PREFIX = "android.permission.BYDAUTO_"
    }
}
