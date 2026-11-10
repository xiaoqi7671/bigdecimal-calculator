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
    expectValue("1÷3", "0.333333333333")
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
    expectValue("1÷3", "0.333333333333", partial = true)
    expectValue("1÷0", "错误: 不能除以 0", partial = true)
    expectValue("99999999999999+1", "100000000000000")
    expectValue("999999999999999+1", "1E+15")
    expectValue("1÷1000000000", "0.000000001")
    expectValue("1÷10000000000", "1E-10")
    println(if (failed == 0) "全部通过" else "失败 $failed 项")
    if (failed != 0) kotlin.system.exitProcess(1)
}
