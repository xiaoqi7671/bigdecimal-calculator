package com.xiaoqi.calculator

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 让 Material 3 在 Android 12+ 上跟随壁纸动态取色；
 * 12 以下自动回退到主题里定义的静态配色。
 */
class CalculatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
