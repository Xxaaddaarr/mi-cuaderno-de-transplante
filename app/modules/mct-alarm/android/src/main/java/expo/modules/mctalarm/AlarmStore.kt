package expo.modules.mctalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda y programa las alarmas de las tomas con AlarmManager.setAlarmClock(),
 * que Android entrega a la hora exacta aunque el celular esté en ahorro de batería.
 * La lista se guarda para volver a programarla si el celular se reinicia.
 */
object AlarmStore {
  private const val PREFS = "mct_alarm"
  private const val KEY_ITEMS = "items"
  private const val KEY_PENDING = "pending"
  const val ACTION_FIRE = "expo.modules.mctalarm.FIRE"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun firePendingIntent(c: Context, item: JSONObject?, id: String): PendingIntent {
    val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
    if (item != null) {
      i.putExtra("id", id)
      i.putExtra("title", item.optString("title"))
      i.putExtra("body", item.optString("body"))
      i.putExtra("k", item.optString("k"))
      i.putExtra("t", item.optString("t"))
    }
    return PendingIntent.getBroadcast(
      c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  /** Reemplaza todas las alarmas por las de esta lista (JSON: [{id, at, title, body, k, t}]). */
  fun scheduleAll(c: Context, json: String) {
    cancelAll(c)
    val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
    val now = System.currentTimeMillis()
    val keep = JSONArray()
    for (n in 0 until arr.length()) {
      val item = arr.optJSONObject(n) ?: continue
      val at = item.optLong("at", 0L)
      val id = item.optString("id")
      if (at <= now || id.isEmpty()) continue
      setAlarm(c, at, firePendingIntent(c, item, id))
      keep.put(item)
    }
    prefs(c).edit().putString(KEY_ITEMS, keep.toString()).apply()
  }

  /** Programa una alarma suelta (para la prueba) sin tocar las demás. */
  fun scheduleOne(c: Context, json: String) {
    val item = try { JSONObject(json) } catch (e: Exception) { return }
    val id = item.optString("id").ifEmpty { "test" }
    setAlarm(c, item.optLong("at", System.currentTimeMillis() + 5000L), firePendingIntent(c, item, id))
  }

  fun cancelAll(c: Context) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val arr = try { JSONArray(prefs(c).getString(KEY_ITEMS, "[]")) } catch (e: Exception) { JSONArray() }
    for (n in 0 until arr.length()) {
      val id = arr.optJSONObject(n)?.optString("id") ?: continue
      if (id.isEmpty()) continue
      val pi = firePendingIntent(c, null, id)
      am.cancel(pi)
      pi.cancel()
    }
    prefs(c).edit().remove(KEY_ITEMS).apply()
  }

  fun rescheduleStored(c: Context) {
    val json = prefs(c).getString(KEY_ITEMS, null) ?: return
    scheduleAll(c, json)
  }

  private fun setAlarm(c: Context, at: Long, pi: PendingIntent) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    try {
      val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
      if (canExact) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()
        val show = PendingIntent.getActivity(
          c, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      } else {
        am.set(AlarmManager.RTC_WAKEUP, at, pi)
      }
    } catch (e: SecurityException) {
      am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }
  }

  /** Lo que el paciente tocó en la alarma (Tomé / Posponer), para que la app lo registre al abrirse. */
  fun savePendingAction(c: Context, action: String, k: String?, t: String?) {
    val o = JSONObject()
      .put("action", action)
      .put("k", k ?: "")
      .put("t", t ?: "")
      .put("ts", System.currentTimeMillis())
    prefs(c).edit().putString(KEY_PENDING, o.toString()).commit()
  }

  fun takePendingAction(c: Context): String? {
    val s = prefs(c).getString(KEY_PENDING, null) ?: return null
    prefs(c).edit().remove(KEY_PENDING).apply()
    return try {
      val o = JSONObject(s)
      if (System.currentTimeMillis() - o.optLong("ts") > 6 * 3600_000L) null else s
    } catch (e: Exception) {
      null
    }
  }
}
