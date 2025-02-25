/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.prefs.florisPreferenceModel
import dev.patrickgold.florisboard.app.res.stringRes
import dev.patrickgold.florisboard.debug.flogDebug
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.util.AndroidVersion

/**
 * Input feedback manager responsible to process and perform audio and haptic
 * feedback for user interactions based on the system and floris preferences.
 */
class InputFeedbackManager private constructor(private val ims: InputMethodService) {
    companion object {
        fun new(ims: InputMethodService) = InputFeedbackManager(ims)

        @Composable
        fun hasAmplitudeControl(): Boolean {
            val vibrator = LocalContext.current.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            return when {
                AndroidVersion.ATLEAST_O -> vibrator?.hasAmplitudeControl() ?: false
                else -> false
            }
        }

        @Composable
        fun generateVibrationStrengthErrorSummary(): String? {
            val vibrator = LocalContext.current.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            return when {
                AndroidVersion.ATLEAST_O -> when {
                    !(vibrator?.hasAmplitudeControl() ?: false) -> {
                        stringRes(R.string.pref__input_feedback__haptic_vibration_strength__summary_no_amplitude_ctrl)
                    }
                    else -> null
                }
                else -> {
                    stringRes(R.string.pref__input_feedback__haptic_vibration_strength__summary_unsupported_android_version)
                }
            }
        }
    }

    private val prefs by florisPreferenceModel()

    private val audioManager = ims.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator = ims.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val contentResolver = ims.contentResolver

    fun keyPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyPress.get()) performAudioFeedback(data, 1.0)
        if (prefs.inputFeedback.hapticFeatKeyPress.get()) performHapticFeedback(data, 1.0)
    }

    fun keyLongPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyLongPress.get()) performAudioFeedback(data, 0.7)
        if (prefs.inputFeedback.hapticFeatKeyLongPress.get()) performHapticFeedback(data, 0.4)
    }

    fun keyRepeatedAction(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyRepeatedAction.get()) performAudioFeedback(data, 0.4)
        if (prefs.inputFeedback.hapticFeatKeyRepeatedAction.get()) performHapticFeedback(data, 0.05)
    }

    fun gestureSwipe(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatGestureSwipe.get()) performAudioFeedback(data, 0.7)
        if (prefs.inputFeedback.hapticFeatGestureSwipe.get()) performHapticFeedback(data, 0.4)
    }

    fun gestureMovingSwipe(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatGestureMovingSwipe.get()) performAudioFeedback(data, 0.4)
        if (prefs.inputFeedback.hapticFeatGestureMovingSwipe.get()) performHapticFeedback(data, 0.05)
    }

    private fun systemPref(id: String): Boolean {
        if (contentResolver == null) return false
        return Settings.System.getInt(contentResolver, id, 0) != 0
    }

    private fun performAudioFeedback(data: KeyData, factor: Double) {
        if (audioManager == null) return
        if (!prefs.inputFeedback.audioEnabled.get()) return

        if (!prefs.inputFeedback.audioIgnoreSystemSettings.get()) {
            if (!systemPref(Settings.System.SOUND_EFFECTS_ENABLED)) return
        }

        val volume = (prefs.inputFeedback.audioVolume.get() * factor) / 100.0
        val effect = when (data.code) {
            KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        if (volume in 0.01..1.00) {
            flogDebug { "Perform audio with volume=$volume and effect=$effect" }
            audioManager.playSoundEffect(effect, volume.toFloat())
        }
    }

    private fun performHapticFeedback(data: KeyData, factor: Double) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (!prefs.inputFeedback.hapticEnabled.get()) return

        if (!prefs.inputFeedback.hapticIgnoreSystemSettings.get()) {
            if (!systemPref(Settings.System.HAPTIC_FEEDBACK_ENABLED)) return
        }

        if (!prefs.inputFeedback.hapticUseVibrator.get()) {
            val view = ims.window?.window?.decorView ?: return
            val hfc = if (factor < 1.0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            val didPerform = view.performHapticFeedback(hfc,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            if (didPerform) return
            // If not performed fall back to using the vibrator directly
        }

        val duration = prefs.inputFeedback.hapticVibrationDuration.get()
        if (duration != 0) {
            val effectiveDuration = (duration * factor).toLong().coerceAtLeast(1L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val strength = when {
                    vibrator.hasAmplitudeControl() -> prefs.inputFeedback.hapticVibrationStrength.get()
                    else -> VibrationEffect.DEFAULT_AMPLITUDE
                }
                if (strength != 0) {
                    val effectiveStrength = when {
                        vibrator.hasAmplitudeControl() -> (255.0 * ((strength * factor) / 100.0)).toInt().coerceIn(1, 255)
                        else -> strength
                    }
                    flogDebug { "Perform haptic with duration=$effectiveDuration and strength=$effectiveStrength" }
                    val effect = VibrationEffect.createOneShot(effectiveDuration, effectiveStrength)
                    vibrator.vibrate(effect)
                }
            } else {
                flogDebug { "Perform haptic with duration=$effectiveDuration" }
                @Suppress("DEPRECATION")
                vibrator.vibrate(effectiveDuration)
            }
        }
    }
}
