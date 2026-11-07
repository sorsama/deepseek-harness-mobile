@file:SuppressLint("InlinedApi")

package com.labteto.dshmobile.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.theme.HapticStrength

/**
 * Haptics, part two: asking the phone what it can do, and playing what [HapticPlanner] chose.
 *
 * One per process (see [get]). The phone's capabilities do not change while the app runs, so they
 * are probed once; the system "Touch feedback" setting does, so it is re-read, at most once a second.
 *
 * Vibrations are played with touch usage ([VibrationAttributes.USAGE_TOUCH] on API 33+, the
 * sonification audio usage before that), so the system treats them as touch feedback: they follow
 * the phone's touch feedback setting and strength and stay quiet in the situations the system
 * keeps touch feedback quiet in.
 *
 * Nothing here is allowed to throw into a gesture. If a direct vibrator call fails, the engine
 * stops making them for the rest of the run and plays the same event through
 * [View.performHapticFeedback] instead.
 */
class HapticEngine private constructor(context: Context) {
    private val appContext = context.applicationContext

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }

    private val hardware: HapticCaps by lazy { probe() }

    @Volatile private var directOk = true
    @Volatile private var cached: HapticCaps? = null
    @Volatile private var cachedAt = 0L

    /** What this phone can do right now. */
    val caps: HapticCaps
        get() {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - cachedAt < TOUCH_SETTING_TTL_MS && it.directOk == directOk) return it }
            val fresh = hardware.copy(touchFeedbackOn = systemTouchFeedbackOn(), directOk = directOk)
            cached = fresh
            cachedAt = now
            return fresh
        }

    /** How the phone plays the app's haptics. */
    val tier: HapticTier get() = HapticPlanner.tier(caps)

    /**
     * The system "Touch feedback" setting, read fresh. Off means the user turned off the
     * vibration that goes with taps, so the app plays nothing and Settings says why.
     */
    @Suppress("DEPRECATION")
    fun systemTouchFeedbackOn(): Boolean = runCatching {
        Settings.System.getInt(appContext.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)

    /**
     * Plays [event]. [view] is only used by the tier that goes through the View. Returns whether
     * something was handed to the phone.
     */
    fun play(view: View, event: HapticEvent, strength: HapticStrength, weight: Float = 1f): Boolean {
        val caps = caps
        val plan = HapticPlanner.plan(event, caps, strength, weight)
        var played = execute(view, plan)
        if (!played && plan !is HapticPlan.Silent && plan !is HapticPlan.ViewConstant && !directOk) {
            // The direct call just failed: say it through the View this once.
            played = execute(view, HapticPlanner.plan(event, caps.copy(directOk = false), strength, weight))
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=$event weight=$weight strength=${strength.key} tier=${HapticPlanner.tier(caps)} plan=$plan played=$played")
        }
        return played
    }

    private fun execute(view: View, plan: HapticPlan): Boolean = when (plan) {
        HapticPlan.Silent -> false
        is HapticPlan.ViewConstant -> runCatching { view.performHapticFeedback(plan.constant) }.getOrDefault(false)
        else -> vibrate(plan)
    }

    private fun vibrate(plan: HapticPlan): Boolean {
        val target = vibrator ?: return false
        return try {
            val effect = effectFor(plan) ?: return false
            if (Build.VERSION.SDK_INT >= 33) {
                target.vibrate(effect, touchAttributes33())
            } else {
                @Suppress("DEPRECATION")
                target.vibrate(effect, touchAudioAttributes)
            }
            true
        } catch (t: Throwable) {
            // A security exception (permission), an effect the phone rejects, a dead service:
            // none of them is worth crashing a drag for. Stop trying this route.
            Log.w(TAG, "direct vibration failed, falling back to the view: $t")
            directOk = false
            false
        }
    }

    private fun effectFor(plan: HapticPlan): VibrationEffect? = when (plan) {
        is HapticPlan.OneShot -> VibrationEffect.createOneShot(plan.millis, plan.amplitude)
        is HapticPlan.Predefined -> if (Build.VERSION.SDK_INT >= 29) predefined(plan.preset) else null
        is HapticPlan.Composition -> if (Build.VERSION.SDK_INT >= 30) composition(plan) else null
        else -> null
    }

    @RequiresApi(29)
    private fun predefined(preset: HapticPreset): VibrationEffect = VibrationEffect.createPredefined(
        when (preset) {
            HapticPreset.Tick -> VibrationEffect.EFFECT_TICK
            HapticPreset.Click -> VibrationEffect.EFFECT_CLICK
            HapticPreset.HeavyClick -> VibrationEffect.EFFECT_HEAVY_CLICK
            HapticPreset.DoubleClick -> VibrationEffect.EFFECT_DOUBLE_CLICK
        },
    )

    @RequiresApi(30)
    private fun composition(plan: HapticPlan.Composition): VibrationEffect {
        val builder = VibrationEffect.startComposition()
        plan.steps.forEach { builder.addPrimitive(primitiveId(it.primitive), it.scale, it.delayMs) }
        return builder.compose()
    }

    @RequiresApi(30)
    private fun primitiveId(primitive: HapticPrimitive): Int = when (primitive) {
        HapticPrimitive.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticPrimitive.QuickRise -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        HapticPrimitive.QuickFall -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        HapticPrimitive.SlowRise -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        HapticPrimitive.LowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        HapticPrimitive.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
    }

    private fun probe(): HapticCaps {
        val target = vibrator
        val has = runCatching { target?.hasVibrator() == true }.getOrDefault(false)
        val amplitude = has && runCatching { target?.hasAmplitudeControl() == true }.getOrDefault(false)
        val primitives = if (has && target != null && Build.VERSION.SDK_INT >= 30) {
            runCatching { supportedPrimitives(target) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val found = HapticCaps(
            sdk = Build.VERSION.SDK_INT,
            hasVibrator = has,
            hasAmplitudeControl = amplitude,
            primitives = primitives,
        )
        if (BuildConfig.DEBUG) Log.i(TAG, "probed $found -> tier ${HapticPlanner.tier(found)}")
        return found
    }

    // Lint cannot see that every id comes from primitiveId(), which only returns the platform's own constants.
    @SuppressLint("WrongConstant")
    @RequiresApi(30)
    private fun supportedPrimitives(target: Vibrator): Set<HapticPrimitive> {
        // Only primitives this release has: asking about a constant it does not know is an error.
        val known = HapticPrimitive.entries.filter { Build.VERSION.SDK_INT >= it.minSdk }
        val answers = target.arePrimitivesSupported(*known.map(::primitiveId).toIntArray())
        return known.filterIndexed { index, _ -> answers.getOrNull(index) == true }.toSet()
    }

    // Held as Any so this class never names a type older phones lack in a field.
    private var touchAttributes: Any? = null

    @RequiresApi(33)
    private fun touchAttributes33(): VibrationAttributes =
        (touchAttributes as? VibrationAttributes) ?: VibrationAttributes
            .createForUsage(VibrationAttributes.USAGE_TOUCH)
            .also { touchAttributes = it }

    private val touchAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    companion object {
        private const val TAG = "DsHaptics"
        private const val TOUCH_SETTING_TTL_MS = 1000L

        @Volatile private var instance: HapticEngine? = null

        fun get(context: Context): HapticEngine = instance ?: synchronized(this) {
            instance ?: HapticEngine(context).also { instance = it }
        }
    }
}
