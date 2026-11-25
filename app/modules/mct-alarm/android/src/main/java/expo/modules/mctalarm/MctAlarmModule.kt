package expo.modules.mctalarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** Puente JS ↔ Android para las alarmas tipo despertador de las tomas. */
class MctAlarmModule : Module() {
  private val ctx: Context
    get() = appContext.reactContext?.applicationContext ?: throw IllegalStateException("Sin contexto de Android")

  override fun definition() = ModuleDefinition {
    Name("MctAlarm")

    // Reemplaza todas las alarmas: JSON [{id, at, title, body, k, t}]
    Function("schedule") { json: String -> AlarmStore.scheduleAll(ctx, json, "app") }

    // Una alarma suelta, sin tocar las demás (para probar)
    Function("scheduleOne") { json: String -> AlarmStore.scheduleOne(ctx, json) }

    Function("cancelAll") { AlarmStore.cancelAll(ctx) }

    // Detiene la alarma que esté sonando y quita su notificación
    Function("stop") { AlarmService.stop(ctx) }

    // Respuesta pendiente desde la pantalla de alarma: {"action","k","t","ts"} o null
    Function("takePendingAction") { AlarmStore.takePendingAction(ctx) }

    Function("canFullScreen") {
      if (Build.VERSION.SDK_INT >= 34) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
      } else {
        true
      }
    }

    // Estado de permisos, batería y próximas alarmas (JSON)
    Function("diagnostics") { AlarmStore.diagnostics(ctx) }

    // Registro de lo que pasó con las alarmas (JSON, últimos 80 eventos)
    Function("getLog") { AlarmStore.getLog(ctx) }

    // Abre la pantalla exacta de Android para arreglar cada cosa
    Function("openSettings") { what: String ->
      val pkg = Uri.parse("package:" + ctx.packageName)
      val i = when (what) {
        "exact" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        "fullScreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        "battery" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        "dnd" -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
      }
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        ctx.startActivity(i)
      } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }

    Function("openFullScreenSettings") {
      val i = if (Build.VERSION.SDK_INT >= 34) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
      } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
      }
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    }
  }
}
