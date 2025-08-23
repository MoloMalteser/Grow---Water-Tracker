package com.nostudios.grow

import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import com.nostudios.grow.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
	private lateinit var binding: ActivityMainBinding
	private val prefs by lazy { getSharedPreferences("grow_prefs", MODE_PRIVATE) }

	private var dailyGoalMl: Int = 2000
	private var glassTargetMl: Int = 250
	private var tolerance: Double = 0.10

	private var consumedMl: Int = 0
	private var points: Int = 0
	private var stage: Int = 0
	private var streakDays: Int = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		maybeResetForNewDay()
		loadState()
		updateUi()

		binding.fabBucket.setOnClickListener { showDrinkSheet() }
		binding.bottomNav.setOnItemSelectedListener {
			true
		}
	}

	private fun showDrinkSheet() {
		val dialog = BottomSheetDialog(this)
		val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_drink, null, false)
		val slider = view.findViewById<Slider>(R.id.sliderAmount)
		val textAmount = view.findViewById<TextView>(R.id.textAmount)
		val buttonAdd = view.findViewById<MaterialButton>(R.id.buttonAdd)
		slider.valueFrom = 50f
		slider.valueTo = 1000f
		slider.stepSize = 50f
		textAmount.text = getString(R.string.sheet_amount, slider.value.toInt())
		slider.addOnChangeListener { _, value, _ ->
			textAmount.text = getString(R.string.sheet_amount, value.toInt())
		}
		buttonAdd.setOnClickListener {
			applyDrink(slider.value.toInt())
			dialog.dismiss()
		}
		dialog.setContentView(view)
		dialog.show()
	}

	private fun applyDrink(ml: Int) {
		consumedMl += ml
		val lower = (glassTargetMl * (1.0 - tolerance)).toInt()
		val upper = (glassTargetMl * (1.0 + tolerance)).toInt()
		val within = ml in lower..upper
		if (within) {
			points += 10
			if (stage >= 0 && stage < 3) stage += 1
			animatePlant(true)
		} else {
			points -= 5
			if (stage > 0) {
				stage -= 1
				animatePlant(false)
			} else {
				stage = -1
			}
		}
		saveState()
		updateUi()
	}

	private fun animatePlant(grow: Boolean) {
		val scaleFrom = if (grow) 0.95f else 1.05f
		ObjectAnimator.ofFloat(binding.imagePlant, "scaleX", scaleFrom, 1f).apply { duration = 250; start() }
		ObjectAnimator.ofFloat(binding.imagePlant, "scaleY", scaleFrom, 1f).apply { duration = 250; start() }
	}

	private fun updateUi() {
		binding.textTopConsumed.text = getString(R.string.top_consumed, consumedMl)
		binding.textTopGoal.text = getString(R.string.top_goal, dailyGoalMl)
		binding.textTopStreak.text = getString(R.string.top_streak, streakDays)
		binding.imagePlant.setImageResource(drawableForStage(stage))
	}

	private fun drawableForStage(stage: Int): Int = when (stage) {
		-1 -> R.drawable.plant_dead
		0 -> R.drawable.plant_seedling
		1 -> R.drawable.plant_small
		2 -> R.drawable.plant_medium
		else -> R.drawable.plant_large
	}

	private fun maybeResetForNewDay() {
		val today = currentDateKey()
		val lastDay = prefs.getString(KEY_DATE, null)
		if (lastDay == null) {
			prefs.edit().putString(KEY_DATE, today).apply()
			return
		}
		if (lastDay != today) {
			if (consumedMl >= dailyGoalMl) {
				streakDays += 1
			} else {
				streakDays = 0
			}
			consumedMl = 0
			points = 0
			stage = 0
			prefs.edit().putString(KEY_DATE, today).apply()
			saveState()
		}
	}

	private fun saveState() {
		prefs.edit()
			.putInt(KEY_CONSUMED, consumedMl)
			.putInt(KEY_POINTS, points)
			.putInt(KEY_STAGE, stage)
			.putInt(KEY_GOAL, dailyGoalMl)
			.putInt(KEY_GLASS, glassTargetMl)
			.putInt(KEY_STREAK, streakDays)
			.apply()
	}

	private fun loadState() {
		dailyGoalMl = prefs.getInt(KEY_GOAL, 2000)
		glassTargetMl = prefs.getInt(KEY_GLASS, 250)
		consumedMl = prefs.getInt(KEY_CONSUMED, 0)
		points = prefs.getInt(KEY_POINTS, 0)
		stage = prefs.getInt(KEY_STAGE, 0)
		streakDays = prefs.getInt(KEY_STREAK, 0)
	}

	private fun currentDateKey(): String {
		return if (Build.VERSION.SDK_INT >= 26) {
			java.time.LocalDate.now().toString()
		} else {
			SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
		}
	}

	companion object {
		private const val KEY_DATE = "key_date"
		private const val KEY_CONSUMED = "key_consumed"
		private const val KEY_POINTS = "key_points"
		private const val KEY_STAGE = "key_stage"
		private const val KEY_GOAL = "key_goal"
		private const val KEY_GLASS = "key_glass"
		private const val KEY_STREAK = "key_streak"
	}
}