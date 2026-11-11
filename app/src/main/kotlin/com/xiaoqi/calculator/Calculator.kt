package com.xiaoqi.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 百分比语义：MATH 按数学意义 x/100；RELATIVE 让 "200+10%" 等于 220。 */
enum class PercentMode { RELATIVE, MATH }

/**
 * 表达式引擎。
 *
 * 四则运算、幂、后缀百分号/阶乘、括号、常数 π/e、函数 sin/cos/tan/ln/log/√ 都用 BigDecimal 算，
 * 精度 34 位有效数字（DECIMAL128）。三角函数、对数、开方内部转 double（Java 没有 BigDecimal 版本），
 * 那几步的有效位数会掉到 15~17 位，其余运算保持 34 位。
 */
object Calculator {

    /** 输入长度上限。 */
    const val MAX_LENGTH = 64

    /** 结果的数量级上限：|x| >= 10^1000 视为溢出，避免算出没法显示的巨数。 */
    const val MAX_EXPONENT = 1000

    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal("100")
    private val TRANSCENDENTAL = MathContext(15, RoundingMode.HALF_EVEN)

    sealed class Outcome {
        data class Value(val value: BigDecimal) : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    fun isOperator(c: Char): Boolean = c == '+' || c == '−' || c == '×' || c == '÷' || c == '^'

    fun isOpenParen(c: Char): Boolean = c == '('

    private fun normalize(raw: String): String = raw
        .replace('×', '*')
        .replace('÷', '/')
        .replace('−', '-')
        .replace('（', '(')
        .replace('）', ')')

    /**
     * @param partial 输入过程中的实时预览：允许未闭合的括号、允许以运算符结尾。
     */
    fun evaluate(
        raw: String,
        partial: Boolean = false,
        percentMode: PercentMode = PercentMode.RELATIVE,
    ): Outcome {
        if (raw.isEmpty()) return Outcome.Failure("空表达式")
        val text = if (partial) trimDanglingTail(raw) else raw
        if (text.isEmpty()) return Outcome.Failure("空表达式")
        return try {
            val value = Parser(normalize(text), allowUnclosed = partial, percentMode = percentMode).parse()
            Outcome.Value(value)
        } catch (e: ParserException) {
            Outcome.Failure(e.message ?: "语法错误")
        } catch (e: ArithmeticException) {
            Outcome.Failure("不能除以 0")
        } catch (e: NumberFormatException) {
            Outcome.Failure("数字格式错误")
        }
    }

    /** 科学计数法的上下阈值：|x| >= 10^15 或 < 10^-9 时改用 E 记法。 */
    private fun magnitudeExponent(value: BigDecimal): Int {
        val stripped = value.stripTrailingZeros()
        return stripped.precision() - stripped.scale() - 1
    }

    /** 结果格式化：最多 34 位有效数字，抹掉尾随零；小到 10⁻¹⁰ 以下或长到 34 位装不下时转科学记数法。 */
    fun format(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        if (stripped.signum() == 0) return "0"
        val exponent = magnitudeExponent(stripped)
        if (exponent <= -10) return scientific(stripped)
        if (exponent >= 15) {
            val plain = stripped.toPlainString()
            if (plain.length <= 34) return plain
            return scientific(stripped)
        }
        return stripped.toPlainString()
    }

    private fun scientific(value: BigDecimal): String = value.toString().replace("E+", "E")

