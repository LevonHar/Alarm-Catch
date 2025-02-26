package com.simplemobiletools.clock.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import com.simplemobiletools.clock.R
import com.simplemobiletools.clock.databinding.ActivityReminderBinding
import com.simplemobiletools.clock.extensions.*
import com.simplemobiletools.clock.helpers.ALARM_ID
import com.simplemobiletools.clock.helpers.ALARM_NOTIF_ID
import com.simplemobiletools.clock.helpers.getPassedSeconds
import com.simplemobiletools.clock.models.Alarm
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import kotlin.random.Random

class ReminderActivity : SimpleActivity() {
    companion object {
        private const val MIN_ALARM_VOLUME_FOR_INCREASING_ALARMS = 1
        private const val INCREASE_VOLUME_DELAY = 300L
    }

    private val increaseVolumeHandler = Handler(Looper.getMainLooper())
    private val maxReminderDurationHandler = Handler(Looper.getMainLooper())
    private val swipeGuideFadeHandler = Handler()
    private val vibrationHandler = Handler(Looper.getMainLooper())
    private var isAlarmReminder = false
    private var didVibrate = false
    private var wasAlarmSnoozed = false
    private var alarm: Alarm? = null
    private var audioManager: AudioManager? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var initialAlarmVolume: Int? = null
    private var dragDownX = 0f
    private val binding: ActivityReminderBinding by viewBinding(ActivityReminderBinding::inflate)
    private var finished = false
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var isButtonMoving = false
    var backPressCount : Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        showOverLockscreen()
        updateTextColors(binding.root)
        updateStatusbarColor(getProperBackgroundColor())

        val id = intent.getIntExtra(ALARM_ID, -1)
        isAlarmReminder = id != -1
        if (id != -1) {
            alarm = dbHelper.getAlarmWithId(id) ?: return
        }

        val label = if (isAlarmReminder) {
            if (alarm!!.label.isEmpty()) {
                getString(com.simplemobiletools.commons.R.string.alarm)
            } else {
                alarm!!.label
            }
        } else {
            getString(R.string.timer)
        }

        binding.reminderTitle.text = label
        binding.reminderText.text = if (isAlarmReminder) getFormattedTime(getPassedSeconds(), false, false) else getString(R.string.time_expired)

        val maxDuration = if (isAlarmReminder) config.alarmMaxReminderSecs else config.timerMaxReminderSecs
        maxReminderDurationHandler.postDelayed({
            finishActivity()
        }, maxDuration * 1000L)

        setupButtons()
        setupEffects()

