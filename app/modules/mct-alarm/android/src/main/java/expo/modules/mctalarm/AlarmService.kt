package expo.modules.mctalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Hace sonar la alarma como un despertador: reproduce el tono de alarma del celular en bucle por el
 * canal de ALARMA (no el de notificaciones), sube ese volumen si está bajo, vibra y muestra una
 * notificación fija con pantalla completa y los botones Tomé / Posponer. Suena hasta 2 minutos;
 * la notificación se queda hasta que el paciente responde.
 */
class AlarmService : Service() {
  companion object {
    const val ACTION_RING = "expo.modules.mctalarm.RING"
    const val CHANNEL = "mct-alarma-v1"
    // Respaldo si el servicio no puede arrancar: la notificación misma suena por el canal de alarma.
    const val CHANNEL_FALLBACK = "mct-alarma-respaldo-v1"
    const val NOTIF_ID = 4201
    private const val RING_MS = 120_000L

    fun stop(c: Context) {
      c.stopService(Intent(c, AlarmService::class.java))
      (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    private fun ensureChannel(c: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL) != null) return
      val ch = NotificationChannel(CHANNEL, "Alarma de medicamentos", NotificationManager.IMPORTANCE_HIGH)
      ch.description = "Suena como despertador a la hora de cada toma"
      ch.setSound(null, null) // el sonido lo pone el servicio, por el canal de alarma
      ch.enableVibration(false)
      ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      nm.createNotificationChannel(ch)
    }

    private fun ensureFallbackChannel(c: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL_FALLBACK) != null) return
      val ch = NotificationChannel(CHANNEL_FALLBACK, "Alarma de medicamentos (respaldo)", NotificationManager.IMPORTANCE_HIGH)
      ch.description = "Suena si la alarma principal no pudo arrancar"
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      ch.setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      ch.enableVibration(true)
      ch.vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 1200)
      ch.setBypassDnd(true)
      ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      nm.createNotificationChannel(ch)
    }

    private fun activityIntent(c: Context, extras: Bundle?, action: String?, code: Int): PendingIntent {
      val i = Intent(c, AlarmActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      extras?.let { i.putExtras(it) }
      if (action != null) i.putExtra("action", action) else i.removeExtra("action")
      return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun smallIcon(c: Context): Int {
      val id = c.resources.getIdentifier("notification_icon", "drawable", c.packageName)
      return if (id != 0) id else c.applicationInfo.icon
    }

    @Suppress("DEPRECATION")
    fun buildNotification(c: Context, extras: Bundle?, fallback: Boolean = false): Notification {
      ensureChannel(c)
      if (fallback) ensureFallbackChannel(c)
      val title = extras?.getString("title")?.takeIf { it.isNotEmpty() } ?: "Hora de tu medicamento"
      val body = extras?.getString("body") ?: ""
      val full = activityIntent(c, extras, null, 10)
      val taken = activityIntent(c, extras, "taken", 11)
      val snooze = activityIntent(c, extras, "snooze", 12)
      val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(c, if (fallback) CHANNEL_FALLBACK else CHANNEL) else Notification.Builder(c)
      b.setSmallIcon(smallIcon(c))
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(Notification.BigTextStyle().bigText(body))
        .setCategory(Notification.CATEGORY_ALARM)
        .setOngoing(true)
        .setAutoCancel(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setFullScreenIntent(full, true)
        .setContentIntent(full)
        .addAction(Notification.Action.Builder(0, "Tomé", taken).build())
        .addAction(Notification.Action.Builder(0, "Posponer 15 min", snooze).build())
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX)
      return b.build()
    }

    fun showFallback(c: Context, intent: Intent) {
      val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.notify(NOTIF_ID, buildNotification(c, intent.extras, true))
    }
  }

  private var player: MediaPlayer? = null
  private var tone: ToneGenerator? = null
  private var vibrator: Vibrator? = null
  private var wake: PowerManager.WakeLock? = null
  private var savedVolume = -1
  private val handler = Handler(Looper.getMainLooper())
  private val autoStop = Runnable { stopRinging(); detachAndStop() }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val n = buildNotification(this, intent?.extras)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(NOTIF_ID, n)
      }
    } catch (e: Exception) {
      // Android no dejó pasar a primer plano: la notificación de respaldo suena sola.
      AlarmStore.log(this, "respaldo", intent?.getStringExtra("id") ?: "", 0L, e.javaClass.simpleName)
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(this, intent?.extras, true))
      stopSelf()
      return START_NOT_STICKY
    }
    startRinging()
    AlarmStore.log(this, if (player != null) "sonó" else if (tone != null) "sonó (pitido)" else "SIN SONIDO", intent?.getStringExtra("id") ?: "", 0L,
      "volumen alarma " + (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_ALARM))
    handler.removeCallbacks(autoStop)
    handler.postDelayed(autoStop, RING_MS)
    return START_NOT_STICKY
  }

  @Suppress("DEPRECATION")
  private fun startRinging() {
    if (player != null) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mct:alarm").apply { acquire(RING_MS + 10_000L) }

    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    try {
      val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
      val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
      val target = (max * 0.85).toInt().coerceAtLeast(1)
      if (cur < target) {
        savedVolume = cur
        am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
      }
    } catch (e: Exception) {
      savedVolume = -1
    }

    // Tono de alarma del celular; si falla, el de llamada o el de notificación; si todo falla, pitidos.
    val uris = listOfNotNull(
      RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    )
    for (uri in uris) {
      if (player != null) break
      player = tryPlay(uri)
    }
    if (player == null) {
      try {
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).apply { startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, RING_MS.toInt()) }
      } catch (e: Exception) { tone = null }
    }

    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    val pattern = longArrayOf(0, 800, 400, 800, 400, 1200, 800)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator?.vibrate(
        VibrationEffect.createWaveform(pattern, 1),
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
      )
    } else {
      vibrator?.vibrate(pattern, 1)
    }
  }

  private fun tryPlay(uri: Uri): MediaPlayer? {
    val mp = MediaPlayer()
    return try {
      mp.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      )
      mp.setDataSource(this, uri)
      mp.isLooping = true
      mp.prepare()
      mp.start()
      mp
    } catch (e: Exception) {
      mp.release()
      null
    }
  }

  private fun stopRinging() {
    handler.removeCallbacks(autoStop)
    try { tone?.stopTone(); tone?.release() } catch (e: Exception) {}
    tone = null
    try { player?.stop() } catch (e: Exception) {}
    player?.release()
    player = null
    vibrator?.cancel()
    vibrator = null
    if (savedVolume >= 0) {
      try {
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
      } catch (e: Exception) {}
      savedVolume = -1
    }
    wake?.let { if (it.isHeld) it.release() }
    wake = null
  }

  @Suppress("DEPRECATION")
  private fun detachAndStop() {
    // Deja de sonar pero la notificación con los botones se queda.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_DETACH) else stopForeground(false)
    stopSelf()
  }

  override fun onDestroy() {
    stopRinging()
    super.onDestroy()
  }
}
