package com.xiaoqi.calculator

/**
 * 脱离 Android 的引擎自检：kotlinc 直接编译 Calculator.kt + 本文件后运行。
 * 用法：kotlinc Calculator.kt EngineCheck.kt -include-runtime -d check.jar && java -jar check.jar
 */

private var failed = 0

private fun expectValue(input: String, expected: String, partial: Boolean = false) {
    val outcome = Calculator.evaluate(input, partial)
    val actual = when (outcome) {
        is Calculator.Outcome.Value -> Calculator.format(outcome.value)
        is Calculator.Outcome.Failure -> "错误: ${outcome.reason}"
    }
    report(input, expected, actual, partial)
}

private fun expectError(input: String, expected: String, partial: Boolean = false) {
    val outcome = Calculator.evaluate(input, partial)
    val actual = when (outcome) {
        is Calculator.Outcome.Value -> "值: ${Calculator.format(outcome.value)}"
        is Calculator.Outcome.Failure -> outcome.reason
    }
    report(input, expected, actual, partial)
}

private fun report(input: String, expected: String, actual: String, partial: Boolean) {
    val ok = expected == actual
    if (!ok) failed++
    val mode = if (partial) "预览" else "求值"
    println("${if (ok) "PASS" else "FAIL"} [$mode] ${input.padEnd(14)} 期望=$expected 实际=$actual")
}

fun main() {
    expectValue("1+2", "3")
    expectValue("0.1+0.2", "0.3")
    expectValue("1÷3", "0.3333333333333333333333333333333333")
    expectValue("2.5×4", "10")
    expectValue("1−2−3", "-4")
    expectValue("5×−3", "-15")
    expectValue("5−−3", "8")
    expectValue("−3×2", "-6")
    expectValue("(1+2)×3", "9")
    expectValue("2×(3+(4−1))", "12")
    expectValue("((2+3))", "5")
    expectValue("50%", "0.5")
    expectValue("50%%", "0.005")
    expectValue("100×10%", "10")
    expectValue("9÷0", "错误: 不能除以 0")
    expectValue("()", "错误: 这里需要一个数字")
    expectValue("1++2", "错误: 这里需要一个数字")
    expectValue("((1+2)", "错误: 括号没有闭合")
    expectValue("1e5", "错误: 多余的内容")
    expectValue("5+", "5", partial = true)
    expectValue("(1+2", "3", partial = true)
    expectValue("2×(", "2", partial = true)
    expectValue("1÷3", "0.3333333333333333333333333333333333", partial = true)
    expectValue("1÷0", "错误: 不能除以 0", partial = true)
    expectValue("99999999999999+1", "100000000000000")
    expectValue("999999999999999+1", "1000000000000000")
    expectValue("1÷1000000000", "0.000000001")
    expectValue("1÷10000000000", "1E-10")
    // 大数 / 精度 / 科学键盘用到的算子
    expectValue("2^100", "1267650600228229401496703205376")
    expectValue("2^10", "1024")
    expectValue("2^0.5", "1.4142135623731")
    expectValue("√2", "1.4142135623731")
    expectValue("√(−4)", "错误: 负数不能开平方")
    expectValue("sin(30)", "0.5")
    expectValue("cos(60)", "0.5")
    expectValue("tan(45)", "1")
    expectValue("cos(0)", "1")
    expectValue("ln(e)", "1")
    expectValue("log(1000)", "3")
    expectValue("5!", "120")
    expectValue("0!", "1")
    expectValue("π", "3.141592653589793238462643383279503")
    expectValue("10^1001", "错误: 结果超出范围")
    expectValue("1÷3×3", "0.9999999999999999999999999999999999")
    expectValue("2(3+1)", "8")
    expectValue("3√4", "6")
    expectValue("2π", "6.283185307179586476925286766559006")
    expectValue("1E-10", "1E-10")
    expectValue("2E3", "2000")
    println(if (failed == 0) "全部通过" else "失败 $failed 项")
    if (failed != 0) kotlin.system.exitProcess(1)
}
