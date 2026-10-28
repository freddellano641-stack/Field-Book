package com.fieldbook.tracker.traits.calculated

import com.fieldbook.tracker.database.DataHelper
import com.fieldbook.tracker.database.models.ObservationModel
import com.fieldbook.tracker.objects.TraitObject
import com.fieldbook.tracker.utilities.CategoryJsonUtil

/**
 * Connects [FormulaEvaluator] to Field Book data: finds the referenced traits, reads their values
 * for an entry and computes the calculated value for one entry or for a whole field.
 */
object CalculatedTraitHelper {

    /** One referenced trait and the raw value found for the entry (null when there is none). */
    data class Input(val name: String, val rawValue: String?)

    sealed class Outcome {
        abstract val inputs: List<Input>

        /** The formula produced [value], formatted for storage as [text]. */
        data class Value(val value: Double, val text: String, override val inputs: List<Input>) : Outcome()

        /** Some referenced traits have no usable value for this entry yet. */
        data class Missing(val traitNames: List<String>, override val inputs: List<Input>) : Outcome()

        /** The formula ran but did not produce a finite number (division by zero...). */
        data class Error(val message: String, override val inputs: List<Input>) : Outcome()

        /** The formula is empty or does not parse. */
        data class Invalid(val message: String?) : Outcome() {
            override val inputs: List<Input> = emptyList()
        }
    }

    data class BatchResult(val calculated: Int, val skipped: Int)

    /**
     * Finds the trait a formula reference points to: exact name, then exact alias,
     * then either one ignoring case.
     */
    fun findTrait(traits: List<TraitObject>, reference: String): TraitObject? =
        traits.firstOrNull { it.name == reference }
            ?: traits.firstOrNull { it.alias == reference }
            ?: traits.firstOrNull {
                it.name.equals(reference, ignoreCase = true) || it.alias.equals(reference, ignoreCase = true)
            }

    /**
     * Converts a stored observation value to a number. Besides what [FormulaEvaluator.toNumber]
     * accepts, categorical values (stored as JSON) use the category value, so a 1-9 score
     * can be used in formulas.
     */
    fun toNumber(raw: String?): Double? {
        val value = raw?.trim() ?: return null
        if (value.startsWith("[")) {
            return try {
                val category = CategoryJsonUtil.decode(value).firstOrNull()
                FormulaEvaluator.toNumber(category?.value) ?: FormulaEvaluator.toNumber(category?.label)
            } catch (_: Exception) {
                null
            }
        }
        return FormulaEvaluator.toNumber(value)
    }

    /**
     * Computes [trait] for one entry.
     *
     * @param traits      every trait in the database, used to resolve references
     * @param observations every observation of the entry, of any trait
     */
    fun compute(
        trait: TraitObject,
        traits: List<TraitObject>,
        observations: List<ObservationModel>
    ): Outcome {

        val formula = trait.calculationFormula.trim()
        if (formula.isEmpty()) return Outcome.Invalid(null)

        val evaluator = try {
            FormulaEvaluator.parse(formula)
        } catch (e: FormulaEvaluator.ParseException) {
            return Outcome.Invalid(e.message)
        }

        val inputs = evaluator.references.map { reference ->
            val input = findTrait(traits, reference)
            Input(reference, input?.let { latestValue(observations, it.id) })
        }
        val rawByName = inputs.associate { it.name to it.rawValue }

        return try {
            val value = evaluator.evaluate { name -> toNumber(rawByName[name]) }
            val decimals = trait.maxDecimalPlaces.toIntOrNull() ?: -1
            Outcome.Value(value, FormulaEvaluator.format(value, decimals), inputs)
        } catch (e: FormulaEvaluator.MissingValueException) {
            // report every missing input, not only the first one the evaluator hit
            val missing = inputs.filter { toNumber(it.rawValue) == null }.map { it.name }
            Outcome.Missing(missing.ifEmpty { listOf(e.traitName) }, inputs)
        } catch (e: FormulaEvaluator.EvaluationException) {
            Outcome.Error(e.message ?: "", inputs)
        }
    }

    /**
     * Latest non-empty value of a trait among an entry's observations; with repeated measures
     * this is the highest rep.
     */
    private fun latestValue(observations: List<ObservationModel>, traitId: String): String? =
        observations
            .filter { it.observation_variable_db_id.toString() == traitId }
            .filter { it.value.isNotBlank() }
            .maxByOrNull { it.rep.toIntOrNull() ?: 0 }
            ?.value

    /**
     * Computes and saves [trait] for every entry of a field that has data. Entries whose inputs
     * are incomplete are skipped and keep whatever they had.
     */
    fun calculateAll(
        database: DataHelper,
        studyId: String,
        trait: TraitObject,
        person: String
    ): BatchResult {

        val traits = database.allTraitObjects
        val byUnit = database.getAllObservations(studyId).groupBy { it.observation_unit_id }

        var calculated = 0
        var skipped = 0
        val updates = ArrayList<ObservationModel>()

        byUnit.forEach { (unit, observations) ->

            when (val outcome = compute(trait, traits, observations)) {

                is Outcome.Value -> {
                    val existing = observations
                        .filter { it.observation_variable_db_id.toString() == trait.id }
                        .maxByOrNull { it.rep.toIntOrNull() ?: 0 }

                    if (existing == null) {
                        database.insertObservation(
                            unit, trait.id, outcome.text, person, "", "", studyId,
                            null, null, null, "1"
                        )
                    } else if (existing.value != outcome.text) {
                        existing.value = outcome.text
                        existing.collector = person
                        updates.add(existing)
                    }
                    calculated++
                }

                is Outcome.Invalid -> return BatchResult(0, byUnit.size)

                else -> skipped++
            }
        }

        if (updates.isNotEmpty()) {
            database.updateObservationModels(database.db, updates)
        }

        return BatchResult(calculated, skipped)
    }
}
