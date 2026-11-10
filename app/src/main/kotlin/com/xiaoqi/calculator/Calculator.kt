package com.xiaoqi.calculator

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * 表达式引擎：四则运算、一元负号、后缀百分号、括号。
 * 纯 Kotlin，不依赖 Android，方便脱离界面单独验证。
 */
object Calculator {

    /** 输入长度上限，避免用户瞎按出一串没法显示的东西。 */
    const val MAX_LENGTH = 64

    sealed class Outcome {
        data class Value(val value: Double) : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    fun isOperator(c: Char): Boolean = c == '+' || c == '−' || c == '×' || c == '÷'

    fun isOpenParen(c: Char): Boolean = c == '('

    /** 把界面上的 Unicode 运算符翻译成解析器认识的 ASCII。 */
    private fun normalize(raw: String): String =
        raw.replace('×', '*').replace('÷', '/').replace('−', '-')

    /**
     * @param partial 输入过程中的实时预览：允许未闭合的括号、允许以运算符结尾。
     */
    fun evaluate(raw: String, partial: Boolean = false): Outcome {
        if (raw.isEmpty()) return Outcome.Failure("空表达式")
        val text = if (partial) trimDanglingTail(raw) else raw
        if (text.isEmpty()) return Outcome.Failure("空表达式")
        return try {
            val value = Parser(normalize(text), allowUnclosed = partial).parse()
            if (value.isFinite()) Outcome.Value(value) else Outcome.Failure("结果超出范围")
        } catch (e: ParserException) {
            Outcome.Failure(e.message ?: "语法错误")
        } catch (e: ArithmeticException) {
            Outcome.Failure("不能除以 0")
        } catch (e: NumberFormatException) {
            Outcome.Failure("数字格式错误")
        }
    }

    /** 结果格式化：12 位有效数字，抹掉浮点尾巴，过大过小转科学计数法。 */
    fun format(value: Double): String {
        if (!value.isFinite()) return "错误"
        val rounded = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        val magnitude = abs(value)
        return when {
            magnitude != 0.0 && (magnitude >= 1e15 || magnitude < 1e-9) -> rounded.toString()
            rounded.scale() < 0 -> rounded.setScale(0).toPlainString()
            else -> rounded.toPlainString()
        }
    }

    /** 预览时砍掉末尾的运算符/多余小数点，让 "5+" 也能算出一个 5 来。 */
    private fun trimDanglingTail(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val c = raw[end - 1]
            if (isOperator(c) || c == '(') {
                end--
                continue
            }
            break
        }
        return raw.substring(0, end)
    }

    /** 结尾数字段（用于小数点、正负号、退格这类"只作用最后一个数"的操作）。 */
    fun trailingNumberStart(src: String): Int {
        var i = src.length
        while (i > 0) {
            val c = src[i - 1]
            if (isOperator(c) || isOpenParen(c)) break
            i--
        }
        return i
    }

    private class ParserException(message: String) : Exception(message)

    private class Parser(private val src: String, private val allowUnclosed: Boolean) {
        private var i = 0

        fun parse(): Double {
            val value = sum()
            if (i != src.length) throw ParserException("多余的内容")
            return value
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'

        private fun number(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            if (start == i) throw ParserException("这里需要一个数字")
            var value = src.substring(start, i).toDouble()
            // 后缀百分号，可以叠：50%% = 0.005
            while (peek() == '%') {
                i++
                value /= 100.0
            }
            return value
        }

        private fun factor(): Double {
            if (peek() == '-') {
                i++
                return -factor()
            }
            return primary()
        }

        private fun primary(): Double {
            if (peek() != '(') return number()
            i++
            val inner = sum()
            if (peek() == ')') {
                i++
            } else if (!allowUnclosed) {
                throw ParserException("括号没有闭合")
            }
            return inner
        }

        private fun term(): Double {
            var value = factor()
            while (peek() == '*' || peek() == '/') {
                val op = src[i]
                i++
                val rhs = factor()
                value = if (op == '*') {
                    value * rhs
                } else {
                    if (rhs == 0.0) throw ArithmeticException("divide by zero")
                    value / rhs
                }
            }
            return value
        }

        private fun sum(): Double {
            var value = term()
            while (peek() == '+' || peek() == '-') {
                val op = src[i]
                i++
                val rhs = term()
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }
    }
}
