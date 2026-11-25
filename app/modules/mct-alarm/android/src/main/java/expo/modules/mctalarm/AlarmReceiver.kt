package expo.modules.mctalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Llega a la hora de la toma y arranca el servicio que hace sonar la alarma. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, intent: Intent) {
    val id = intent.getStringExtra("id") ?: ""
    val at = intent.getLongExtra("at", 0L)
    val late = if (at > 0) (System.currentTimeMillis() - at) / 1000 else 0
    AlarmStore.log(c, "llegó", id, at, if (late > 60) "con ${late / 60} min de retraso" else "a tiempo")
    val s = Intent(c, AlarmService::class.java).setAction(AlarmService.ACTION_RING)
    intent.extras?.let { s.putExtras(it) }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s) else c.startService(s)
    } catch (e: Exception) {
      // Si Android no deja arrancar el servicio, igual suena: notificación por el canal de alarma con sonido.
      AlarmStore.log(c, "respaldo", id, at, e.javaClass.simpleName)
      AlarmService.showFallback(c, intent)
    }
  }
}

/** Al reiniciar el celular, actualizar la app o cambiar la hora/zona horaria, vuelve a programar las alarmas. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, intent: Intent) {
    val reason = when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> "reinicio"
      Intent.ACTION_MY_PACKAGE_REPLACED -> "actualización"
      Intent.ACTION_TIME_CHANGED -> "cambio de hora"
      Intent.ACTION_TIMEZONE_CHANGED -> "cambio de zona horaria"
      else -> intent.action ?: "sistema"
    }
    AlarmStore.rescheduleStored(c, reason)
  }
}
