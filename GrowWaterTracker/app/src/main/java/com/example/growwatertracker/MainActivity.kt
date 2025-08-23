package com.nostudios.grow

import android.animation.ObjectAnimator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.nostudios.grow.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
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
	private var stage: Int = 0 // -1 dead, 0 seedling, 1 small, 2 medium, 3 large

	private var lastCapturedPhotoUri: Uri? = null
	private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setupActivityResult()
		maybeResetForNewDay()
		loadState()
		updateUi()

		binding.buttonDrink.setOnClickListener { showDrinkDialog() }
		binding.buttonPhoto.setOnClickListener { capturePhotoProof() }
	}

	private fun setupActivityResult() {
		takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
			if (success) {
				// Optionally we could show a small acknowledgment
				lastCapturedPhotoUri?.let { uri ->
					prefs.edit().putString(KEY_LAST_PHOTO_URI, uri.toString()).apply()
				}
			}
		}
	}

	private fun showDrinkDialog() {
		if (stage < 0) {
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.dialog_dead_title)
				.setMessage(R.string.dialog_dead_message)
				.setPositiveButton(android.R.string.ok, null)
				.show()
			return
		}

		val input = EditText(this)
		input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
		input.hint = getString(R.string.dialog_drink_hint, glassTargetMl)

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.dialog_drink_title)
			.setView(input)
			.setPositiveButton(R.string.dialog_drink_positive) { _, _ ->
				val text = input.text?.toString()?.trim()
				val ml = text?.toIntOrNull()
				if (ml != null && ml > 0) {
					applyDrink(ml)
				} else {
					MaterialAlertDialogBuilder(this)
						.setMessage(R.string.dialog_invalid_amount)
						.setPositiveButton(android.R.string.ok, null)
						.show()
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun applyDrink(ml: Int) {
		consumedMl += ml

		val lower = (glassTargetMl * (1.0 - tolerance)).toInt()
		val upper = (glassTargetMl * (1.0 + tolerance)).toInt()
		val within = ml in lower..upper

		if (within) {
			points += 10
			if (stage >= 0 && stage < 3) stage += 1
			animatePlant(grow = true)
		} else {
			points -= 5
			if (stage > 0) {
				stage -= 1
				animatePlant(grow = false)
			} else {
				stage = -1
				animateDeath()
			}
		}

		saveState()
		updateUi()
	}

	private fun animatePlant(grow: Boolean) {
		val scaleFrom = if (grow) 0.95f else 1.05f
		val scaleTo = 1.0f
		ObjectAnimator.ofFloat(binding.imagePlant, "scaleX", scaleFrom, scaleTo).apply {
			duration = 250
			start()
		}
		ObjectAnimator.ofFloat(binding.imagePlant, "scaleY", scaleFrom, scaleTo).apply {
			duration = 250
			start()
		}
	}

	private fun animateDeath() {
		ObjectAnimator.ofFloat(binding.imagePlant, "alpha", 1f, 0.5f, 1f).apply {
			duration = 600
			start()
		}
	}

	private fun capturePhotoProof() {
		val photoFile = createImageFile()
		val uri = FileProvider.getUriForFile(
			this,
			"com.nostudios.grow.fileprovider",
			photoFile
		)
		lastCapturedPhotoUri = uri
		takePictureLauncher.launch(uri)
	}

	private fun createImageFile(): File {
		val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
		val imagesDir = File(cacheDir, "images").apply { if (!exists()) mkdirs() }
		return File(imagesDir, "GROW_${'$'}timeStamp.jpg")
	}

	private fun updateUi() {
		binding.textDailyGoal.text = getString(R.string.daily_goal_label, dailyGoalMl)
		binding.textConsumed.text = getString(R.string.consumed_label, consumedMl)
		binding.textPoints.text = getString(R.string.points_label, points)
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
		val savedDay = prefs.getString(KEY_DATE, null)
		if (savedDay == null || savedDay != today) {
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
			.apply()
	}

	private fun loadState() {
		dailyGoalMl = prefs.getInt(KEY_GOAL, 2000)
		glassTargetMl = prefs.getInt(KEY_GLASS, 250)
		consumedMl = prefs.getInt(KEY_CONSUMED, 0)
		points = prefs.getInt(KEY_POINTS, 0)
		stage = prefs.getInt(KEY_STAGE, 0)
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
		private const val KEY_LAST_PHOTO_URI = "key_last_photo_uri"
	}
}