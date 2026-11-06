package com.example.georeport.ui.common

import android.app.Activity
import android.content.Intent
import com.example.georeport.data.UserStore
import com.example.georeport.domain.Role
import com.example.georeport.ui.auth.LoginActivity
import com.example.georeport.ui.home.HomeActivity
import com.example.georeport.ui.reports.ReportsActivity

object SessionNavigation {
    fun route(activity: Activity) {
        val identity = UserStore(activity).sessionIdentity()
        val target = when(identity?.second) {
            Role.ADMINISTRADOR -> ReportsActivity::class.java
            Role.CIUDADANO -> HomeActivity::class.java
            null -> LoginActivity::class.java
        }
        activity.startActivity(Intent(activity, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("admin", identity?.second == Role.ADMINISTRADOR)
        })
        activity.finish()
    }
    fun require(activity: Activity, role: Role? = null): Boolean {
        val identity = UserStore(activity).sessionIdentity()
        if(identity == null || (role != null && identity.second != role)) { route(activity); return false }
        return true
    }
    fun logout(activity: Activity) { UserStore(activity).clearSession(); route(activity) }
}
