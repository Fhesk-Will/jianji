package com.jianji.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * 金额工具：存储单位为「分」（Long），展示时才格式化。
 *
 * 金额输入支持四则算式（如 "12.5+3.2*2"）：
 *  - [append] 承载键盘输入（数字 / 小数点 / + - * /），带合法性约束
 *  - [evalExprMinor] 求值：乘除优先于加减（标准优先级，无括号），
 *    BigDecimal 全程精确，除法中间保留 10 位、最终四舍五入到分
 */
object Money {

    private val grouped = DecimalFormat("#,##0.00")

    /** 上限 999,999,999.99 元（与整数 9 位输入上限一致） */
    private const val MAX_MINOR = 99_999_999_999L

    private const val OPS = "+-*/"

    // ---------- 展示格式化 ----------

    /** 分 -> "1,234.56"（精确，走 BigDecimal 避免 double 误差） */
    fun format(minor: Long): String = grouped.format(BigDecimal.valueOf(minor, 2))

    /** 分 -> "¥1,234.56" */
    fun yuan(minor: Long): String = "¥" + format(minor)

    /** 带符号：支出 -¥32.00 / 收入 +¥11,600.00 */
    fun signed(minor: Long, isExpense: Boolean): String =
        (if (isExpense) "-¥" else "+¥") + format(abs(minor))

    /** CSV 用：无货币符号、无千分位，如 32.00 / -12.50 */
    fun csvAmount(minor: Long): String {
        val a = abs(minor)
        val sign = if (minor < 0) "-" else ""
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    // ---------- 算式输入 ----------

    /** 是否包含运算符（决定展示样式：纯数字大字 vs 算式+实时结果） */
    fun hasOperator(input: String): Boolean = input.any { it in OPS }

    /** 当前操作数：最后一个运算符之后的部分 */
    private fun tailOperand(input: String): String {
        var i = input.length - 1
        while (i >= 0 && input[i] !in OPS) i--
        return input.substring(i + 1)
    }

    /**
     * 键盘输入追加一个字符。
     * 规则：小数 ≤2 位、单个整数 ≤9 位；"0" 后输数字自动替换；
     * 连续运算符自动替换；表达式总长 ≤ 40。
     */
    fun append(input: String, ch: Char): String {
        when {
            ch.isDigit() -> {
                val cur = tailOperand(input)
                if (cur == "0") return input.dropLast(1) + ch
                if (cur.contains('.')) {
                    if (cur.length - cur.indexOf('.') - 1 >= 2) return input
                } else if (cur.length >= 9) {
                    return input
                }
                if (input.length >= 40) return input
                return input + ch
            }
            ch == '.' -> {
                if (tailOperand(input).contains('.')) return input
                if (input.isEmpty()) return "0."
                return input + ch
            }
            ch in OPS -> {
                if (input.isEmpty()) return input           // 不允许负数开头（类型已定收支）
                if (input.last() in OPS) return input.dropLast(1) + ch
                return input + ch
            }
            else -> return input
        }
    }

    fun backspace(input: String): String =
        if (input.isEmpty()) input else input.dropLast(1)

    // ---------- 求值 ----------

    /**
     * 求值算式 -> 分。
     * 返回 null：表达式为空 / 结尾是运算符（不完整）/ 除零 /
     * 结果 ≤ 0 / 超出金额上限。
     */
    fun evalExprMinor(input: String): Long? {
        if (input.isEmpty()) return null

        // 1. 分词
        val operands = mutableListOf<String>()
        val ops = mutableListOf<Char>()
        val sb = StringBuilder()
        input.forEach { c ->
            if (c in OPS) {
                operands.add(sb.toString())
                ops.add(c)
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        operands.add(sb.toString())
        if (operands.last().isEmpty()) return null

        // 2. 操作数转 BigDecimal
        val nums = ArrayList<BigDecimal>(operands.size)
        operands.forEach { s ->
            nums.add(toBigDecimal(s) ?: return null)
        }

        // 3. 第一遍：折叠 × ÷（左结合）
        val folded = mutableListOf(nums[0])
        val foldedOps = mutableListOf<Char>()
        for (i in ops.indices) {
            when (ops[i]) {
                '*' -> folded[folded.lastIndex] = folded.last() * nums[i + 1]
                '/' -> {
                    if (nums[i + 1].signum() == 0) return null
                    folded[folded.lastIndex] =
                        folded.last().divide(nums[i + 1], 10, RoundingMode.HALF_UP)
                }
                else -> {
                    foldedOps.add(ops[i])
                    folded.add(nums[i + 1])
                }
            }
        }

        // 4. 第二遍：加减
        var acc = folded[0]
        for (i in foldedOps.indices) {
            acc = if (foldedOps[i] == '+') acc + folded[i + 1] else acc - folded[i + 1]
        }

        val result = acc.setScale(2, RoundingMode.HALF_UP)
        if (result.signum() <= 0) return null
        val minor = result.movePointRight(2).longValueExact()
        return if (minor <= MAX_MINOR) minor else null
    }

    /** "12." / "12.5" -> BigDecimal；非法返回 null */
    private fun toBigDecimal(s: String): BigDecimal? {
        if (s.isEmpty()) return null
        if (s.count { it == '.' } > 1) return null
        val t = s.trimEnd('.')
        if (t.isEmpty()) return null
        if (t.any { !it.isDigit() && it != '.' }) return null
        if (t.substringBefore('.').length > 9) return null
        if (t.substringAfter('.', "").length > 2) return null
        return runCatching { BigDecimal(t) }.getOrNull()
    }

    /** 编辑回填：分 -> 键盘输入串（"32" / "32.5" / "32.55"） */
    fun minorToInput(minor: Long): String {
        val a = abs(minor)
        val sign = if (minor < 0) "-" else ""
        val frac = (a % 100).toString().padStart(2, '0').trimEnd('0')
        return "$sign${a / 100}" + if (frac.isEmpty()) "" else ".$frac"
    }
}
