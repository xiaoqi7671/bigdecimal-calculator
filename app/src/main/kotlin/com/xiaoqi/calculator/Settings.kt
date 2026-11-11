package com.xiaoqi.calculator

import android.content.Context

/**
 * 用户偏好：都落在 SharedPreferences 里，进程重启/重装后保留。
 * 读取时给默认值，避免第一次运行时出现空值分支。
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("calculator-settings", Context.MODE_PRIVATE)

    /** true = 相对百分比（200+10% = 220）；false = 数学百分号（200+10% = 200.1）。 */
    var percentRelative: Boolean
        get() = prefs.getBoolean(KEY_PERCENT_RELATIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_PERCENT_RELATIVE, value).apply()

    /** 连按等号重复上一次运算：2+3= 5，再按 = 得 8。 */
    var repeatEquals: Boolean
        get() = prefs.getBoolean(KEY_REPEAT_EQUALS, true)
        set(value) = prefs.edit().putBoolean(KEY_REPEAT_EQUALS, value).apply()

    /** 科学模式开关（键盘暂未接入，先占位）。 */
    var scientific: Boolean
        get() = prefs.getBoolean(KEY_SCIENTIFIC, false)
        set(value) = prefs.edit().putBoolean(KEY_SCIENTIFIC, value).apply()

    fun percentMode(): PercentMode =
        if (percentRelative) PercentMode.RELATIVE else PercentMode.MATH

    private companion object {
        const val KEY_PERCENT_RELATIVE = "percent_relative"
        const val KEY_REPEAT_EQUALS = "repeat_equals"
        const val KEY_SCIENTIFIC = "scientific"
    }
}
