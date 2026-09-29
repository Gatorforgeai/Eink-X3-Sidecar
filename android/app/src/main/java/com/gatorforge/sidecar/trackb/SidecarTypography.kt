package com.gatorforge.sidecar.trackb

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface

/**
 * DESIGN INTENT
 * DONE-WHEN: Every X3 content renderer uses the same bundled, high-legibility typeface at weights
 * and minimum sizes appropriate for the 528 x 792, 259-PPI monochrome panel.
 * INPUTS: Android application context, requested regular/medium/bold weight, tabular-number usage.
 * FAILS-WHEN: Rendering varies with the phone's installed fonts, useful text shrinks below the
 * X3 legibility floor, or numeric columns use ambiguous/proportionally shifting digits.
 */
internal object SidecarTypography {
    const val TITLE = 40f
    const val BODY = 30f
    const val DENSE = 26f
    const val META = 24f
    const val FOOTER = 20f
    const val MIN_USEFUL = 24f

    private var base: Typeface? = null

    private fun base(context: Context): Typeface = base ?: synchronized(this) {
        base ?: context.resources.getFont(R.font.atkinson_hyperlegible_next).also { base = it }
    }

    fun regular(context: Context): Typeface = Typeface.create(base(context), 400, false)
    fun medium(context: Context): Typeface = Typeface.create(base(context), 500, false)
    fun bold(context: Context): Typeface = Typeface.create(base(context), 700, false)

    fun tabular(paint: Paint): Paint = paint.apply { fontFeatureSettings = "tnum" }
}
