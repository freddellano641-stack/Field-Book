package com.fieldbook.tracker.traits.calculated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FormulaEvaluatorTest {

    private val values = mapOf(
        "Altura" to 85.0,
        "Peso parcela" to 1250.0,
        "Area util" to 5.0,
        "NDF" to 12.0,
        "DMS" to 0.0
    )

    private fun eval(formula: String, vars: Map<String, Double?> = values) =
        FormulaEvaluator.parse(formula).evaluate { vars[it] }

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(7.0, eval("1 + 2 * 3"), 1e-9)
        assertEquals(9.0, eval("(1 + 2) * 3"), 1e-9)
        assertEquals(1.0, eval("7 % 3"), 1e-9)
        assertEquals(512.0, eval("2 ^ 3 ^ 2"), 1e-9) // right associative
        assertEquals(-4.0, eval("-2 ^ 2"), 1e-9)
        assertEquals(0.5, eval("2 ^ -1"), 1e-9)
        assertEquals(8.0, eval("2 ** 3"), 1e-9)
        assertEquals(0.0015, eval("1.5e-3"), 1e-12)
        assertEquals(0.5, eval(".5"), 1e-12)
    }

    @Test
    fun traitReferences() {
        assertEquals(0.85, eval("Altura / 100"), 1e-9)
        // yield in kg/ha from plot weight in grams and area in m2
        assertEquals(2500.0, eval("{Peso parcela} / {Area util} * 10"), 1e-9)
        assertEquals(listOf("Peso parcela", "Area util"),
            FormulaEvaluator.parse("{Peso parcela} / {Area util} * {Peso parcela}").references)
    }

    @Test
    fun functions() {
        assertEquals(3.0, eval("sqrt(9)"), 1e-9)
        assertEquals(2.0, eval("log(100)"), 1e-9)
        assertEquals(3.14, eval("round(3.14159, 2)"), 1e-9)
        assertEquals(3.0, eval("round(2.5)"), 1e-9)
        assertEquals(5.0, eval("max(1, 5, 3)"), 1e-9)
        assertEquals(1.0, eval("MIN(1, 5, 3)"), 1e-9)
        assertEquals(3.0, eval("mean(1, 5, 3)"), 1e-9)
        assertEquals(9.0, eval("sum(1; 5; 3)"), 1e-9)
        assertEquals(17.0, eval("max(Altura / 5, 1)"), 1e-9)
    }

    @Test
    fun comparisonsAndConditionals() {
        assertEquals(1.0, eval("Altura > 80"), 1e-9)
        assertEquals(0.0, eval("Altura <= 80"), 1e-9)
        assertEquals(1.0, eval("NDF = 12"), 1e-9)
        assertEquals(1.0, eval("NDF == 12 && Altura != 0"), 1e-9)
        assertEquals(1.0, eval("!DMS"), 1e-9)
        assertEquals(10.0, eval("if(Altura > 80, 10, 20)"), 1e-9)
        // the branch not taken may reference a trait with no value
        assertEquals(20.0, eval("if(Altura < 80, Vazio, 20)"), 1e-9)
        assertEquals(0.0, eval("DMS > 0 && Vazio > 0"), 1e-9)
    }

    @Test
    fun missingValues() {
        val e = assertThrows(FormulaEvaluator.MissingValueException::class.java) {
            eval("Altura + Inexistente")
        }
        assertEquals("Inexistente", e.traitName)
    }

    @Test
    fun evaluationErrors() {
        assertThrows(FormulaEvaluator.EvaluationException::class.java) { eval("Altura / DMS") }
        assertThrows(FormulaEvaluator.EvaluationException::class.java) { eval("sqrt(-1)") }
        assertThrows(FormulaEvaluator.EvaluationException::class.java) { eval("ln(0)") }
    }

    @Test
    fun parseErrors() {
        listOf("", "1 +", "(1 + 2", "{Altura", "foo(1)", "round()", "1 2", "1 # 2", "1..2", "{}")
            .forEach { formula ->
                assertThrows("should fail: '$formula'", FormulaEvaluator.ParseException::class.java) {
                    FormulaEvaluator.parse(formula)
                }
            }
    }

    @Test
    fun toNumber() {
        assertEquals(12.5, FormulaEvaluator.toNumber("12.5")!!, 1e-9)
        assertEquals(12.5, FormulaEvaluator.toNumber(" 12,5 ")!!, 1e-9)
        assertEquals(1.0, FormulaEvaluator.toNumber("TRUE")!!, 1e-9)
        assertEquals(0.0, FormulaEvaluator.toNumber("false")!!, 1e-9)
        assertEquals(null, FormulaEvaluator.toNumber("NA"))
        assertEquals(null, FormulaEvaluator.toNumber(""))
        assertEquals(null, FormulaEvaluator.toNumber(null))
        assertEquals(null, FormulaEvaluator.toNumber("verde"))
        assertEquals(null, FormulaEvaluator.toNumber("NaN"))

        // dates become day counts, so their difference is a number of days
        val sowing = FormulaEvaluator.toNumber("2026-10-01")!!
        val flowering = FormulaEvaluator.toNumber("2026-11-15 08:30:00")!!
        assertEquals(45.0, flowering - sowing, 1e-9)
    }

    @Test
    fun format() {
        assertEquals("2500", FormulaEvaluator.format(2500.0, -1))
        assertEquals("0.333333", FormulaEvaluator.format(1.0 / 3.0, -1))
        assertEquals("0.33", FormulaEvaluator.format(1.0 / 3.0, 2))
        assertEquals("3", FormulaEvaluator.format(2.5, 0))
        assertEquals("2.50", FormulaEvaluator.format(2.5, 2))
        assertEquals("0", FormulaEvaluator.format(-0.0000001, -1))
        assertEquals("-1.5", FormulaEvaluator.format(-1.5, -1))
    }
}
