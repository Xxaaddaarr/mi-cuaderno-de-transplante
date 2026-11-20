package expo.modules.mctalarm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla de alarma, como la del despertador: se muestra sobre la pantalla de bloqueo con la toma
 * y dos botones grandes. Al responder, detiene la alarma, guarda la respuesta y abre la app para
 * que la registre.
 */
class AlarmActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (!handleAction(intent)) render(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (!handleAction(intent)) render(intent)
  }

  private fun handleAction(i: Intent?): Boolean {
    val action = i?.getStringExtra("action") ?: return false
    AlarmService.stop(this)
    AlarmStore.savePendingAction(this, action, i.getStringExtra("k"), i.getStringExtra("t"))
    packageManager.getLaunchIntentForPackage(packageName)?.let {
      it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      startActivity(it)
    }
    finish()
    return true
  }

  private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

  private fun pill(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18f).toFloat()
    setColor(color)
  }

  private fun render(i: Intent?) {
    val title = i?.getStringExtra("title")?.takeIf { it.isNotEmpty() } ?: "Hora de tu medicamento"
    val body = i?.getStringExtra("body") ?: ""

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.parseColor("#0A1019"))
      setPadding(dp(28f), dp(48f), dp(28f), dp(48f))
    }
    fun text(s: String, sp: Float, color: String, bold: Boolean = false, bottom: Float = 12f) =
      TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottom) }
      }
    root.addView(text("MI CUADERNO TX", 13f, "#96A1B4", true, 20f))
    root.addView(text(title, 30f, "#FFFFFF", true, 16f))
    if (body.isNotEmpty()) root.addView(text(body, 19f, "#E5EAF3", false, 40f))

    fun button(label: String, bg: String, fg: String, action: String) = Button(this).apply {
      text = label
      isAllCaps = false
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
      setTextColor(Color.parseColor(fg))
      typeface = Typeface.DEFAULT_BOLD
      background = pill(Color.parseColor(bg))
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72f)).apply {
        bottomMargin = dp(16f)
      }
      setOnClickListener { handleAction(Intent(intent).putExtra("action", action)) }
    }
    root.addView(button("Tomé", "#52C99C", "#06281D", "taken"))
    root.addView(button("Posponer 15 min", "#263145", "#FFFFFF", "snooze"))
    setContentView(root)
  }
}
