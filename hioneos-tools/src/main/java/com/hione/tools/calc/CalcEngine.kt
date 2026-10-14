package com.hione.tools.calc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Expression evaluator on BigDecimal.
 *
 * Why not double: 0.1 + 0.2 must print 0.3, and 1 / 3 * 3 must print 1. Everything is computed
 * at [WORK] significant digits and only rounded to what fits the display at the very end, so
 * intermediate rounding never leaks into the answer. Transcendental functions are evaluated by
 * series at the same precision instead of going through Math.sin and friends, otherwise
 * sin(30 deg) would come back as 0.49999999999999994.
 *
 * Grammar, loosest binding first:
 *   expr    := term (('+' | '−') term)*
 *   term    := unary (('×' | '÷') unary | <implicit ×> unary)*
 *   unary   := ('−' | '+') unary | power
 *   power   := postfix ('^' unary)?            right associative, so 2^3^2 = 2^9
 *   postfix := primary ('!' | '%')*
 *   primary := number | π | e | '(' expr ')' | func arg | '√' postfix
 * so −2^2 = −4 like on paper, and 2π, 3(4) and 2sin(30) multiply implicitly.
 *
 * Percent follows what people expect from a pocket calculator: a bare x% is x/100, but as the
 * right side of + or − it is relative to the left side, so 200 + 10% = 220 (and 50 × 10% = 5).
 */
object CalcEngine {
    /** Working precision. 60 digits leaves ~28 guard digits beyond the 32 we ever show. */
    const val WORK = 60
    val MC = MathContext(WORK, RoundingMode.HALF_EVEN)
    private val MC_GUARD = MathContext(WORK + 10, RoundingMode.HALF_EVEN)

    // 110 digits of pi; enough to reduce trig arguments up to ~1e40 without visible error.
    val PI = BigDecimal(
        "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706798214808651"
    )
    private val TWO = BigDecimal(2)
    private val HALF_PI = PI.divide(TWO, MC_GUARD)
    private val TWO_PI = PI.multiply(TWO)
    val E: BigDecimal by lazy { exp(BigDecimal.ONE) }
    private val LN10: BigDecimal by lazy { lnRaw(BigDecimal.TEN) }

    class CalcException(message: String) : Exception(message)

    private fun fail(msg: String): Nothing = throw CalcException(msg)

    // ---------------------------------------------------------------- tokens

    private sealed class Tok {
        data class Num(val v: BigDecimal) : Tok()
        data class Op(val c: Char) : Tok()          // + − × ÷ ^ ! %
        object LParen : Tok()
        object RParen : Tok()
        data class Fn(val name: String) : Tok()
        data class Const(val v: BigDecimal) : Tok()
        object Sqrt : Tok()
    }

    private val FUNCS = listOf("asin", "acos", "atan", "sin", "cos", "tan", "ln", "log")

