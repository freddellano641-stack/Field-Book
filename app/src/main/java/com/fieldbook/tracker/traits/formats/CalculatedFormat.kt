package com.fieldbook.tracker.traits.formats

import com.fieldbook.tracker.R
import com.fieldbook.tracker.traits.formats.feature.ChartableData
import com.fieldbook.tracker.traits.formats.feature.DisplayValue
import com.fieldbook.tracker.traits.formats.parameters.CalculationFormulaParameter
import com.fieldbook.tracker.traits.formats.parameters.DecimalPlacesParameter
import com.fieldbook.tracker.traits.formats.parameters.DetailsParameter
import com.fieldbook.tracker.traits.formats.parameters.NameParameter
import com.fieldbook.tracker.traits.formats.parameters.UnitParameter

/**
 * A trait whose value is computed from other traits of the same entry with a formula,
 * e.g. yield from plot weight and area, or days to flowering from two dates.
 * The result is stored as a normal observation, so it is exported like any other trait.
 */
open class CalculatedFormat : TraitFormat(
    format = Formats.CALCULATED,
    defaultLayoutId = R.layout.trait_calculated,
    layoutView = null,
    databaseName = "calculated",
    nameStringResourceId = R.string.traits_format_calculated,
    iconDrawableResourceId = R.drawable.ic_trait_calculated,
    stringNameAux = null,
    NameParameter(),
    CalculationFormulaParameter(),
    DecimalPlacesParameter(),
    UnitParameter(),
    DetailsParameter()
), DisplayValue, ChartableData
