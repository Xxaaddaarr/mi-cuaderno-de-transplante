package expo.modules.mctalarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda y programa las alarmas de las tomas con AlarmManager.setAlarmClock(),
 * que Android entrega a la hora exacta aunque el celular esté en ahorro de batería.
 * La lista se guarda para volver a programarla si el celular se reinicia, se actualiza la app
 * o cambia la hora. Cada evento (programada, sonó, falló) queda en un registro para diagnosticar.
 */
object AlarmStore {
  private const val PREFS = "mct_alarm"
  private const val KEY_ITEMS = "items"
  private const val KEY_PENDING = "pending"
  private const val KEY_LOG = "log"
  private const val KEY_INFO = "info"
  private const val LOG_MAX = 80
  private const val RC_BASE = 7000
  const val ACTION_FIRE = "expo.modules.mctalarm.FIRE"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // Cada alarma tiene su propio código (posición en la lista): nunca dos alarmas se pisan.
  private fun firePendingIntent(c: Context, item: JSONObject?, id: String, rc: Int): PendingIntent {
    val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
    if (item != null) {
      i.putExtra("id", id)
      i.putExtra("title", item.optString("title"))
      i.putExtra("body", item.optString("body"))
      i.putExtra("k", item.optString("k"))
      i.putExtra("t", item.optString("t"))
      i.putExtra("at", item.optLong("at", 0L))
    }
    return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  fun canExact(c: Context): Boolean {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
  }

  /** Reemplaza todas las alarmas por las de esta lista (JSON: [{id, at, title, body, k, t}]). */
  @Synchronized
  fun scheduleAll(c: Context, json: String, reason: String = "app") {
    cancelAll(c)
    val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
    val now = System.currentTimeMillis()
    val keep = JSONArray()
    var rc = RC_BASE
    var first = 0L
    var last = 0L
    for (n in 0 until arr.length()) {
      val item = arr.optJSONObject(n) ?: continue
      val at = item.optLong("at", 0L)
      val id = item.optString("id")
      if (at <= now || id.isEmpty()) continue
      item.put("rc", rc)
      setAlarm(c, at, firePendingIntent(c, item, id, rc))
      keep.put(item)
      rc++
      if (first == 0L || at < first) first = at
      if (at > last) last = at
    }
    prefs(c).edit()
      .putString(KEY_ITEMS, keep.toString())
      .putString(KEY_INFO, JSONObject().put("count", keep.length()).put("first", first).put("last", last)
        .put("exact", canExact(c)).put("ts", now).put("reason", reason).toString())
      .apply()
    log(c, "programadas", "", first, "${keep.length()} alarmas · ${if (canExact(c)) "exactas" else "NO exactas"} · $reason")
  }

  /** Programa una alarma suelta (para la prueba) sin tocar las demás. */
  fun scheduleOne(c: Context, json: String) {
    val item = try { JSONObject(json) } catch (e: Exception) { return }
    val id = item.optString("id").ifEmpty { "test" }
    val at = item.optLong("at", System.currentTimeMillis() + 5000L)
    item.put("at", at)
    setAlarm(c, at, firePendingIntent(c, item, id, RC_BASE - 1))
    log(c, "prueba", id, at, if (canExact(c)) "exacta" else "NO exacta")
  }

  @Synchronized
  fun cancelAll(c: Context) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val arr = try { JSONArray(prefs(c).getString(KEY_ITEMS, "[]")) } catch (e: Exception) { JSONArray() }
    for (n in 0 until arr.length()) {
      val o = arr.optJSONObject(n) ?: continue
      val id = o.optString("id")
      if (id.isEmpty()) continue
      // Las listas guardadas por versiones anteriores usaban el código del id.
      val rc = if (o.has("rc")) o.optInt("rc") else id.hashCode()
      val pi = firePendingIntent(c, null, id, rc)
      am.cancel(pi)
      pi.cancel()
    }
    prefs(c).edit().remove(KEY_ITEMS).apply()
  }

  fun rescheduleStored(c: Context, reason: String) {
    val json = prefs(c).getString(KEY_ITEMS, null) ?: return
    scheduleAll(c, json, reason)
  }

  private fun setAlarm(c: Context, at: Long, pi: PendingIntent) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    try {
      if (canExact(c)) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()
        val show = PendingIntent.getActivity(c, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      } else {
        am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
      }
    } catch (e: SecurityException) {
      // Sin permiso de alarma exacta: lo más cercano que Android permite.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      else am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }
  }

  /** Registro de lo que pasó con las alarmas (últimos 80 eventos). */
  @Synchronized
  fun log(c: Context, ev: String, id: String, at: Long, info: String) {
    val arr = try { JSONArray(prefs(c).getString(KEY_LOG, "[]")) } catch (e: Exception) { JSONArray() }
    arr.put(JSONObject().put("ts", System.currentTimeMillis()).put("ev", ev).put("id", id).put("at", at).put("info", info))
    val out = JSONArray()
    val from = if (arr.length() > LOG_MAX) arr.length() - LOG_MAX else 0
    for (n in from until arr.length()) out.put(arr.get(n))
    prefs(c).edit().putString(KEY_LOG, out.toString()).apply()
  }

  fun getLog(c: Context): String = prefs(c).getString(KEY_LOG, "[]") ?: "[]"

  /** Estado de todo lo que necesita la alarma para sonar. */
  fun diagnostics(c: Context): String {
    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    val info = try { JSONObject(prefs(c).getString(KEY_INFO, "{}")) } catch (e: Exception) { JSONObject() }
    val items = try { JSONArray(prefs(c).getString(KEY_ITEMS, "[]")) } catch (e: Exception) { JSONArray() }
    val now = System.currentTimeMillis()
    var next = 0L
    var future = 0
    for (n in 0 until items.length()) {
      val at = items.optJSONObject(n)?.optLong("at", 0L) ?: 0L
      if (at > now) { future++; if (next == 0L || at < next) next = at }
    }
    return JSONObject()
      .put("exact", canExact(c))
      .put("fullScreen", if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true)
      .put("notifications", nm.areNotificationsEnabled())
      .put("battery", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(c.packageName) else true)
      .put("dndTotal", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE)
      .put("future", future)
      .put("next", next)
      .put("last", info.optLong("last", 0L))
      .put("scheduledAt", info.optLong("ts", 0L))
      .put("sdk", Build.VERSION.SDK_INT)
      .put("maker", Build.MANUFACTURER ?: "")
      .toString()
  }

  /** Lo que el paciente tocó en la alarma (Tomé / Posponer), para que la app lo registre al abrirse. */
  fun savePendingAction(c: Context, action: String, k: String?, t: String?) {
    val o = JSONObject()
      .put("action", action)
      .put("k", k ?: "")
      .put("t", t ?: "")
      .put("ts", System.currentTimeMillis())
    prefs(c).edit().putString(KEY_PENDING, o.toString()).commit()
    log(c, if (action == "taken") "tomé" else "posponer", "${k ?: ""}|${t ?: ""}", 0L, "")
  }

  fun takePendingAction(c: Context): String? {
    val s = prefs(c).getString(KEY_PENDING, null) ?: return null
    prefs(c).edit().remove(KEY_PENDING).apply()
    return try {
      val o = JSONObject(s)
      if (System.currentTimeMillis() - o.optLong("ts") > 18 * 3600_000L) null else s
    } catch (e: Exception) {
      null
    }
  }
}