    private fun tokenize(src: String): List<Tok> {
        val s = src.replace(" ", "")
            .replace('*', '×').replace('/', '÷').replace('-', '−').replace('x', '×')
            .replace("pi", "π")
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    // Scientific notation as we print it: 1.5E+30 / 2E−7
                    if (i < s.length && s[i] == 'E') {
                        var j = i + 1
                        if (j < s.length && (s[j] == '+' || s[j] == '−')) j++
                        if (j < s.length && s[j].isDigit()) {
                            while (j < s.length && s[j].isDigit()) j++
                            i = j
                        }
                    }
                    val raw = s.substring(st, i).replace('−', '-')
                    if (raw.count { it == '.' } > 1 || raw == ".") fail("Check the number")
                    out += Tok.Num(BigDecimal(if (raw.startsWith(".")) "0$raw" else raw))
                    continue
                }
                c == '(' -> out += Tok.LParen
                c == ')' -> out += Tok.RParen
                c in "+−×÷^!%" -> out += Tok.Op(c)
                c == 'π' -> out += Tok.Const(PI)
                c == '√' -> out += Tok.Sqrt
                c == 'e' -> out += Tok.Const(E)
                c.isLetter() -> {
                    val f = FUNCS.firstOrNull { s.startsWith(it, i) } ?: fail("Check the expression")
                    out += Tok.Fn(f)
                    i += f.length
                    continue
                }
                else -> fail("Check the expression")
            }
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- parser

    private class Parser(val t: List<Tok>, val degrees: Boolean) {
        var p = 0
        /** Set by term() when the term was exactly `x%`: holds x/100 so expr() can rescale. */
        var barePercent: BigDecimal? = null

        fun peek(): Tok? = t.getOrNull(p)
        fun isOp(c: Char) = (peek() as? Tok.Op)?.c == c

        fun parse(): BigDecimal {
            if (t.isEmpty()) fail("Empty")
            val v = expr()
            if (p < t.size) fail("Check the expression")
            return v
        }

        fun expr(): BigDecimal {
            var v = term()
            while (isOp('+') || isOp('−')) {
                val op = (peek() as Tok.Op).c; p++
                barePercent = null
                var r = term()
                barePercent?.let { pct -> r = v.multiply(pct, MC) }
                v = if (op == '+') v.add(r, MC) else v.subtract(r, MC)
            }
            barePercent = null
            return v
        }

        fun term(): BigDecimal {
            barePercent = null
            var v = unary()
            var single = true
            while (true) {
                val tk = peek()
                v = when {
                    tk is Tok.Op && tk.c == '×' -> { p++; single = false; v.multiply(unary(), MC) }
                    tk is Tok.Op && tk.c == '÷' -> {
                        p++; single = false
                        val d = unary()
                        if (d.signum() == 0) fail("Can't divide by zero")
                        v.divide(d, MC)
                    }
                    tk is Tok.Num || tk is Tok.Const || tk is Tok.LParen || tk is Tok.Fn || tk is Tok.Sqrt -> {
                        single = false; v.multiply(unary(), MC)
                    }
                    else -> break
                }
            }
            if (!single) barePercent = null
            return v
        }

        fun unary(): BigDecimal = when {
            isOp('−') -> { p++; barePercent = null; unary().negate() }
            isOp('+') -> { p++; unary() }
            else -> power()
        }

        fun power(): BigDecimal {
            val base = postfix()
            if (isOp('^')) {
                p++
                barePercent = null
                return pow(base, unary())
            }
            return base
        }

        fun postfix(): BigDecimal {
            var v = primary()
            var pct = false
            while (true) {
                when {
                    isOp('!') -> { p++; v = factorial(v); pct = false }
                    isOp('%') -> { p++; v = v.divide(BigDecimal(100), MC); pct = true }
                    else -> break
                }
            }
            barePercent = if (pct) v else null
            return v
        }

        fun primary(): BigDecimal {
            return when (val tk = peek() ?: fail("Incomplete")) {
                is Tok.Num -> { p++; tk.v }
                is Tok.Const -> { p++; tk.v }
                is Tok.LParen -> {
                    p++
                    val v = expr()
                    // Missing closing parentheses at the end are forgiven (auto-closed).
                    if (peek() is Tok.RParen) p++ else if (p < t.size) fail("Check the parentheses")
                    v
                }
                // √−4 should say "not a real number", not "check the expression".
                is Tok.Sqrt -> { p++; sqrt(if (isOp('−')) unary() else postfixArg()) }
                is Tok.Fn -> {
                    p++
                    val arg = if (peek() is Tok.LParen) primary() else postfixArg()
                    applyFn(tk.name, arg)
                }
                else -> fail("Check the expression")
            }
        }

        private fun postfixArg(): BigDecimal {
            val saved = barePercent
            return postfix().also { barePercent = saved }
        }

        fun applyFn(name: String, x: BigDecimal): BigDecimal {
            val toRad = { v: BigDecimal -> if (degrees) v.multiply(PI, MC_GUARD).divide(BigDecimal(180), MC_GUARD) else v }
            val fromRad = { v: BigDecimal -> if (degrees) v.multiply(BigDecimal(180), MC_GUARD).divide(PI, MC_GUARD) else v }
            return when (name) {
                "sin" -> sin(toRad(x))
                "cos" -> cos(toRad(x))
                "tan" -> {
                    val r = toRad(x)
                    val c = cos(r)
                    if (c.signum() == 0) fail("Undefined")
                    sin(r).divide(c, MC)
                }
                "asin" -> fromRad(asin(x))
                "acos" -> fromRad(acos(x))
                "atan" -> fromRad(atan(x))
                "ln" -> ln(x)
                "log" -> log10(x)
                else -> fail("Unknown function")
            }
        }
    }

    /** Evaluate. Throws [CalcException] with a short, user-facing reason. */
    fun evaluate(expression: String, degrees: Boolean): BigDecimal {
        val tokens = tokenize(expression)
        return snap(Parser(tokens, degrees).parse())
    }

    /** Values within ~1e-50 of an integer are the integer; this is what makes sin(π) print 0
     *  and acos(−1) × 180 ÷ π print 180 even though π itself is only known to 110 digits. */
    private fun snap(v: BigDecimal): BigDecimal {
        if (v.signum() == 0) return BigDecimal.ZERO
        val r = v.setScale(0, RoundingMode.HALF_EVEN)
        val diff = v.subtract(r).abs()
        return if (diff.signum() != 0 && diff < BigDecimal("1E-50") && v.abs() < BigDecimal("1E9")) r else v
    }

    // ---------------------------------------------------------------- formatting

    /**
     * Render for display: at most [maxSig] significant digits, plain notation for "normal"
     * magnitudes, scientific (1.23E+40, re-parseable) outside them.
     */
    fun format(v: BigDecimal, maxSig: Int = 20): String {
        if (v.signum() == 0) return "0"
        val r = v.round(MathContext(maxSig, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val exp = r.precision() - r.scale() - 1 // power of ten of the leading digit
        val s = if (exp in -8 until maxSig) {
            r.toPlainString()
        } else {
            val unscaled = r.unscaledValue().abs().toString()
            val mant = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            (if (r.signum() < 0) "-" else "") + mant + "E" + (if (exp >= 0) "+" else "") + exp
        }
        return s.replace('-', '−')
    }

    /** Groups the integer part with thin commas for reading; never used for re-parsing. */
    fun group(s: String): String {
        if (s.contains('E')) return s
        val neg = s.startsWith("−")
        val body = if (neg) s.substring(1) else s
        val dot = body.indexOf('.')
        val ip = if (dot >= 0) body.substring(0, dot) else body
        val fp = if (dot >= 0) body.substring(dot) else ""
        val grouped = ip.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "−" else "") + grouped + fp
    }

    // ---------------------------------------------------------------- math

    private val LIMIT = BigDecimal("1E100000")

    private fun guard(v: BigDecimal): BigDecimal {
        if (v.abs() > LIMIT) fail("Too big")
        return v
    }

    fun pow(x: BigDecimal, y: BigDecimal): BigDecimal {
        val yi = runCatching { y.stripTrailingZeros().toBigIntegerExact() }.getOrNull()
        if (yi != null && yi.abs() <= BigInteger.valueOf(99_999)) {
            val n = yi.toInt()
            if (x.signum() == 0) {
                if (n < 0) fail("Can't divide by zero")
                return if (n == 0) BigDecimal.ONE else BigDecimal.ZERO
            }
            // Cheap overflow guard before the real multiplication.
            val digits = (x.precision() - x.scale()).toLong()
            if (n > 0 && digits * n > 100_000) fail("Too big")
            val r = x.pow(kotlin.math.abs(n), MC)
            return guard(if (n < 0) BigDecimal.ONE.divide(r, MC) else r)
        }
        if (x.signum() == 0) return if (y.signum() > 0) BigDecimal.ZERO else fail("Can't divide by zero")
        if (x.signum() < 0) {
            // Odd roots of negatives are real: (−8)^(1/3) = −2. Recognize y = 1/odd.
            val inv = BigDecimal.ONE.divide(y, MC).round(MathContext(WORK - 10))
            val n = runCatching { inv.stripTrailingZeros().toBigIntegerExact() }.getOrNull()
            if (n != null && n.testBit(0)) return pow(x.negate(), y).negate()
            fail("Not a real number")
        }
        val lnx = ln(x)
        if (lnx.multiply(y).abs() > BigDecimal(230_000)) fail("Too big")
        return exp(lnx.multiply(y, MC_GUARD))
    }

    fun factorial(x: BigDecimal): BigDecimal {
        val n = runCatching { x.stripTrailingZeros().toBigIntegerExact() }.getOrNull()
            ?: fail("Factorial needs a whole number")
        if (n.signum() < 0) fail("Factorial needs a whole number")
        if (n > BigInteger.valueOf(3000)) fail("Too big")
        var r = BigInteger.ONE
        for (i in 2..n.toInt()) r = r.multiply(BigInteger.valueOf(i.toLong()))
        return BigDecimal(r)
    }

    fun sqrt(x: BigDecimal): BigDecimal {
        if (x.signum() < 0) fail("Not a real number")
        if (x.signum() == 0) return BigDecimal.ZERO
        // Seed from double where it fits, else from the exponent; Newton doubles the digits.
        val d = x.toDouble()
        var g = if (d.isFinite() && d > 0) BigDecimal(Math.sqrt(d)) else BigDecimal.ONE.scaleByPowerOfTen((x.precision() - x.scale()) / 2)
        val tol = BigDecimal.ONE.scaleByPowerOfTen(-(WORK + 5) + (g.precision() - g.scale()))
        for (i in 0 until 200) {
            val next = g.add(x.divide(g, MC_GUARD)).divide(TWO, MC_GUARD)
            if (next.subtract(g).abs() <= tol) { g = next; break }
            g = next
        }
        // Exact squares come out exact: √144 = 12, not 11.9999...
        val rounded = g.round(MathContext(WORK - 5))
        val ri = rounded.setScale(0, RoundingMode.HALF_EVEN)
        if (ri.multiply(ri).compareTo(x) == 0) return ri
        return g.round(MC)
    }

    fun exp(x: BigDecimal): BigDecimal {
        if (x > BigDecimal(230_000)) fail("Too big")
        if (x < BigDecimal(-230_000)) return BigDecimal.ZERO
        // Halve the argument until it is small, sum the series, then square back up.
        var k = 0
        var r = x
        while (r.abs() > BigDecimal("0.01")) { r = r.divide(TWO, MC_GUARD); k++ }
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        val eps = BigDecimal.ONE.scaleByPowerOfTen(-(WORK + 8))
        var n = 1
        while (true) {
            term = term.multiply(r, MC_GUARD).divide(BigDecimal(n), MC_GUARD)
            sum = sum.add(term, MC_GUARD)
            if (term.abs() < eps) break
            n++
        }
        repeat(k) { sum = sum.multiply(sum, MC_GUARD) }
        return sum.round(MC)
    }

    fun ln(x: BigDecimal): BigDecimal {
        if (x.signum() <= 0) fail("Not a real number")
        if (x.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ZERO
        return lnRaw(x).round(MC)
    }

    private fun lnRaw(x: BigDecimal): BigDecimal {
        // x = m * 10^k with m in [1, 10): ln x = ln m + k ln 10.
        val k = x.precision() - x.scale() - 1
        var m = x.scaleByPowerOfTen(-k)
        val lnTen = if (k != 0) (if (x.compareTo(BigDecimal.TEN) == 0) null else LN10) else null
        // Square-root m toward 1 so the atanh series converges in a few dozen terms.
        var mult = 1
        while (m.subtract(BigDecimal.ONE).abs() > BigDecimal("0.05")) {
            m = sqrt(m).round(MC_GUARD); mult *= 2
        }
        val z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC_GUARD)
        val z2 = z.multiply(z, MC_GUARD)
        var term = z
        var sum = BigDecimal.ZERO
        var n = 1
        val eps = BigDecimal.ONE.scaleByPowerOfTen(-(WORK + 8))
        while (true) {
            val add = term.divide(BigDecimal(n), MC_GUARD)
            sum = sum.add(add, MC_GUARD)
            if (add.abs() < eps) break
            term = term.multiply(z2, MC_GUARD)
            n += 2
        }
        var r = sum.multiply(BigDecimal(2L * mult), MC_GUARD)
        if (k != 0) {
            // ln(10) itself: m = 1, k = 1, so it is lnRaw of 1 (0) plus k*ln10, which would
            // recurse. Compute ln 10 as 2*ln(sqrt 10) through the series path instead.
            val l10 = lnTen ?: run {
                val s = sqrt(BigDecimal.TEN)
                lnRaw(s).multiply(TWO, MC_GUARD)
            }
            r = r.add(l10.multiply(BigDecimal(k), MC_GUARD), MC_GUARD)
        }
        return r
    }

    fun log10(x: BigDecimal): BigDecimal {
        if (x.signum() <= 0) fail("Not a real number")
        // Exact powers of ten give exact answers.
        val st = x.stripTrailingZeros()
        if (st.unscaledValue() == BigInteger.ONE) return BigDecimal(-st.scale())
        return ln(x).divide(LN10, MC)
    }

    private fun reduceAngle(x: BigDecimal): BigDecimal {
        if (x.abs() > BigDecimal("1E40")) fail("Too big")
        var r = x.remainder(TWO_PI, MC_GUARD)
        if (r > PI) r = r.subtract(TWO_PI)
        if (r < PI.negate()) r = r.add(TWO_PI)
        return r
    }

    private fun trigSeries(x: BigDecimal, startTerm: BigDecimal, startN: Int): BigDecimal {
        val x2 = x.multiply(x, MC_GUARD)
        var term = startTerm
        var sum = startTerm
        var n = startN
        val eps = BigDecimal.ONE.scaleByPowerOfTen(-(WORK + 8))
        while (true) {
            term = term.multiply(x2, MC_GUARD).divide(BigDecimal((n + 1).toLong() * (n + 2)), MC_GUARD).negate()
            sum = sum.add(term, MC_GUARD)
            if (term.abs() < eps) break
            n += 2
        }
        return sum
    }

    /** Results smaller than this after a trig call are rounding noise from π, not a value. */
    private val TRIG_ZERO = BigDecimal("1E-48")

    fun sin(x: BigDecimal): BigDecimal {
        val r = reduceAngle(x)
        val v = trigSeries(r, r, 1)
        return if (v.abs() < TRIG_ZERO) BigDecimal.ZERO else v.round(MC)
    }

    fun cos(x: BigDecimal): BigDecimal {
        val r = reduceAngle(x)
        val v = trigSeries(r, BigDecimal.ONE, 0)
        return if (v.abs() < TRIG_ZERO) BigDecimal.ZERO else v.round(MC)
    }

    fun atan(x: BigDecimal): BigDecimal {
        if (x.signum() == 0) return BigDecimal.ZERO
        if (x.abs() > BigDecimal.ONE) {
            val inner = atan(BigDecimal.ONE.divide(x, MC_GUARD))
            val hp = if (x.signum() > 0) HALF_PI else HALF_PI.negate()
            return hp.subtract(inner, MC)
        }
        // Halve twice with atan(x) = 2 atan(x / (1 + sqrt(1 + x^2))), then the series.
        var y = x
        var mult = 1
        repeat(3) {
            y = y.divide(BigDecimal.ONE.add(sqrt(BigDecimal.ONE.add(y.multiply(y, MC_GUARD)))), MC_GUARD)
            mult *= 2
        }
        val y2 = y.multiply(y, MC_GUARD)
        var term = y
        var sum = BigDecimal.ZERO
        var n = 1
        var sign = 1
        val eps = BigDecimal.ONE.scaleByPowerOfTen(-(WORK + 8))
        while (true) {
            val add = term.divide(BigDecimal(n), MC_GUARD)
            sum = if (sign > 0) sum.add(add, MC_GUARD) else sum.subtract(add, MC_GUARD)
            if (add.abs() < eps) break
            term = term.multiply(y2, MC_GUARD)
            n += 2; sign = -sign
        }
        return sum.multiply(BigDecimal(mult), MC)
    }

    fun asin(x: BigDecimal): BigDecimal {
        val c = x.abs().compareTo(BigDecimal.ONE)
        if (c > 0) fail("Not a real number")
        if (c == 0) return if (x.signum() > 0) HALF_PI.round(MC) else HALF_PI.negate().round(MC)
        val d = sqrt(BigDecimal.ONE.subtract(x.multiply(x, MC_GUARD)))
        return atan(x.divide(d, MC_GUARD))
    }

    fun acos(x: BigDecimal): BigDecimal {
        if (x.abs() > BigDecimal.ONE) fail("Not a real number")
        return HALF_PI.subtract(asin(x), MC)
    }
}
