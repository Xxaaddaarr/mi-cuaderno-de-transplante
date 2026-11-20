package expo.modules.mctalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Llega a la hora de la toma y arranca el servicio que hace sonar la alarma. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, intent: Intent) {
    val s = Intent(c, AlarmService::class.java).setAction(AlarmService.ACTION_RING)
    intent.extras?.let { s.putExtras(it) }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s) else c.startService(s)
    } catch (e: Exception) {
      // Si Android no deja arrancar el servicio, al menos se muestra la notificación con los botones.
      AlarmService.showFallback(c, intent)
    }
  }
}

/** Después de reiniciar el celular o actualizar la app, vuelve a programar las alarmas guardadas. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, intent: Intent) {
    AlarmStore.rescheduleStored(c)
  }
}