    /** 预览时砍掉末尾的运算符/多余小数点，让 "5+" 也能算出一个 5 来。 */
    private fun trimDanglingTail(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val c = raw[end - 1]
            if (isOperator(c) || c == '(' || c == '^') {
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

    /** 拆出最后一个顶层双目运算，供"连按等号重复上一次运算"使用。 */
    fun splitLastOperation(src: String): Pair<Char, String>? {
        var depth = 0
        var index = -1
        for (i in src.indices) {
            val c = src[i]
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                isOperator(c) && depth == 0 -> {
                    val prev = src.getOrNull(i - 1)
                    val unary = c == '−' && (prev == null || isOperator(prev) || prev == '(')
                    if (!unary) index = i
                }
            }
        }
        if (index <= 0 || index >= src.length - 1) return null
        return src[index] to src.substring(index + 1)
    }

    private class ParserException(message: String) : Exception(message)

    private class Parser(
        private val src: String,
        private val allowUnclosed: Boolean,
        private val percentMode: PercentMode,
    ) {
        private var i = 0

        fun parse(): BigDecimal {
            val value = sum()
            if (i != src.length) throw ParserException("多余的内容")
            return guard(value)
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'

        private fun guard(value: BigDecimal): BigDecimal {
            if (value.compareTo(BigDecimal.ZERO) == 0) return value
            if (magnitudeExponent(value) > MAX_EXPONENT) throw ParserException("结果超出范围")
            return value
        }

        private fun number(): BigDecimal {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            if (start == i) throw ParserException("这里需要一个数字")
            val digits = src.substring(start, i)
            var exponent = 0
            if (i < src.length && src[i] == 'E') {
                i++
                var sign = 1
                if (peek() == '+' || peek() == '-') {
                    if (peek() == '-') sign = -1
                    i++
                }
                val expStart = i
                while (i < src.length && src[i].isDigit()) i++
                if (expStart == i) throw ParserException("数字格式错误")
                exponent = sign * src.substring(expStart, i).toInt()
            }
            return try {
                BigDecimal(digits, MC).scaleByPowerOfTen(exponent)
            } catch (e: NumberFormatException) {
                throw ParserException("数字格式错误")
            }
        }

        private fun primary(): BigDecimal {
            val c = peek()
            if (c == '(') {
                i++
                val inner = sum()
                expectClose()
                return inner
            }
            if (c == '√') {
                i++
                return sqrtOf(unary())
            }
            if (c == 'π') {
                i++
                return PI
            }
            if (c == 'e') {
                i++
                return E
            }
            if (c.isLetter()) {
                val name = functionName() ?: throw ParserException("未知函数")
                if (peek() != '(') throw ParserException("$name 后面要跟括号")
                i++
                val argument = sum()
                expectClose()
                return callFunction(name, argument)
            }
            return number()
        }

        private fun functionName(): String? {
            val names = listOf("sin", "cos", "tan", "ln", "log", "sqrt")
            for (name in names) {
                if (src.startsWith(name, i)) {
                    i += name.length
                    return name
                }
            }
            return null
        }

        private fun expectClose() {
            if (peek() == ')') {
                i++
            } else if (!allowUnclosed) {
                throw ParserException("括号没有闭合")
            }
        }

        private fun callFunction(name: String, argument: BigDecimal): BigDecimal {
            val x = argument.toDouble()
            val result = when (name) {
                "sin" -> sin(Math.toRadians(x))
                "cos" -> cos(Math.toRadians(x))
                "tan" -> tan(Math.toRadians(x))
                "ln" -> ln(x)
                "log" -> log10(x)
                "sqrt" -> sqrt(x)
                else -> throw ParserException("未知函数 $name")
            }
            if (!result.isFinite()) throw ParserException("$name 的定义域不对")
            return BigDecimal.valueOf(result).round(TRANSCENDENTAL).stripTrailingZeros()
        }

        private fun sqrtOf(value: BigDecimal): BigDecimal {
            if (value.signum() < 0) throw ParserException("负数不能开平方")
            return BigDecimal.valueOf(sqrt(value.toDouble())).round(TRANSCENDENTAL).stripTrailingZeros()
        }

        /** 后缀：% 除以 100（可叠），! 阶乘。 */
        private fun postfix(): BigDecimal {
            var value = primary()
            while (peek() == '%' || peek() == '!') {
                if (peek() == '%') {
                    i++
                    value = value.divide(HUNDRED, MC)
                } else {
                    i++
                    value = factorial(value)
                }
            }
            return value
        }

        private fun factorial(value: BigDecimal): BigDecimal {
            val stripped = value.stripTrailingZeros()
            if (stripped.scale() > 0) throw ParserException("阶乘只能用于整数")
            if (stripped.signum() < 0) throw ParserException("阶乘只能用于非负整数")
            val n = try {
                stripped.intValueExact()
            } catch (e: ArithmeticException) {
                throw ParserException("阶乘的数太大了")
            }
            if (n > 5000) throw ParserException("阶乘的数太大了")
            var acc = BigDecimal.ONE
            for (k in 2..n) acc = acc.multiply(BigDecimal(k), MC)
            return acc
        }

        /** 幂：右结合，指数可以是负数或小数。 */
        private fun power(): BigDecimal {
            val base = postfix()
            if (peek() != '^') return base
            i++
            val exponent = unary()
            return guard(pow(base, exponent))
        }

        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val stripped = exponent.stripTrailingZeros()
            val isIntegral = stripped.scale() <= 0
            if (isIntegral) {
                val n = try {
                    stripped.intValueExact()
                } catch (e: ArithmeticException) {
                    0
                }
                if (n in 0..100000) {
                    return if (n == 0) BigDecimal.ONE else base.pow(n, MC)
                }
            }
            val result = base.toDouble().pow(exponent.toDouble())
            if (!result.isFinite()) throw ParserException("幂的结果超出范围")
            return BigDecimal.valueOf(result).round(TRANSCENDENTAL).stripTrailingZeros()
        }

        private fun unary(): BigDecimal {
            if (peek() == '-') {
                i++
                return unary().negate()
            }
            return power()
        }

        private fun term(): BigDecimal {
            var value = unary()
            while (true) {
                when {
                    peek() == '*' -> {
                        i++
                        value = guard(value.multiply(unary(), MC))
                    }
                    peek() == '/' -> {
                        i++
                        val rhs = unary()
                        if (rhs.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("divide by zero")
                        value = guard(value.divide(rhs, MC))
                    }
                    // 隐式乘法：2(3+1)、2π、3√4、2.5(4) —— e 不参与，避免和"1e5"这种写法混淆
                    peek() == '(' || peek() == '√' || peek() == 'π' || peek().isDigit() || peek() == '.' -> {
                        value = guard(value.multiply(unary(), MC))
                    }
                    else -> return value
                }
            }
        }

        private fun sum(): BigDecimal {
            var value = term()
            while (peek() == '+' || peek() == '-') {
                val op = src[i]
                i++
                val termStart = i
                val rhs = term()
                val relative = percentMode == PercentMode.RELATIVE &&
                    i > termStart && src[i - 1] == '%'
                value = when {
                    op == '+' && relative -> value.add(value.multiply(rhs, MC), MC)
                    op == '+' -> value.add(rhs, MC)
                    relative -> value.subtract(value.multiply(rhs, MC), MC)
                    else -> value.subtract(rhs, MC)
                }
                value = guard(value)
            }
            return value
        }

        private companion object {
            /** π 取 34 位，和 DECIMAL128 的有效位数对齐。 */
            val PI: BigDecimal = BigDecimal("3.141592653589793238462643383279503")
            val E: BigDecimal = BigDecimal("2.718281828459045235360287471352662")
        }
    }
}
