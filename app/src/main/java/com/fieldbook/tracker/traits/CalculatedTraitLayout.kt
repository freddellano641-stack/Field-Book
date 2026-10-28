package com.fieldbook.tracker.traits

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fieldbook.tracker.R
import com.fieldbook.tracker.traits.calculated.CalculatedTraitHelper
import com.fieldbook.tracker.traits.calculated.CalculatedTraitHelper.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collect screen for calculated traits. The value is not typed: each time the entry is shown,
 * the formula is evaluated with the entry's current values and the result is saved when it changed.
 * "Calculate all entries" fills the whole field at once, e.g. after the inputs were collected.
 */
class CalculatedTraitLayout : BaseTraitLayout {

    companion object {
        const val TAG = "CalculatedTraitLayout"
        const val type = "calculated"
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var formulaTv: TextView? = null
    private var statusTv: TextView? = null
    private var inputsTitleTv: TextView? = null
    private var inputsLl: LinearLayout? = null
    private var recalculateBtn: Button? = null
    private var calculateAllBtn: Button? = null

    override fun layoutId(): Int = R.layout.trait_calculated

    override fun type(): String = type

    override fun init(act: Activity) {
        formulaTv = act.findViewById(R.id.trait_calculated_formula_tv)
        statusTv = act.findViewById(R.id.trait_calculated_status_tv)
        inputsTitleTv = act.findViewById(R.id.trait_calculated_inputs_title_tv)
        inputsLl = act.findViewById(R.id.trait_calculated_inputs_ll)
        recalculateBtn = act.findViewById(R.id.trait_calculated_recalculate_btn)
        calculateAllBtn = act.findViewById(R.id.trait_calculated_calculate_all_btn)

        recalculateBtn?.setOnClickListener { calculateCurrentEntry() }
        calculateAllBtn?.setOnClickListener { confirmCalculateAll() }
    }

    override fun setNaTraitsText() {
        collectInputView.text = "NA"
    }

    override fun loadLayout() {
        super.loadLayout()
        calculateCurrentEntry()
    }

    /**
     * Evaluates the formula for the entry on screen, shows the inputs and saves the result
     * when it differs from what is stored.
     */
    private fun calculateCurrentEntry() {

        val trait = currentTrait ?: return
        val act = collectActivity

        val outcome = try {
            CalculatedTraitHelper.compute(
                trait,
                database.allTraitObjects,
                database.getAllObservations(act.studyId, act.observationUnit).toList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate ${trait.name}", e)
            showStatus(context.getString(R.string.trait_calculated_error, e.message ?: ""), isProblem = true)
            return
        }

        formulaTv?.text = trait.calculationFormula.ifBlank { "" }
        showInputs(outcome)

        when (outcome) {

            is Outcome.Value -> saveIfChanged(outcome.text)

            is Outcome.Missing -> showStatus(
                context.getString(
                    R.string.trait_calculated_missing_inputs,
                    outcome.traitNames.joinToString(", ")
                ),
                isProblem = true
            )

            is Outcome.Error -> showStatus(
                context.getString(R.string.trait_calculated_error, outcome.message),
                isProblem = true
            )

            is Outcome.Invalid -> showStatus(
                if (outcome.message == null) {
                    context.getString(R.string.trait_calculated_no_formula)
                } else {
                    context.getString(R.string.trait_calculated_invalid_formula, outcome.message)
                },
                isProblem = true
            )
        }
    }

    private fun saveIfChanged(value: String) {

        val stored = currentObservation?.value

        if (stored == value) {
            showStatus("", isProblem = false)
            return
        }

        if (isLocked) {
            // show what the value would be without touching locked data
            showStatus("${context.getString(R.string.trait_calculated_locked)}: $value", isProblem = true)
            return
        }

        collectInputView.text = value
        collectActivity.updateObservation(currentTrait, value, null)
        collectInputView.markObservationSaved()
        collectInputView.setTextColor(Color.parseColor(displayColor))

        showStatus(context.getString(R.string.trait_calculated_saved), isProblem = false)
    }

    private fun showInputs(outcome: Outcome) {

        val container = inputsLl ?: return
        container.removeAllViews()

        val inputs = outcome.inputs
        inputsTitleTv?.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE

        val empty = context.getString(R.string.trait_calculated_empty_value)

        inputs.forEach { input ->
            val shown = CalculatedTraitHelper.toNumber(input.rawValue)?.let { input.rawValue } ?: empty
            container.addView(TextView(context).apply {
                text = "${input.name}: $shown"
                textSize = 16f
                setTextColor(if (shown == empty) valueAlteredColor else textColor)
                setPadding(0, 4, 0, 4)
            })
        }
    }

    private fun showStatus(message: String, isProblem: Boolean) {
        statusTv?.apply {
            text = message
            visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            setTextColor(if (isProblem) valueAlteredColor else textColor)
        }
    }

    private fun confirmCalculateAll() {

        val act = collectActivity
        val trait = currentTrait ?: return

        if (act.isLocked || act.isFrozen) {
            Toast.makeText(context, R.string.trait_calculated_locked, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(context, R.style.AppAlertDialog)
            .setTitle(R.string.trait_calculated_calculate_all)
            .setMessage(context.getString(R.string.trait_calculated_calculate_all_confirm, trait.name))
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                calculateAll()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun calculateAll() {

        val act = collectActivity
        val trait = currentTrait ?: return
        val studyId = act.studyId
        val person = act.person

        calculateAllBtn?.isEnabled = false

        act.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    CalculatedTraitHelper.calculateAll(database, studyId, trait, person)
                }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.trait_calculated_calculate_all_result,
                        result.calculated,
                        result.skipped
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to calculate all entries for ${trait.name}", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.trait_calculated_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                calculateAllBtn?.isEnabled = true
                // the value on screen may have changed
                loadLayout()
            }
        }
    }
}
