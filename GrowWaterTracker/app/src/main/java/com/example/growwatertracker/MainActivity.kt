package com.nostudios.grow

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import com.nostudios.grow.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
	private lateinit var binding: ActivityMainBinding
	private val prefs by lazy { getSharedPreferences("grow_prefs", MODE_PRIVATE) }
	private lateinit var notificationManager: NotificationManager
	private lateinit var alarmManager: AlarmManager

	private var dailyGoalMl: Int = 2000
	private var glassTargetMl: Int = 250
	private var tolerance: Double = 0.10

	private var consumedMl: Int = 0
	private var points: Int = 0
	private var stage: Int = 0
	private var streakDays: Int = 0
	private var plantHeightCm: Int = 5
	private var bestDayMl: Int = 0
	private var totalDrunkL: Int = 0
	private var notificationsEnabled: Boolean = true
	private var isFirstLaunch: Boolean = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

		createNotificationChannel()
		checkFirstLaunch()
		maybeResetForNewDay()
		loadState()
		setupSettingsListeners()
		updateUi()

		binding.fabBucket.setOnClickListener { showDrinkSheet() }
		binding.bottomNav.setOnItemSelectedListener { item ->
			when (item.itemId) {
				R.id.nav_home -> showSection(R.id.homeContainer)
				R.id.nav_history -> showSection(R.id.historyContainer)
				R.id.nav_scoreboard -> showSection(R.id.scoreboardContainer)
				R.id.nav_settings -> showSection(R.id.settingsContainer)
			}
			true
		}
	}

	private fun checkFirstLaunch() {
		isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
		if (isFirstLaunch) {
			showGoalSetupDialog()
		}
	}

	private fun showGoalSetupDialog() {
		val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_goal_setup, null)
		val seekBar = dialogView.findViewById<SeekBar>(R.id.seekBarGoalSetup)
		val textValue = dialogView.findViewById<TextView>(R.id.textGoalValue)
		val textWarning = dialogView.findViewById<TextView>(R.id.textGoalWarning)
		
		seekBar.max = 3000
		seekBar.progress = 2000
		textValue.text = "2000 ml"
		
		seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (fromUser) {
					textValue.text = "$progress ml"
					
					// Show warning for high values
					if (progress >= 3000) {
						textWarning.text = "⚠️ 3 Liter ist sehr viel und wird nicht empfohlen!"
						textWarning.visibility = android.view.View.VISIBLE
					} else if (progress >= 2500) {
						textWarning.text = "⚠️ Über 2.5 Liter ist bereits viel"
						textWarning.visibility = android.view.View.VISIBLE
					} else {
						textWarning.visibility = android.view.View.GONE
					}
				}
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {}
		})
		
		val dialog = AlertDialog.Builder(this)
			.setTitle("Willkommen bei Grow! 🌱")
			.setMessage("Wie viel Wasser möchtest du täglich trinken?")
			.setView(dialogView)
			.setPositiveButton("Bestätigen") { _, _ ->
				dailyGoalMl = seekBar.progress
				isFirstLaunch = false
				prefs.edit()
					.putBoolean(KEY_FIRST_LAUNCH, false)
					.putInt(KEY_GOAL, dailyGoalMl)
					.apply()
				updateUi()
			}
			.setCancelable(false)
			.create()
		dialog.show()
	}

	private fun showDrinkSheet() {
		val dialog = BottomSheetDialog(this)
		val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_drink, null, false)
		val slider = view.findViewById<Slider>(R.id.sliderAmount)
		val textAmount = view.findViewById<TextView>(R.id.textAmount)
		val buttonAdd = view.findViewById<MaterialButton>(R.id.buttonAdd)
		val animView = view.findViewById<android.widget.ImageView>(R.id.sheetVisual)
		
		(animView.drawable as? android.graphics.drawable.AnimationDrawable)?.start()
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
		
		// Check if exceeding 3L limit
		if (consumedMl > 3000) {
			Toast.makeText(this, R.string.goal_max_exceeded, Toast.LENGTH_LONG).show()
			// Plant will die at end of day, not immediately
		} else {
			// Plant only grows when goal is reached
			if (consumedMl >= dailyGoalMl) {
				if (stage >= 0 && stage < 3) {
					stage += 1
					plantHeightCm += 10
					Toast.makeText(this, R.string.plant_growing, Toast.LENGTH_SHORT).show()
				}
				points += 10
			} else {
				// Plant doesn't die immediately - it will be evaluated at end of day
				points += 2 // Small points for drinking water
			}
		}
		
		// Update best day and total
		if (consumedMl > bestDayMl) {
			bestDayMl = consumedMl
		}
		totalDrunkL += ml
		
		saveState()
		updateUi()
		updateScoreboard()
		updateHistory()
	}

	private fun setupSettingsListeners() {
		binding.seekBarDailyGoal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (fromUser) {
					dailyGoalMl = progress
					binding.textDailyGoalValue.text = "$progress ml"
					
					// Show warning for 3L
					if (progress >= 3000) {
						showGoalWarningDialog()
					}
				}
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				saveState()
			}
		})

		binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
			notificationsEnabled = isChecked
			binding.textNotificationStatus.text = if (isChecked) {
				getString(R.string.notifications_enabled)
			} else {
				getString(R.string.notifications_disabled)
			}
			
			if (isChecked) {
				scheduleNotifications()
			} else {
				cancelNotifications()
			}
			saveState()
		}
	}

	private fun showGoalWarningDialog() {
		AlertDialog.Builder(this)
			.setTitle(R.string.goal_warning_title)
			.setMessage(R.string.goal_warning_message)
			.setPositiveButton(R.string.goal_warning_continue) { _, _ ->
				// User confirmed, continue with 3L goal
			}
			.setNegativeButton(R.string.goal_warning_cancel) { _, _ ->
				// Reset to 2.5L
				dailyGoalMl = 2500
				binding.seekBarDailyGoal.progress = dailyGoalMl
				binding.textDailyGoalValue.text = "$dailyGoalMl ml"
			}
			.show()
	}

	private fun showSection(visibleId: Int) {
		binding.homeContainer.visibility = if (visibleId == R.id.homeContainer) android.view.View.VISIBLE else android.view.View.GONE
		binding.historyContainer.visibility = if (visibleId == R.id.historyContainer) android.view.View.VISIBLE else android.view.View.GONE
		binding.scoreboardContainer.visibility = if (visibleId == R.id.scoreboardContainer) android.view.View.VISIBLE else android.view.View.GONE
		binding.settingsContainer.visibility = if (visibleId == R.id.settingsContainer) android.view.View.VISIBLE else android.view.View.GONE
	}

	private fun updateUi() {
		binding.textTopConsumed.text = getString(R.string.top_consumed, consumedMl)
		binding.textTopGoal.text = getString(R.string.top_goal, dailyGoalMl)
		binding.textTopStreak.text = getString(R.string.top_streak, streakDays)
		binding.imagePlant.setImageResource(drawableForStage(stage))
		binding.textPlantHeight.text = getString(R.string.plant_height, plantHeightCm)
		
		// Update plant status with better messages
		binding.textPlantStatus.text = when {
			stage == -1 -> getString(R.string.plant_dying)
			consumedMl >= dailyGoalMl -> getString(R.string.plant_growing)
			consumedMl >= dailyGoalMl * 0.8 -> "Fast am Ziel! 💪"
			consumedMl >= dailyGoalMl * 0.5 -> "Gut unterwegs! 🌱"
			else -> "Noch ${dailyGoalMl - consumedMl} ml bis zum Ziel"
		}
	}

	private fun updateScoreboard() {
		binding.textScoreboardPoints.text = getString(R.string.scoreboard_points, points)
		binding.textScoreboardStreak.text = getString(R.string.scoreboard_streak, streakDays)
		binding.textScoreboardBestDay.text = getString(R.string.scoreboard_best_day, bestDayMl)
		binding.textScoreboardTotal.text = getString(R.string.scoreboard_total_drunk, totalDrunkL / 1000)
	}

	private fun updateHistory() {
		val today = currentDateKey()
		val yesterday = getYesterdayDate()
		val weekStart = getWeekStartDate()
		
		binding.textHistoryToday.text = "${getString(R.string.history_today)}: $consumedMl ml"
		binding.textHistoryYesterday.text = "${getString(R.string.history_yesterday)}: ${prefs.getInt("history_$yesterday", 0)} ml"
		binding.textHistoryWeek.text = "${getString(R.string.history_this_week)}: ${getWeekTotal(weekStart)} ml"
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
			// Save yesterday's data
			prefs.edit().putInt("history_$lastDay", consumedMl).apply()
			
			// Evaluate plant health at end of day
			evaluatePlantHealth()
			
			if (consumedMl >= dailyGoalMl) {
				streakDays += 1
			} else {
				streakDays = 0
			}
			
			consumedMl = 0
			points = 0
			stage = 0
			plantHeightCm = 5
			prefs.edit().putString(KEY_DATE, today).apply()
			saveState()
		}
	}

	private fun evaluatePlantHealth() {
		val previousStage = stage
		val previousHeight = plantHeightCm
		
		when {
			consumedMl > 3000 -> {
				// Plant dies from overwatering
				stage = -1
				plantHeightCm = 0
				Toast.makeText(this, "Pflanze ist an Überwässerung gestorben! 💀", Toast.LENGTH_LONG).show()
			}
			consumedMl >= dailyGoalMl -> {
				// Plant grows if goal reached
				if (stage >= 0 && stage < 3) {
					stage += 1
					plantHeightCm += 10
				}
				Toast.makeText(this, "Pflanze ist gewachsen! 🌱", Toast.LENGTH_SHORT).show()
			}
			consumedMl < dailyGoalMl * 0.5 -> {
				// Plant dies if less than 50% of goal
				stage = -1
				plantHeightCm = 0
				Toast.makeText(this, "Pflanze ist vertrocknet! 💀", Toast.LENGTH_LONG).show()
			}
			else -> {
				// Plant survives but doesn't grow
				if (stage > 0) {
					stage -= 1
					plantHeightCm = maxOf(0, plantHeightCm - 5)
				}
				Toast.makeText(this, "Pflanze hat überlebt, aber nicht gewachsen", Toast.LENGTH_SHORT).show()
			}
		}
		
		// Save the evaluation result
		prefs.edit()
			.putInt(KEY_STAGE, stage)
			.putInt(KEY_PLANT_HEIGHT, plantHeightCm)
			.apply()
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				getString(R.string.notification_channel_name),
				NotificationManager.IMPORTANCE_DEFAULT
			).apply {
				description = getString(R.string.notification_channel_description)
			}
			notificationManager.createNotificationChannel(channel)
		}
	}

	private fun scheduleNotifications() {
		if (!notificationsEnabled) return
		
		val intent = Intent(this, NotificationReceiver::class.java)
		val pendingIntent = PendingIntent.getBroadcast(
			this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		// Schedule notifications every 3 hours
		val calendar = Calendar.getInstance()
		calendar.add(Calendar.HOUR, 3)
		
		alarmManager.setRepeating(
			AlarmManager.RTC_WAKEUP,
			calendar.timeInMillis,
			AlarmManager.INTERVAL_HOUR * 3,
			pendingIntent
		)
	}

	private fun cancelNotifications() {
		val intent = Intent(this, NotificationReceiver::class.java)
		val pendingIntent = PendingIntent.getBroadcast(
			this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		alarmManager.cancel(pendingIntent)
	}

	private fun saveState() {
		prefs.edit()
			.putInt(KEY_CONSUMED, consumedMl)
			.putInt(KEY_POINTS, points)
			.putInt(KEY_STAGE, stage)
			.putInt(KEY_GOAL, dailyGoalMl)
			.putInt(KEY_STREAK, streakDays)
			.putInt(KEY_PLANT_HEIGHT, plantHeightCm)
			.putInt(KEY_BEST_DAY, bestDayMl)
			.putInt(KEY_TOTAL_DRUNK, totalDrunkL)
			.putBoolean(KEY_NOTIFICATIONS, notificationsEnabled)
			.apply()
	}

	private fun loadState() {
		dailyGoalMl = prefs.getInt(KEY_GOAL, 2000)
		consumedMl = prefs.getInt(KEY_CONSUMED, 0)
		points = prefs.getInt(KEY_POINTS, 0)
		stage = prefs.getInt(KEY_STAGE, 0)
		streakDays = prefs.getInt(KEY_STREAK, 0)
		plantHeightCm = prefs.getInt(KEY_PLANT_HEIGHT, 5)
		bestDayMl = prefs.getInt(KEY_BEST_DAY, 0)
		totalDrunkL = prefs.getInt(KEY_TOTAL_DRUNK, 0)
		notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true)
		
		// Update UI elements
		binding.seekBarDailyGoal.progress = dailyGoalMl
		binding.switchNotifications.isChecked = notificationsEnabled
		binding.textDailyGoalValue.text = "$dailyGoalMl ml"
		binding.textNotificationStatus.text = if (notificationsEnabled) {
			getString(R.string.notifications_enabled)
		} else {
			getString(R.string.notifications_disabled)
		}
	}

	private fun currentDateKey(): String {
		return if (Build.VERSION.SDK_INT >= 26) {
			java.time.LocalDate.now().toString()
		} else {
			SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
		}
	}

	private fun getYesterdayDate(): String {
		val calendar = Calendar.getInstance()
		calendar.add(Calendar.DAY_OF_YEAR, -1)
		return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
	}

	private fun getWeekStartDate(): String {
		val calendar = Calendar.getInstance()
		calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
		return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
	}

	private fun getWeekTotal(weekStart: String): Int {
		var total = 0
		val calendar = Calendar.getInstance()
		repeat(7) {
			val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
			total += prefs.getInt("history_$date", 0)
			calendar.add(Calendar.DAY_OF_YEAR, 1)
		}
		return total
	}

	companion object {
		private const val KEY_DATE = "key_date"
		private const val KEY_CONSUMED = "key_consumed"
		private const val KEY_POINTS = "key_points"
		private const val KEY_STAGE = "key_stage"
		private const val KEY_GOAL = "key_goal"
		private const val KEY_STREAK = "key_streak"
		private const val KEY_PLANT_HEIGHT = "key_plant_height"
		private const val KEY_BEST_DAY = "key_best_day"
		private const val KEY_TOTAL_DRUNK = "key_total_drunk"
		private const val KEY_NOTIFICATIONS = "key_notifications"
		private const val KEY_FIRST_LAUNCH = "key_first_launch"
		private const val CHANNEL_ID = "grow_water_reminders"
	}
}

// Notification Receiver for scheduled notifications
class NotificationReceiver : android.content.BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val prefs = context.getSharedPreferences("grow_prefs", Context.MODE_PRIVATE)
		val consumedMl = prefs.getInt("key_consumed", 0)
		val dailyGoalMl = prefs.getInt("key_goal", 2000)
		val notificationsEnabled = prefs.getBoolean("key_notifications", true)
		
		if (!notificationsEnabled) return
		
		val remainingMl = dailyGoalMl - consumedMl
		if (remainingMl > 0) {
			val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			
			val notification = NotificationCompat.Builder(context, "grow_water_reminders")
				.setContentTitle(context.getString(R.string.notification_title))
				.setContentText(context.getString(R.string.notification_message, remainingMl))
				.setSmallIcon(android.R.drawable.ic_dialog_info)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setAutoCancel(true)
				.build()
			
			notificationManager.notify(1, notification)
		}
	}
}