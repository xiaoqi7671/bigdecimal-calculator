package com.xiaoqi.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private var expr = ""
    private var errorText: String? = null
    private var lastSnapshotExpr = ""

    private lateinit var store: HistoryStore
    private val entries = mutableListOf<HistoryEntry>()

    private lateinit var settings: Settings
    private lateinit var rootView: View
    private lateinit var exprView: TextView
    private lateinit var previewView: TextView

    private var historySheet: BottomSheetDialog? = null
    private var settingsSheet: BottomSheetDialog? = null

    /** 上一次双目运算，供"连按等号重复"使用。 */
    private var lastOp: Char? = null
    private var lastOperand: String? = null
    /** true = 屏幕上是刚算出的结果，不是用户正在敲的式子。 */
    private var resultMode = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleSnapshot = Runnable { takeSnapshot() }

    private val density get() = resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 从当前主题（可能已被动态取色改写）读颜色。 */
    private fun color(attr: Int): Int =
        MaterialColors.getColor(window.decorView, attr, Color.GRAY)

    private val colorSurface get() = color(com.google.android.material.R.attr.colorSurface)
    private val colorOnSurface get() = color(com.google.android.material.R.attr.colorOnSurface)
    private val colorOnSurfaceVariant get() =
        color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val colorPrimary get() = color(androidx.appcompat.R.attr.colorPrimary)
    private val colorOnPrimary get() = color(com.google.android.material.R.attr.colorOnPrimary)
    private val colorPrimaryContainer get() =
        color(com.google.android.material.R.attr.colorPrimaryContainer)
    private val colorOnPrimaryContainer get() =
        color(com.google.android.material.R.attr.colorOnPrimaryContainer)
    private val colorSecondaryContainer get() =
        color(com.google.android.material.R.attr.colorSecondaryContainer)
    private val colorOnSecondaryContainer get() =
        color(com.google.android.material.R.attr.colorOnSecondaryContainer)
    private val colorSurfaceContainerHigh get() =
        color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val colorSurfaceContainerHighest get() =
        color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val colorError get() = color(androidx.appcompat.R.attr.colorError)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 起对 targetSdk 35+ 强制 edge-to-edge，边距由我们自己吃 inset
        WindowCompat.setDecorFitsSystemWindows(window, false)
        savedInstanceState?.let { expr = it.getString(KEY_EXPR) ?: "" }
        settings = Settings(this)
        store = HistoryStore(this)
        entries.addAll(store.load())

        setContentView(buildRoot())
        applyWindowInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    historySheet?.isShowing == true -> historySheet?.dismiss()
                    settingsSheet?.isShowing == true -> settingsSheet?.dismiss()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        render()
    }

    /** 系统栏 + 挖孔 inset 加到根布局内边距上。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(16f) + bars.left,
                dp(8f) + bars.top,
                dp(16f) + bars.right,
                dp(8f) + bars.bottom,
            )
            insets
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EXPR, expr)
    }

    // ---------------------------------------------------------------- 界面搭建

    private fun buildRoot(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorSurface)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        rootView = column
        column.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(48f)))
        column.addView(buildDisplay(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildKeypad(), LinearLayout.LayoutParams(MATCH, 0, 2.6f))
        return column
    }

    /** 顶栏：左历史、右设置；中间弹性区留给后续栏位（换算、科学模式等）。 */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            circleIcon(R.drawable.ic_history_outline, "历史记录") { showHistorySheet() },
            LinearLayout.LayoutParams(dp(48f), dp(48f))
        )
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(1f), 1f))
        bar.addView(
            circleIcon(R.drawable.ic_settings_outline, "设置") { showSettingsSheet() },
            LinearLayout.LayoutParams(dp(48f), dp(48f))
        )
        return bar
    }

    /** 40dp 圆底 + 22dp 描边图标，触摸热区 48dp。 */
    private fun circleIcon(icon: Int, description: String, onClick: () -> Unit): View {
        val outer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val disc = FrameLayout(this).apply {
            background = rippleRound(colorSurfaceContainerHigh, 20f)
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(colorOnSurfaceVariant)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        disc.addView(image, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        outer.addView(disc, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.CENTER))
        return outer
    }

    private fun buildDisplay(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        exprView = TextView(this).apply {
            gravity = Gravity.END
            setTextColor(colorOnSurface)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
            fontFeatureSettings = "tnum"
            setOnClickListener { copyToClipboard(text.toString()) }
            setOnLongClickListener {
                showDisplayMenu()
                true
            }
        }
        previewView = TextView(this).apply {
            gravity = Gravity.END
            setTextColor(colorOnSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isSingleLine = true
            fontFeatureSettings = "tnum"
            setPadding(0, dp(6f), 0, 0)
        }
        box.addView(previewView, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(exprView, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    private fun buildKeypad(): View {
        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in KEY_ROWS) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = row.sumOf { it.second.toDouble() }.toFloat()
            }
            for ((label, weight) in row) {
                line.addView(makeKey(label), LinearLayout.LayoutParams(0, MATCH, weight).apply {
                    setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                })
            }
            keypad.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return keypad
    }

    private fun makeKey(label: String): MaterialButton {
        val isFunction = label in FUNCTIONS
        val isEquals = label == "="
        val background = when {
            isFunction -> colorSecondaryContainer
            isEquals -> colorPrimary
            label in OPERATORS -> colorPrimaryContainer
            else -> colorSurfaceContainerHighest
        }
        val foreground = when {
            isFunction -> colorOnSecondaryContainer
            isEquals -> colorOnPrimary
            label in OPERATORS -> colorOnPrimaryContainer
            else -> colorOnSurface
        }
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            isFocusable = false
            stateListAnimator = null
            cornerRadius = dp(28f)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(background)
            setTextColor(foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 20f else 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            rippleColor = ColorStateList.valueOf(withAlpha(foreground, 0x33))
            contentDescription = when (label) {
                "⌫" -> "退格"
                "±" -> "正负号"
                else -> label
            }
            setOnClickListener { onKey(label) }
            if (label == "⌫") {
                setOnLongClickListener {
                    expr = ""
                    errorText = null
                    render()
                    true
                }
            }
        }
    }

    // ---------------------------------------------------------------- 交互逻辑

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = when {
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                tap((keyCode - KeyEvent.KEYCODE_0).toString())
            keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                tap((keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString())
            keyCode == KeyEvent.KEYCODE_PERIOD || keyCode == KeyEvent.KEYCODE_NUMPAD_DOT -> tap(".")
            keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD -> tap("+")
            keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> tap("−")
            keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> tap("×")
            keyCode == KeyEvent.KEYCODE_SLASH || keyCode == KeyEvent.KEYCODE_NUMPAD_DIVIDE -> tap("÷")
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> tap("=")
            keyCode == KeyEvent.KEYCODE_DEL -> tap("⌫")
            keyCode == KeyEvent.KEYCODE_ESCAPE -> tap("C")
            else -> null
        }
        return handled == true || super.onKeyDown(keyCode, event)
    }

    private fun tap(label: String): Boolean {
        onKey(label)
        return true
    }

    private fun onKey(key: String) {
        errorText = null
        // 刚出结果时：敲数字重新开始，敲运算符在结果上继续算
        if (resultMode && key != "=" && key != "⌫" && key != "%" && key != "±") {
            if (key == "C" || key !in OPERATORS) expr = ""
            resultMode = false
        }
        val full = expr.length >= Calculator.MAX_LENGTH && key !in setOf("=", "C", "⌫")
        if (!full) {
            when (key) {
                "C" -> expr = ""
                "⌫" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
                "±" -> toggleSign()
                "=" -> commit()
                "%" -> appendPercent()
                "." -> appendDot()
                "(", ")" -> appendParen(key[0])
                "+", "−", "×", "÷" -> pressOperator(key[0])
                else -> expr += key
            }
        }
        haptic()
        render()
        scheduleSnapshot()
    }

    private fun haptic() {
        runCatching { exprView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }

    private fun appendDot() {
        val start = Calculator.trailingNumberStart(expr)
        val segment = expr.substring(start)
        if (segment.contains('.')) return
        expr += if (segment.isEmpty()) "0." else "."
    }

    private fun appendPercent() {
        val last = expr.lastOrNull() ?: return
        if (last.isDigit() || last == ')' || last == '%') expr += "%"
    }

    private fun appendParen(paren: Char) {
        val last = expr.lastOrNull()
        if (paren == '(') {
            if (last != null && (last.isDigit() || last == ')' || last == '%')) expr += "×"
            expr += "("
            return
        }
        val open = expr.count { it == '(' } - expr.count { it == ')' }
        if (open <= 0) return
        if (last == null || Calculator.isOperator(last) || last == '(') return
        expr += ")"
    }

    private fun pressOperator(op: Char) {
        if (expr.isEmpty()) {
            if (op == '−') expr = "−"
            return
        }
        val last = expr.last()
        if (!Calculator.isOperator(last)) {
            expr += op
            return
        }
        if (op == '−' && last != '−') {
            expr += op
            return
        }
        var base = expr.dropLast(1)
        if (base.length >= 2 && base.last() == '−' && Calculator.isOperator(base[base.length - 2])) {
            base = base.dropLast(1)
        }
        expr = if (base.isEmpty()) "" else base + op
    }

    private fun toggleSign() {
        val start = Calculator.trailingNumberStart(expr)
        if (start >= expr.length) return
        expr = if (expr[start] == '−') {
            expr.removeRange(start, start + 1)
        } else {
            expr.substring(0, start) + "−" + expr.substring(start)
        }
    }

    private fun commit() {
        if (expr.isEmpty()) return
        // 连按等号：把上一次的运算符与右操作数接回结果后面
        if (resultMode && settings.repeatEquals) {
            val op = lastOp
            val rhs = lastOperand
            if (op != null && rhs != null) expr = expr + op + rhs
        }
        when (val outcome = Calculator.evaluate(expr, percentMode = settings.percentMode())) {
            is Calculator.Outcome.Value -> {
                val result = Calculator.format(outcome.value)
                val source = expr
                record(source, result, snapshot = false)
                Calculator.splitLastOperation(source)?.let { (op, rhs) ->
                    lastOp = op
                    lastOperand = rhs
                }
                expr = result
                errorText = null
                resultMode = true
                lastSnapshotExpr = ""
            }
            is Calculator.Outcome.Failure -> {
                errorText = outcome.reason
                expr = ""
                resultMode = false
            }
        }
    }

    // ---------------------------------------------------------------- 历史记录

    private fun record(exprText: String, result: String, snapshot: Boolean) {
        entries.add(HistoryEntry(exprText, result, System.currentTimeMillis(), snapshot))
        store.save(entries)
    }

    /** 停手一段时间后把"半成品算式"也记一条，免得算到一半切走就忘了。 */
    private fun scheduleSnapshot() {
        idleHandler.removeCallbacks(idleSnapshot)
        if (expr.contains('(') || expr.any { Calculator.isOperator(it) }) {
            idleHandler.postDelayed(idleSnapshot, IDLE_SNAPSHOT_MS)
        }
    }

    private fun takeSnapshot() {
        if (expr.isEmpty() || expr == lastSnapshotExpr) return
        if (!expr.contains('(') && expr.none { Calculator.isOperator(it) }) return
        val value = Calculator.evaluate(expr, partial = true)
        val shown = if (value is Calculator.Outcome.Value) {
            Calculator.format(value.value)
        } else {
            "未完成"
        }
        record(expr, shown, snapshot = true)
        lastSnapshotExpr = expr
        render()
    }

    /** MD3 底部抽屉：拖拽把手 + 圆角行，长按复制，点按回填。 */
    private fun showHistorySheet() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
            setBackgroundColor(colorSurface)
        }

        content.addView(
            View(this).apply { background = rounded(withAlpha(colorOnSurfaceVariant, 0x4D), 2f) },
            LinearLayout.LayoutParams(dp(32f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f)
            }
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(16f), dp(4f), dp(10f))
        }
        header.addView(TextView(this).apply {
            text = "历史记录"
            setTextColor(colorOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(TextView(this).apply {
            text = "清空"
            setTextColor(colorError)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = rippleRound(withAlpha(colorError, 0x1F), 20f)
            setOnClickListener {
                sheet.dismiss()
                confirmClear()
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "还没有记录"
                setTextColor(colorOnSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(4f), dp(16f), dp(4f), dp(16f))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            for (entry in entries.asReversed()) {
                list.addView(makeSheetRow(entry) {
                    restore(entry.expr)
                    sheet.dismiss()
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        val scroll = ScrollView(this).apply { addView(list) }
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))

        sheet.setContentView(content)
        historySheet = sheet
        sheet.setOnDismissListener { historySheet = null }
        sheet.show()
    }

    private fun makeSheetRow(entry: HistoryEntry, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = rippleRound(colorSurfaceContainerHigh, 20f)
            setOnClickListener { onClick() }
            setOnLongClickListener {
                copyToClipboard("${entry.expr} = ${entry.result}")
                true
            }
        }
        row.addView(TextView(this).apply {
            text = formatDate(entry.date) + if (entry.snapshot) " · 停留记录" else ""
            setTextColor(colorOnSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        row.addView(TextView(this).apply {
            text = entry.expr
            setTextColor(colorOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        row.addView(TextView(this).apply {
            text = "= ${entry.result}"
            setTextColor(colorPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val holder = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(8f))
            addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        return holder
    }

    private fun restore(exprText: String) {
        expr = exprText
        errorText = null
        resultMode = false
        render()
        scheduleSnapshot()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setMessage("清空全部历史记录？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                entries.clear()
                store.save(entries)
                render()
            }
            .show()
    }

    // ---------------------------------------------------------------- 设置

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(20f))
            setBackgroundColor(colorSurface)
        }
        content.addView(
            View(this).apply { background = rounded(withAlpha(colorOnSurfaceVariant, 0x4D), 2f) },
            LinearLayout.LayoutParams(dp(32f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f)
                bottomMargin = dp(10f)
            }
        )
        content.addView(sheetTitle("设置"), LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(
            settingRow(
                title = "百分比按相对值计算",
                subtitle = "开：200+10% = 220　　关：200+10% = 200.1",
                checked = settings.percentRelative,
            ) { on ->
                settings.percentRelative = on
                render()
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8f) }
        )
        content.addView(
            settingRow(
                title = "连按等号重复上次运算",
                subtitle = "2+3= 得 5，再按一次 = 得 8",
                checked = settings.repeatEquals,
            ) { on -> settings.repeatEquals = on },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8f) }
        )
        content.addView(
            settingRow(
                title = "科学模式",
                subtitle = "键盘接入后出现在这里（尚未实现）",
                checked = settings.scientific,
                enabled = false,
            ) { on -> settings.scientific = on },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        sheet.setContentView(content)
        settingsSheet = sheet
        sheet.setOnDismissListener { settingsSheet = null }
        sheet.show()
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(colorOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(14f), dp(2f), 0, dp(10f))
    }

    private fun settingRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(10f), dp(12f))
            background = rippleRound(colorSurfaceContainerHigh, 20f)
            alpha = if (enabled) 1f else 0.45f
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(colorOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(colorOnSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3f), dp(8f), 0)
        })
        val toggle = MaterialSwitch(this).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(toggle, LinearLayout.LayoutParams(WRAP, WRAP))
        if (enabled) row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    // ---------------------------------------------------------------- 剪贴板

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("calculator", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val raw = clip.getItemAt(0).coerceToText(this).toString()
        val cleaned = buildString {
            for (c in raw) {
                when (c) {
                    in '0'..'9', '.', '+', '(', ')', '%', '−', '×', '÷' -> append(c)
                    '-' -> append('−')
                    '*' -> append('×')
                    '/' -> append('÷')
                    else -> Unit
                }
                if (length >= Calculator.MAX_LENGTH) break
            }
        }
        if (cleaned.isEmpty()) {
            Toast.makeText(this, "剪贴板里没有可用的算式", Toast.LENGTH_SHORT).show()
            return
        }
        expr = cleaned
        errorText = null
        render()
        scheduleSnapshot()
    }

    private fun showDisplayMenu() {
        val menu = PopupMenu(this, exprView)
        menu.menu.add(0, 1, 0, "复制表达式")
        menu.menu.add(0, 2, 1, "粘贴")
        menu.menu.add(0, 3, 2, "清空")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> copyToClipboard(expr)
                2 -> pasteFromClipboard()
                else -> {
                    expr = ""
                    errorText = null
                    render()
                }
            }
            true
        }
        menu.show()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val error = errorText
        val text = when {
            error != null -> error
            expr.isEmpty() -> "0"
            else -> expr
        }
        exprView.setTextColor(if (error != null) colorError else colorOnSurface)
        exprView.text = text
        exprView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeFor(text.length))

        previewView.text = previewText()
        previewView.visibility = if (previewView.text.isEmpty()) View.GONE else View.VISIBLE

    }

    /** 输入过程中给一个淡色的"= 结果"提示，但绝不替换用户敲的内容。 */
    private fun previewText(): String {
        if (errorText != null || expr.isEmpty()) return ""
        if (!expr.contains('(') && expr.none { Calculator.isOperator(it) }) return ""
        return when (val outcome = Calculator.evaluate(expr, partial = true, percentMode = settings.percentMode())) {
            is Calculator.Outcome.Value -> "= ${Calculator.format(outcome.value)}"
            is Calculator.Outcome.Failure -> ""
        }
    }

    private fun sizeFor(length: Int): Float = when {
        length <= 8 -> 56f
        length <= 10 -> 50f
        length <= 12 -> 44f
        length <= 14 -> 38f
        length <= 18 -> 32f
        length <= 24 -> 26f
        else -> 20f
    }

    // ---------------------------------------------------------------- 小工具

    private fun rounded(fill: Int, cornerDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(fill)
        }

    private fun rippleRound(fill: Int, cornerDp: Float): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(withAlpha(if (fill == colorSurfaceContainerHigh) colorOnSurface else fill, 0x33)),
            rounded(fill, cornerDp),
            rounded(Color.WHITE, cornerDp)
        )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val KEY_EXPR = "expr"
        private const val IDLE_SNAPSHOT_MS = 15_000L

        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private val OPERATORS = setOf("÷", "×", "−", "+", "=")
        private val FUNCTIONS = setOf("C", "⌫", "%", "(", ")", "±")

        private val KEY_ROWS: List<List<Pair<String, Float>>> = listOf(
            listOf("C" to 1f, "⌫" to 1f, "%" to 1f, "÷" to 1f),
            listOf("(" to 1f, ")" to 1f, "±" to 1f, "×" to 1f),
            listOf("7" to 1f, "8" to 1f, "9" to 1f, "−" to 1f),
            listOf("4" to 1f, "5" to 1f, "6" to 1f, "+" to 1f),
            listOf("1" to 1f, "2" to 1f, "3" to 1f, "=" to 1f),
            listOf("0" to 3f, "." to 1f)
        )
    }
}
