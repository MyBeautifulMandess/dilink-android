package com.dynamicisland.dilink.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dynamicisland.dilink.R
import java.util.Calendar

/**
 * Месячная сетка календаря.
 *
 * Сделана кодом, а не разметкой: 42 ячейки в XML засоряли бы файл, а здесь
 * сетка собирается один раз и просто перерисовывается при смене месяца.
 * Неделя начинается с понедельника — как принято в России.
 */
class MonthGrid(
    context: Context,
    private val onPick: (year: Int, month: Int, day: Int) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentSoft = ContextCompat.getColor(context, R.color.accent_soft)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

    private val cells = ArrayList<TextView>()
    private var year = 0
    private var month = 0

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, 0)
    }

    init {
        root.addView(weekHeader(context))
        for (row in 0 until ROWS) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (column in 0 until 7) {
                val cell = dayCell(context)
                cells.add(cell)
                line.addView(cell)
            }
            root.addView(line)
        }
    }

    /** Заголовок дней недели: понедельник первым. */
    private fun weekHeader(context: Context): View {
        val names = context.resources.getStringArray(R.array.weekday_names)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        for (index in 0 until 7) {
            line.addView(TextView(context).apply {
                text = names.getOrElse(index) { "" }
                gravity = Gravity.CENTER
                setTextColor(textSecondary)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return line
    }

    private fun dayCell(context: Context): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(textPrimary)
        layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
            setMargins((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
        }
        isClickable = true
        setOnClickListener {
            val day = tag as? Int ?: return@setOnClickListener
            onPick(year, month, day)
        }
    }

    /**
     * Перерисовывает месяц.
     *
     * @param selectedY,selectedM,selectedD — выбранный день.
     * @param marked — номера дней, где есть события.
     */
    fun render(year: Int, month: Int, selectedY: Int, selectedM: Int, selectedD: Int, marked: Set<Int>) {
        this.year = year
        this.month = month
        val today = Calendar.getInstance()
        val first = Calendar.getInstance().apply {
            clear()
            set(year, month, 1)
        }
        // Понедельник — нулевой день недели.
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        var cell = 0
        var day = 1 - offset
        for (row in 0 until ROWS) {
            for (column in 0 until 7) {
                val view = cells[cell]
                val inMonth = day in 1..lengthOfMonth(year, month)
                if (inMonth) {
                    view.text = day.toString()
                    view.tag = day
                    view.isEnabled = true
                    view.alpha = 1f
                    val isSelected = year == selectedY && month == selectedM && day == selectedD
                    val isToday = today.get(Calendar.YEAR) == year &&
                        today.get(Calendar.MONTH) == month &&
                        today.get(Calendar.DAY_OF_MONTH) == day
                    when {
                        isSelected -> {
                            view.background = pill(accent)
                            view.setTextColor(Color.WHITE)
                            view.setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        isToday -> {
                            view.background = pill(accentSoft)
                            view.setTextColor(accent)
                            view.setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        day in marked -> {
                            view.background = null
                            view.setTextColor(accent)
                            view.setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        else -> {
                            view.background = null
                            view.setTextColor(textPrimary)
                            view.setTypeface(null, android.graphics.Typeface.NORMAL)
                        }
                    }
                } else {
                    // Дни соседних месяцев показываем бледно — для ориентира.
                    val number = if (day < 1) {
                        val previous = if (month == 0) month + 11 else month - 1
                        val previousYear = if (month == 0) year - 1 else year
                        day + lengthOfMonth(previousYear, previous)
                    } else {
                        day - lengthOfMonth(year, month)
                    }
                    view.text = number.toString()
                    view.tag = null
                    view.isEnabled = false
                    view.alpha = 0.35f
                    view.background = null
                    view.setTextColor(textSecondary)
                    view.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                cell++
                day++
            }
        }
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12 * density
        setColor(color)
    }

    private fun lengthOfMonth(year: Int, month: Int): Int {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month, 1)
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private companion object {
        const val ROWS = 6
    }
}
