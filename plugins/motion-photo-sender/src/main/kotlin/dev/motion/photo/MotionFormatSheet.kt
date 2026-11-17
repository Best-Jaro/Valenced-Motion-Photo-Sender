package dev.motion.photo

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Material 3-inspired modal bottom sheet using platform Android views.
 * Native Material You colors are applied on Android 12+ without relying on
 * a new Material Components library inside the injected Valenced plugin.
 */
object MotionFormatSheet {
    private data class Choice(val symbol: String, val title: String, val subtitle: String, val mode: String)

    suspend fun show(activity: Activity): String = suspendCancellableCoroutine { continuation ->
        activity.runOnUiThread {
            val night = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            fun systemColor(name: String, fallback: Int): Int {
                if (Build.VERSION.SDK_INT < 31) return fallback
                val id = activity.resources.getIdentifier(name, "color", "android")
                if (id == 0) return fallback
                return try { activity.getColor(id) } catch (_: Exception) { fallback }
            }

            val surface = if (night) systemColor("system_neutral1_900", Color.rgb(33, 31, 38))
                else systemColor("system_neutral1_10", Color.rgb(250, 249, 255))
            val onSurface = if (night) Color.rgb(233, 227, 235) else Color.rgb(29, 27, 32)
            val onSurfaceVariant = if (night) Color.rgb(202, 196, 208) else Color.rgb(73, 69, 79)
            val primary = if (night) systemColor("system_accent1_200", Color.rgb(208, 188, 255))
                else systemColor("system_accent1_600", Color.rgb(103, 80, 164))
            val container = if (night) systemColor("system_neutral2_800", Color.rgb(49, 47, 55))
                else systemColor("system_neutral2_50", Color.rgb(243, 239, 247))
            val iconColor = if (night) systemColor("system_accent1_800", Color.rgb(74, 68, 88))
                else systemColor("system_accent1_100", Color.rgb(234, 221, 255))
            fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

            fun rounded(color: Int, radius: Int): GradientDrawable =
                GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(radius).toFloat()
                }

            fun text(value: String, sp: Float, color: Int, bold: Boolean = false): TextView =
                TextView(activity).apply {
                    this.text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    setTextColor(color)
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                }

            val dialog = Dialog(activity)
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(20))
                background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadii = floatArrayOf(
                        dp(28).toFloat(), dp(28).toFloat(),
                        dp(28).toFloat(), dp(28).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                }
            }

            val handle = View(activity).apply {
                background = rounded(onSurfaceVariant, 3)
                alpha = 0.5f
            }
            root.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(22)
            })

            root.addView(text("Send Motion Photo", 23f, onSurface, true),
                LinearLayout.LayoutParams(-1, dp(32)))
            root.addView(text("Choose a format for your animation", 14f, onSurfaceVariant),
                LinearLayout.LayoutParams(-1, dp(24)).apply { bottomMargin = dp(16) })

            val choices = listOf(
                Choice("▶", "Original video", "MP4  •  Fast, original quality and sound", "mp4"),
                Choice("◷", "Freeze frame", "MP4  •  Hold first video frame for 0.5 s", "mp4-freeze"),
                Choice("▣", "Photo cover", "MP4  •  Original photo for 0.5 s (slower)", "mp4-cover"),
                Choice("GIF", "Animated GIF", "No sound  •  Optimized color palette", "gif"),
                Choice("▧", "Still photo", "Send just the original image", "photo")
            )

            fun choose(mode: String) {
                if (continuation.isActive) continuation.resume(mode)
                dialog.dismiss()
            }

            choices.forEach { item ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = RippleDrawable(
                        ColorStateList.valueOf(primary and 0x00FFFFFF or 0x26000000),
                        rounded(container, 18),
                        rounded(Color.WHITE, 18),
                    )
                    isClickable = true
                    isFocusable = true
                    contentDescription = item.title + ". " + item.subtitle
                    setOnClickListener { choose(item.mode) }
                }
                val icon = text(item.symbol, if (item.symbol == "GIF") 12f else 21f, primary, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(iconColor, 14)
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(12)
                })
                val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(text(item.title, 16f, onSurface, true))
                labels.addView(text(item.subtitle, 12f, onSurfaceVariant),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                root.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }

            val cancel = text("Cancel", 14f, primary, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22000000),
                    rounded(surface, 14), rounded(Color.WHITE, 14))
                setOnClickListener { choose("cancel") }
            }
            root.addView(cancel, LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(4)
            })

            dialog.setOnCancelListener {
                if (continuation.isActive) continuation.resume("cancel")
            }
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.35f)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
