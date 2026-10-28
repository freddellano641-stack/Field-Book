package com.fieldbook.tracker.traits.formats.parameters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import com.fieldbook.tracker.R
import com.fieldbook.tracker.database.repository.TraitRepository
import com.fieldbook.tracker.objects.TraitObject
import com.fieldbook.tracker.traits.calculated.CalculatedTraitHelper
import com.fieldbook.tracker.traits.calculated.FormulaEvaluator
import com.fieldbook.tracker.traits.formats.ValidationResult
import kotlinx.coroutines.runBlocking

/**
 * Formula of a calculated trait. Validation checks the syntax and that every referenced trait
 * exists, so mistakes show up when the trait is created instead of in the field.
 */
class CalculationFormulaParameter : BaseFormatParameter(
    nameStringResourceId = R.string.traits_create_calculation_formula,
    defaultLayoutId = R.layout.list_item_trait_parameter_formula,
    parameter = Parameters.CALCULATION_FORMULA
) {

    override fun createViewHolder(
        parent: ViewGroup,
    ): BaseFormatParameter.ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_trait_parameter_formula, parent, false)
        return ViewHolder(v)
    }

    inner class ViewHolder(itemView: View) : BaseFormatParameter.ViewHolder(itemView) {

        private val formulaEt: EditText =
            itemView.findViewById(R.id.list_item_trait_parameter_formula_et)

        init {
            formulaEt.addTextChangedListener { textInputLayout.error = null }
        }

        override fun merge(traitObject: TraitObject) = traitObject.apply {
            calculationFormula = formulaEt.text.toString().trim()
        }

        override fun load(traitObject: TraitObject?): Boolean {
            formulaEt.setText(traitObject?.calculationFormula ?: "")
            return true
        }

        override fun validate(
            traitRepo: TraitRepository,
            initialTraitObject: TraitObject?
        ) = ValidationResult().apply {

            val context = itemView.context
            val formula = formulaEt.text.toString().trim()

            error = when {
                formula.isEmpty() ->
                    context.getString(R.string.traits_create_calculation_formula_required)

                else -> try {
                    val references = FormulaEvaluator.parse(formula).references
                    val traits = runBlocking { traitRepo.getTraits() }
                    val selfName = initialTraitObject?.name

                    val unknown = references.filter {
                        CalculatedTraitHelper.findTrait(traits, it) == null
                    }
                    when {
                        selfName != null && references.any { it.equals(selfName, ignoreCase = true) } ->
                            context.getString(R.string.traits_create_calculation_formula_self_reference)

                        unknown.isNotEmpty() -> context.getString(
                            R.string.traits_create_calculation_formula_unknown_trait,
                            unknown.joinToString(", ")
                        )

                        else -> null
                    }
                } catch (e: FormulaEvaluator.ParseException) {
                    context.getString(R.string.traits_create_calculation_formula_invalid, e.message)
                }
            }

            result = error == null
            textInputLayout.error = error
        }
    }
}
