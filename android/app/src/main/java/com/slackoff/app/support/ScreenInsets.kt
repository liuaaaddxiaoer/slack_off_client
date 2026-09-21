package com.slackoff.app.support

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsets

object ScreenInsets {
    fun top(activity: Activity?): Float {
        if (activity == null) return 24f
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val insets = activity.window.decorView.rootWindowInsets
            val statusBars = insets?.getInsets(WindowInsets.Type.statusBars())
            statusBars?.top?.toFloat() ?: 24f
        } else {
            24f
        }
    }

    fun bottom(activity: Activity?): Float {
        if (activity == null) return 0f
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val insets = activity.window.decorView.rootWindowInsets
            val navBars = insets?.getInsets(WindowInsets.Type.navigationBars())
            navBars?.bottom?.toFloat() ?: 0f
        } else {
            0f
        }
    }

    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}