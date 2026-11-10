package com.xiaoqi.calculator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var expr = ""
    private var errorText: String? = null
    private var lastSnapshotExpr = ""

    private lateinit var store: HistoryStore
    private val entries = mutableListOf<HistoryEntry>()

    private lateinit var historyBar: TextView
    private lateinit var exprView: TextView
    private lateinit var previewView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelList: LinearLayout

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleSnapshot = Runnable { takeSnapshot() }

    private val density get() = resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { expr = it.getString(KEY_EXPR) ?: "" }
        store = HistoryStore(this)
        entries.addAll(store.load())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = COLOR_BACKGROUND
            @Suppress("DEPRECATION")
            window.navigationBarColor = COLOR_BACKGROUND
        }

        setContentView(buildRoot())
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EXPR, expr)
    }

    // ---------------------------------------------------------------- 界面搭建

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        root.addView(buildMain(), FrameLayout.LayoutParams(MATCH, MATCH))
        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun buildMain(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(18f), topInset() + dp(10f), dp(18f), dp(18f))
        }
        column.addView(buildHistoryBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(buildDisplay(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildKeypad(), LinearLayout.LayoutParams(MATCH, 0, 2.5f))
        return column
    }

    private fun buildHistoryBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = keyDrawable(Color.parseColor("#1C1C1E"))
            setOnClickListener { openPanel() }
        }
        historyBar = TextView(this).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        val arrow = TextView(this).apply {
            text = "▸"
            setTextColor(COLOR_KEY_OPERATOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8f), 0, 0, 0)
        }
        row.addView(historyBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(arrow, LinearLayout.LayoutParams(WRAP, WRAP))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10f))
        }
        wrap.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    private fun buildDisplay(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        exprView = TextView(this).apply {
            gravity = Gravity.END
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
            setOnLongClickListener {
                showDisplayMenu()
                true
            }
        }
        previewView = TextView(this).apply {
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isSingleLine = true
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
                    setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
                })
            }
            keypad.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return keypad
    }

    private fun buildPanel(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2000000"))
            setPadding(dp(18f), topInset() + dp(10f), dp(18f), dp(18f))
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(4f), dp(12f))
        }
        val title = TextView(this).apply {
            text = "历史记录"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val clear = TextView(this).apply {
            text = "清空"
            setTextColor(COLOR_KEY_OPERATOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16f), dp(6f), dp(8f), dp(6f))
            setOnClickListener { confirmClear() }
        }
        val close = TextView(this).apply {
            text = "关闭"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { closePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(clear, LinearLayout.LayoutParams(WRAP, WRAP))
        header.addView(close, LinearLayout.LayoutParams(WRAP, WRAP))
        overlay.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        panelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panelList, ScrollView.LayoutParams(MATCH, WRAP))
        }
        overlay.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return overlay
    }

    private fun makeKey(label: String): Button {
        val color = when {
            label in OPERATORS -> COLOR_KEY_OPERATOR
            label in FUNCTIONS -> COLOR_KEY_FUNCTION
            else -> COLOR_KEY_DIGIT
        }
        val foreground = if (label in FUNCTIONS) Color.BLACK else Color.WHITE
        return Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = false
            stateListAnimator = null
            backgroundTintList = null
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setTextColor(foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 22f else 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            contentDescription = when (label) {
                "⌫" -> "退格"
                "±" -> "正负号"
                else -> label
            }
            background = rippleKey(color)
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

    private fun keyDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(40f).toFloat()
        setColor(color)
    }

    private fun rippleKey(color: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(RIPPLE_COLOR),
        keyDrawable(color),
        keyDrawable(Color.WHITE)
    )

    private fun topInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
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
        when (val outcome = Calculator.evaluate(expr)) {
            is Calculator.Outcome.Value -> {
                val result = Calculator.format(outcome.value)
                record(expr, result, snapshot = false)
                expr = result
                errorText = null
                lastSnapshotExpr = ""
            }
            is Calculator.Outcome.Failure -> {
                errorText = outcome.reason
                expr = ""
            }
        }
    }

    // ---------------------------------------------------------------- 历史记录

    private fun record(exprText: String, result: String, snapshot: Boolean) {
        entries.add(HistoryEntry(exprText, result, System.currentTimeMillis(), snapshot))
        store.save(entries)
        if (panel.visibility == View.VISIBLE) rebuildPanel()
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
    }

    private fun openPanel() {
        rebuildPanel()
        panel.visibility = View.VISIBLE
    }

    private fun closePanel() {
        panel.visibility = View.GONE
    }

    private fun rebuildPanel() {
        panelList.removeAllViews()
        if (entries.isEmpty()) {
            panelList.addView(TextView(this).apply {
                text = "还没有记录"
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(4f), dp(24f), dp(4f), dp(4f))
            })
            return
        }
        for (entry in entries.asReversed()) {
            panelList.addView(makeHistoryRow(entry), LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun makeHistoryRow(entry: HistoryEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(Color.parseColor("#14FFFFFF"))
            setOnClickListener { restore(entry.expr) }
            setOnLongClickListener {
                copyToClipboard("${entry.expr} = ${entry.result}")
                true
            }
        }
        val meta = TextView(this).apply {
            text = formatDate(entry.date) + if (entry.snapshot) " · 停留记录" else ""
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val formula = TextView(this).apply {
            text = entry.expr
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val result = TextView(this).apply {
            text = "= ${entry.result}"
            setTextColor(COLOR_KEY_OPERATOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        row.addView(formula, LinearLayout.LayoutParams(MATCH, WRAP))
        row.addView(result, LinearLayout.LayoutParams(MATCH, WRAP))
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8f))
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return holder
    }

    private fun restore(exprText: String) {
        expr = exprText
        errorText = null
        closePanel()
        render()
        scheduleSnapshot()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("清空全部历史记录？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                entries.clear()
                store.save(entries)
                rebuildPanel()
                render()
            }
            .show()
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
        exprView.setTextColor(if (error != null) COLOR_ERROR else Color.WHITE)
        exprView.text = text
        exprView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeFor(text.length))

        previewView.text = previewText()
        previewView.visibility = if (previewView.text.isEmpty()) View.GONE else View.VISIBLE

        historyBar.text = entries.lastOrNull()?.let {
            "${formatDate(it.date)}   ${it.expr} = ${it.result}"
        } ?: "暂无历史记录"
    }

    /** 输入过程中给一个淡色的"= 结果"提示，但绝不替换用户敲的内容。 */
    private fun previewText(): String {
        if (errorText != null || expr.isEmpty()) return ""
        if (!expr.contains('(') && expr.none { Calculator.isOperator(it) }) return ""
        return when (val outcome = Calculator.evaluate(expr, partial = true)) {
            is Calculator.Outcome.Value -> "= ${Calculator.format(outcome.value)}"
            is Calculator.Outcome.Failure -> ""
        }
    }

    private fun sizeFor(length: Int): Float = when {
        length <= 8 -> 64f
        length <= 10 -> 58f
        length <= 12 -> 48f
        length <= 14 -> 42f
        length <= 18 -> 34f
        length <= 24 -> 28f
        else -> 22f
    }

    companion object {
        private const val KEY_EXPR = "expr"
        private const val IDLE_SNAPSHOT_MS = 15_000L

        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private val COLOR_BACKGROUND = Color.parseColor("#000000")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#8E8E93")
        private val COLOR_KEY_DIGIT = Color.parseColor("#2C2C2E")
        private val COLOR_KEY_OPERATOR = Color.parseColor("#FF9F0A")
        private val COLOR_KEY_FUNCTION = Color.parseColor("#A5A5A5")
        private val COLOR_ERROR = Color.parseColor("#FF453A")
        private const val RIPPLE_COLOR = 0x40FFFFFF

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
