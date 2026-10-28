package com.fieldbook.tracker.traits.calculated

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Parses and evaluates the formula of a calculated trait.
 *
 * A formula references other traits of the same field by name. Simple names can be written
 * directly (`Altura / 100`); names with spaces or symbols go between braces
 * (`{Peso parcela} / {Area util}`).
 *
 * Supported syntax, from lowest to highest precedence:
 * - `||`, `&&`                 logical or / and, result 1 or 0
 * - `<  <=  >  >=  ==  =  !=`  comparisons, result 1 or 0
 * - `+  -`
 * - `*  /  %`
 * - unary `-`, `+`, `!`
 * - `^` (also `**`)            power, right associative
 * - numbers, `(...)`, trait references and function calls
 *
 * Functions: abs, sqrt, exp, ln, log (base 10), log10, round(x[, digits]), floor, ceil, sign,
 * pow(x, y), min(...), max(...), sum(...), mean(...), if(condition, a, b),
 * sin, cos, tan, asin, acos, atan.
 *
 * This class has no Android dependencies so it can be unit tested on the JVM.
 */
class FormulaEvaluator private constructor(
    val formula: String,
    private val root: Node
) {

    /** Thrown when the formula text is not valid. [position] is a 0-based character index. */
    class ParseException(message: String, val position: Int) : Exception(message)

    /** Thrown when a referenced trait has no usable value for the current entry. */
    class MissingValueException(val traitName: String) :
        Exception("Missing value for trait '$traitName'")

    /** Thrown when the formula cannot produce a finite number (division by zero, sqrt(-1)...). */
    class EvaluationException(message: String) : Exception(message)

    /** Names of every trait the formula references, in order of first appearance. */
    val references: List<String> by lazy {
        val names = LinkedHashSet<String>()
        collectReferences(root, names)
        names.toList()
    }

    /**
     * Evaluates the formula.
     *
     * @param resolve returns the numeric value of a referenced trait, or null when it has none.
     * @throws MissingValueException when [resolve] returns null for a referenced trait.
     * @throws EvaluationException when the result is not a finite number.
     */
    fun evaluate(resolve: (String) -> Double?): Double {
        val result = eval(root, resolve)
        if (result.isNaN() || result.isInfinite()) {
            throw EvaluationException("Result is not a finite number")
        }
        return result
    }

    private fun collectReferences(node: Node, into: MutableSet<String>) {
        when (node) {
            is Node.Ref -> into.add(node.name)
            is Node.Unary -> collectReferences(node.operand, into)
            is Node.Binary -> {
                collectReferences(node.left, into)
                collectReferences(node.right, into)
            }
            is Node.Call -> node.args.forEach { collectReferences(it, into) }
            is Node.Num -> Unit
        }
    }

    private fun eval(node: Node, resolve: (String) -> Double?): Double = when (node) {
        is Node.Num -> node.value
        is Node.Ref -> resolve(node.name) ?: throw MissingValueException(node.name)
        is Node.Unary -> {
            val v = eval(node.operand, resolve)
            when (node.op) {
                "-" -> -v
                "+" -> v
                "!" -> bool(v == 0.0)
                else -> error("unknown unary ${node.op}")
            }
        }
        is Node.Binary -> evalBinary(node, resolve)
        is Node.Call -> evalCall(node, resolve)
    }

    private fun evalBinary(node: Node.Binary, resolve: (String) -> Double?): Double {
        // short circuit so an unused branch does not demand its inputs
        if (node.op == "&&") {
            return bool(eval(node.left, resolve) != 0.0 && eval(node.right, resolve) != 0.0)
        }
        if (node.op == "||") {
            return bool(eval(node.left, resolve) != 0.0 || eval(node.right, resolve) != 0.0)
        }
        val l = eval(node.left, resolve)
        val r = eval(node.right, resolve)
        return when (node.op) {
            "+" -> l + r
            "-" -> l - r
            "*" -> l * r
            "/" -> if (r == 0.0) throw EvaluationException("Division by zero") else l / r
            "%" -> if (r == 0.0) throw EvaluationException("Division by zero") else l % r
            "^" -> l.pow(r)
            "<" -> bool(l < r)
            "<=" -> bool(l <= r)
            ">" -> bool(l > r)
            ">=" -> bool(l >= r)
            "==" -> bool(l == r)
            "!=" -> bool(l != r)
            else -> error("unknown operator ${node.op}")
        }
    }

    private fun evalCall(node: Node.Call, resolve: (String) -> Double?): Double {
        if (node.name == "if") {
            // only the chosen branch is evaluated, so the other may reference empty traits
            val condition = eval(node.args[0], resolve)
            return eval(if (condition != 0.0) node.args[1] else node.args[2], resolve)
        }
        val a = node.args.map { eval(it, resolve) }
        return when (node.name) {
            "abs" -> abs(a[0])
            "sqrt" -> if (a[0] < 0) throw EvaluationException("Square root of a negative number") else sqrt(a[0])
            "exp" -> exp(a[0])
            "ln" -> if (a[0] <= 0) throw EvaluationException("Logarithm of a non-positive number") else ln(a[0])
            "log", "log10" -> if (a[0] <= 0) throw EvaluationException("Logarithm of a non-positive number") else log10(a[0])
            "round" -> roundTo(a[0], if (a.size > 1) a[1].toInt() else 0)
            "floor" -> floor(a[0])
            "ceil" -> ceil(a[0])
            "sign" -> sign(a[0])
            "pow" -> a[0].pow(a[1])
            "min" -> a.min()
            "max" -> a.max()
            "sum" -> a.sum()
            "mean" -> a.average()
            "sin" -> sin(a[0])
            "cos" -> cos(a[0])
            "tan" -> tan(a[0])
            "asin" -> asin(a[0])
            "acos" -> acos(a[0])
            "atan" -> atan(a[0])
            else -> error("unknown function ${node.name}")
        }
    }

    private fun bool(b: Boolean) = if (b) 1.0 else 0.0

    private sealed class Node {
        data class Num(val value: Double) : Node()
        data class Ref(val name: String) : Node()
        data class Unary(val op: String, val operand: Node) : Node()
        data class Binary(val op: String, val left: Node, val right: Node) : Node()
        data class Call(val name: String, val args: List<Node>) : Node()
    }

    private enum class TokenType { NUMBER, NAME, REF, OP, LPAREN, RPAREN, COMMA, END }

    private data class Token(val type: TokenType, val text: String, val position: Int)

    private class Lexer(private val text: String) {

        fun tokens(): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                        // scientific notation, e.g. 1.5e-3
                        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                            var j = i + 1
                            if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
                            if (j < text.length && text[j].isDigit()) {
                                i = j
                                while (i < text.length && text[i].isDigit()) i++
                            }
                        }
                        val number = text.substring(start, i)
                        if (number.count { it == '.' } > 1) {
                            throw ParseException("Invalid number '$number'", start)
                        }
                        out.add(Token(TokenType.NUMBER, number, start))
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                        out.add(Token(TokenType.NAME, text.substring(start, i), start))
                    }
                    c == '{' -> {
                        val start = i
                        val close = text.indexOf('}', i + 1)
                        if (close == -1) throw ParseException("Missing '}'", start)
                        val name = text.substring(i + 1, close).trim()
                        if (name.isEmpty()) throw ParseException("Empty trait name", start)
                        out.add(Token(TokenType.REF, name, start))
                        i = close + 1
                    }
                    c == '(' -> { out.add(Token(TokenType.LPAREN, "(", i)); i++ }
                    c == ')' -> { out.add(Token(TokenType.RPAREN, ")", i)); i++ }
                    c == ',' || c == ';' -> { out.add(Token(TokenType.COMMA, ",", i)); i++ }
                    else -> {
                        val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                        val (op, length) = when (two) {
                            "<=", ">=", "==", "!=", "&&", "||" -> two to 2
                            "**" -> "^" to 2
                            else -> when (c) {
                                '+', '-', '*', '/', '%', '^', '<', '>', '!' -> c.toString() to 1
                                '=' -> "==" to 1
                                else -> throw ParseException("Unexpected character '$c'", i)
                            }
                        }
                        out.add(Token(TokenType.OP, op, i))
                        i += length
                    }
                }
            }
            out.add(Token(TokenType.END, "", text.length))
            return out
        }
    }

    private class Parser(private val tokens: List<Token>) {

        private var index = 0

        private fun peek() = tokens[index]

        private fun next() = tokens[index++]

        private fun isOp(vararg ops: String) = peek().type == TokenType.OP && peek().text in ops

        fun parse(): Node {
            if (peek().type == TokenType.END) throw ParseException("Formula is empty", 0)
            val node = parseOr()
            val t = peek()
            if (t.type != TokenType.END) throw ParseException("Unexpected '${t.text}'", t.position)
            return node
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (isOp("||")) { next(); left = Node.Binary("||", left, parseAnd()) }
            return left
        }

        private fun parseAnd(): Node {
            var left = parseComparison()
            while (isOp("&&")) { next(); left = Node.Binary("&&", left, parseComparison()) }
            return left
        }

        private fun parseComparison(): Node {
            var left = parseAdditive()
            while (isOp("<", "<=", ">", ">=", "==", "!=")) {
                val op = next().text
                left = Node.Binary(op, left, parseAdditive())
            }
            return left
        }

        private fun parseAdditive(): Node {
            var left = parseMultiplicative()
            while (isOp("+", "-")) {
                val op = next().text
                left = Node.Binary(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): Node {
            var left = parseUnary()
            while (isOp("*", "/", "%")) {
                val op = next().text
                left = Node.Binary(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Node {
            if (isOp("-", "+", "!")) {
                val op = next().text
                return Node.Unary(op, parseUnary())
            }
            return parsePower()
        }

        private fun parsePower(): Node {
            val base = parsePrimary()
            if (isOp("^")) {
                next()
                // right associative, and allows a signed exponent: 2^-1
                return Node.Binary("^", base, parseUnary())
            }
            return base
        }

        private fun parsePrimary(): Node {
            val t = next()
            return when (t.type) {
                TokenType.NUMBER -> Node.Num(t.text.toDouble())
                TokenType.REF -> Node.Ref(t.text)
                TokenType.NAME -> {
                    if (peek().type == TokenType.LPAREN) parseCall(t) else Node.Ref(t.text)
                }
                TokenType.LPAREN -> {
                    val inner = parseOr()
                    val close = next()
                    if (close.type != TokenType.RPAREN) throw ParseException("Missing ')'", close.position)
                    inner
                }
                TokenType.END -> throw ParseException("Formula ends unexpectedly", t.position)
                else -> throw ParseException("Unexpected '${t.text}'", t.position)
            }
        }

        private fun parseCall(nameToken: Token): Node {
            val name = nameToken.text.lowercase()
            val arity = FUNCTIONS[name]
                ?: throw ParseException("Unknown function '${nameToken.text}'", nameToken.position)
            next() // (
            val args = ArrayList<Node>()
            if (peek().type != TokenType.RPAREN) {
                args.add(parseOr())
                while (peek().type == TokenType.COMMA) { next(); args.add(parseOr()) }
            }
            val close = next()
            if (close.type != TokenType.RPAREN) throw ParseException("Missing ')'", close.position)
            if (args.size !in arity) {
                throw ParseException("Wrong number of arguments for '$name'", nameToken.position)
            }
            return Node.Call(name, args)
        }
    }

    companion object {

        private val ANY = 1..Int.MAX_VALUE

        /** Allowed argument counts per function. */
        private val FUNCTIONS: Map<String, IntRange> = mapOf(
            "abs" to 1..1, "sqrt" to 1..1, "exp" to 1..1, "ln" to 1..1, "log" to 1..1,
            "log10" to 1..1, "round" to 1..2, "floor" to 1..1, "ceil" to 1..1, "sign" to 1..1,
            "pow" to 2..2, "min" to ANY, "max" to ANY, "sum" to ANY, "mean" to ANY,
            "if" to 3..3, "sin" to 1..1, "cos" to 1..1, "tan" to 1..1, "asin" to 1..1,
            "acos" to 1..1, "atan" to 1..1
        )

        /** Function names, for help text. */
        val functionNames: Set<String> get() = FUNCTIONS.keys

        /** @throws ParseException when [formula] is not valid. */
        fun parse(formula: String): FormulaEvaluator =
            FormulaEvaluator(formula, Parser(Lexer(formula).tokens()).parse())

        /**
         * Converts a stored observation value into a number usable by a formula, or null when it
         * has none. Accepts plain numbers (decimal point or comma), booleans (1/0), and dates in
         * ISO format (yyyy-MM-dd...), which become a day count so that `{Floracao} - {Semeadura}`
         * gives the number of days between them.
         */
        fun toNumber(raw: String?): Double? {
            val value = raw?.trim() ?: return null
            if (value.isEmpty() || value.equals("NA", ignoreCase = true)) return null

            value.toDoubleOrNull()?.let { return it.takeIf { d -> d.isFinite() } }

            // decimal comma, e.g. 12,5 (but not thousands-grouped values like 1,234,5)
            if (value.count { it == ',' } == 1 && !value.contains('.')) {
                value.replace(',', '.').toDoubleOrNull()?.let { return it }
            }

            when (value.lowercase()) {
                "true" -> return 1.0
                "false" -> return 0.0
            }

            if (value.length >= 10 && value[4] == '-' && value[7] == '-') {
                try {
                    return LocalDate.parse(value.substring(0, 10)).toEpochDay().toDouble()
                } catch (_: DateTimeParseException) {
                }
            }

            return null
        }

        /**
         * Formats a result for storage.
         * @param decimalPlaces number of digits after the decimal point; negative keeps up to
         * six, dropping trailing zeros.
         */
        fun format(value: Double, decimalPlaces: Int): String {
            val scale = if (decimalPlaces < 0) 6 else decimalPlaces
            var bd = BigDecimal(value).setScale(scale, RoundingMode.HALF_UP)
            if (decimalPlaces < 0) {
                bd = bd.stripTrailingZeros()
                if (bd.scale() < 0) bd = bd.setScale(0)
            }
            val text = bd.toPlainString()
            // avoid "-0" and "-0.00"
            return if (bd.signum() == 0) text.removePrefix("-") else text
        }

        private fun roundTo(value: Double, digits: Int): Double =
            BigDecimal(value).setScale(digits.coerceIn(0, 15), RoundingMode.HALF_UP).toDouble()
    }
}
