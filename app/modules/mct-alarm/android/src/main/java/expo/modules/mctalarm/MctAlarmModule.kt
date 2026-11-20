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
    Function("schedule") { json: String -> AlarmStore.scheduleAll(ctx, json) }

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