        moveButtonRandomly()
    }

    private fun setupButtons() {
        if (isAlarmReminder) {
            setupAlarmButtons()
        } else {
            setupTimerButtons()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAlarmButtons() {
        binding.reminderStop.beGone()

        val textColor = getProperTextColor()
        binding.reminderDraggable.applyColorFilter(textColor)

        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f

        binding.reminderDraggable.setOnTouchListener { v, event ->
            if (!isButtonMoving) {
                // Start moving the button when it's clicked
                moveButtonRandomly()
            } else {
                // "Catch" the button when it's clicked while moving
                stopButtonMovement()
                Toast.makeText(this@ReminderActivity, "Button Caught!", Toast.LENGTH_SHORT).show()
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    if (!didVibrate) {
                        binding.reminderDraggable.animate().x(initialDraggableX).withEndAction {
                        }

                        binding.reminderGuide.animate().alpha(1f).start()
                        swipeGuideFadeHandler.removeCallbacksAndMessages(null)
                        swipeGuideFadeHandler.postDelayed({
                            binding.reminderGuide.animate().alpha(0f).start()
                        }, 2000L)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    binding.reminderDraggable.x = Math.min(maxDragX, Math.max(minDragX, event.rawX - dragDownX))
                    if (binding.reminderDraggable.x >= maxDragX - 50f) {
                        if (!didVibrate) {
                            binding.reminderDraggable.performHapticFeedback()
                            didVibrate = true
                            finishActivity()
                        }

                        if (isOreoPlus()) {
                            notificationManager.cancelAll()
                        }
                    } else if (binding.reminderDraggable.x <= minDragX + 50f) {
                        if (!didVibrate) {
                            binding.reminderDraggable.performHapticFeedback()
                            didVibrate = true
                            snoozeAlarm()
                        }

                        if (isOreoPlus()) {
                            notificationManager.cancelAll()
                        }
                    }
                }
            }
            true
        }
    }

    private fun moveButtonRandomly() {
        isButtonMoving = true
        handler.postDelayed({
            // Generate random coordinates for the new position across the entire screen
            val newX = Random.nextInt(0, windowManager.defaultDisplay.width - binding.reminderDraggable.width)
            val newY = Random.nextInt(0, windowManager.defaultDisplay.height - binding.reminderDraggable.height)

            // Create a translation animation
            val translateAnimation =
                AnimationUtils.loadAnimation(this@ReminderActivity, R.anim.translate_animation)
            translateAnimation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    // Set the new position of the button after the animation ends
                    binding.reminderDraggable.translationX = newX.toFloat()
                    binding.reminderDraggable.translationY = newY.toFloat()
                    // Continue moving the button
                    handler.postDelayed({ moveButtonRandomly() }, 10) // 100 milliseconds delay
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })

            // Start the animation
            binding.reminderDraggable.startAnimation(translateAnimation)
        }, 50) // 100 milliseconds initial delay
    }

    override fun onBackPressed() {
        if (backPressCount < 200) {
            // Increase the count and show a message
            backPressCount++
        } else {
            // If the user pressed back 5 times, exit the app
            super.onBackPressed()
        }
    }

    private fun stopButtonMovement() {
        // Remove the callbacks to stop the movement
        handler.removeCallbacksAndMessages(null)
        isButtonMoving = true
    }

    private fun setupTimerButtons() {
        binding.reminderStop.background = resources.getColoredDrawableWithColor(R.drawable.circle_background_filled, getProperPrimaryColor())


        binding.reminderStop.setOnClickListener {
            finishActivity()
        }
    }

    private fun setupEffects() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initialAlarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 7

        val doVibrate = alarm?.vibrate ?: config.timerVibrate
        if (doVibrate && isOreoPlus()) {
            val pattern = LongArray(2) { 500 }
            vibrationHandler.postDelayed({
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }, 500)
        }

        val soundUri = if (alarm != null) {
            alarm!!.soundUri
        } else {
            config.timerSoundUri
        }

        if (soundUri != SILENT) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                    setDataSource(this@ReminderActivity, Uri.parse(soundUri))
                    isLooping = true
                    prepare()
                    start()
                }

                if (config.increaseVolumeGradually) {
                    scheduleVolumeIncrease(MIN_ALARM_VOLUME_FOR_INCREASING_ALARMS.toFloat(), initialAlarmVolume!!.toFloat(), 0)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun scheduleVolumeIncrease(lastVolume: Float, maxVolume: Float, delay: Long) {
        increaseVolumeHandler.postDelayed({
            val newLastVolume = (lastVolume + 0.1f).coerceAtMost(maxVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, newLastVolume.toInt(), 0)
            scheduleVolumeIncrease(newLastVolume, maxVolume, INCREASE_VOLUME_DELAY)
        }, delay)
    }

    private fun resetVolumeToInitialValue() {
        initialAlarmVolume?.apply {
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, this, 0)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == AlarmClock.ACTION_SNOOZE_ALARM) {
            val durationMinutes = intent.getIntExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, -1)
            if (durationMinutes == -1) {
                snoozeAlarm()
            } else {
                snoozeAlarm(durationMinutes)
            }
        } else {
            finishActivity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        increaseVolumeHandler.removeCallbacksAndMessages(null)
        maxReminderDurationHandler.removeCallbacksAndMessages(null)
        swipeGuideFadeHandler.removeCallbacksAndMessages(null)
        vibrationHandler.removeCallbacksAndMessages(null)
        if (!finished) {
            finishActivity()
            notificationManager.cancel(ALARM_NOTIF_ID)
        } else {
            destroyEffects()
        }
    }

    private fun destroyEffects() {
        if (config.increaseVolumeGradually) {
            resetVolumeToInitialValue()
        }

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun snoozeAlarm(overrideSnoozeDuration: Int? = null) {
        destroyEffects()
        if (overrideSnoozeDuration != null) {
            setupAlarmClock(alarm!!, overrideSnoozeDuration * MINUTE_SECONDS)
            wasAlarmSnoozed = true
            finishActivity()
        } else if (config.useSameSnooze) {
            setupAlarmClock(alarm!!, config.snoozeTime * MINUTE_SECONDS)
            wasAlarmSnoozed = true
            finishActivity()
        } else {
            showPickSecondsDialog(config.snoozeTime * MINUTE_SECONDS, true, cancelCallback = { finishActivity() }) {
                config.snoozeTime = it / MINUTE_SECONDS
                setupAlarmClock(alarm!!, it)
                wasAlarmSnoozed = true
                finishActivity()
            }
        }
    }

    private fun finishActivity() {
        if (!wasAlarmSnoozed && alarm != null) {
            cancelAlarmClock(alarm!!)
            if (alarm!!.days > 0) {
                scheduleNextAlarm(alarm!!, false)
            }
            if (alarm!!.days < 0) {
                if (alarm!!.oneShot) {
                    alarm!!.isEnabled = false
                    dbHelper.deleteAlarms(arrayListOf(alarm!!))
                } else {
                    dbHelper.updateAlarmEnabledState(alarm!!.id, false)
                }
                updateWidgets()
            }
        }

        finished = true
        destroyEffects()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun showOverLockscreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }
}
