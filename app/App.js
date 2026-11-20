// Mi Cuaderno Tx · app de Android
// Muestra la misma página web (incluida en la app para que funcione sin internet) y le suma
// alarmas nativas: la página manda la lista de tomas próximas y la app las programa como
// alarmas de reloj (setAlarmClock), que Android no retrasa por ahorro de batería.
import { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, Linking, Platform, StyleSheet, useColorScheme } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { WebView } from 'react-native-webview';
import * as Notifications from 'expo-notifications';
import * as Updates from 'expo-updates';
import webapp from './src/webapp';

const SITE = 'https://xxaaddaarr.github.io/mi-cuaderno-de-transplante/';
const CH_DOSES = 'tomas-v1';
const CH_INFO = 'avisos-v1';
const CATEGORY = 'dosis';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

async function setupNotifications() {
  if (Platform.OS !== 'android') return;
  await Notifications.setNotificationChannelAsync(CH_DOSES, {
    name: 'Tomas de medicamentos',
    description: 'Alarma a la hora de cada toma y recordatorios hasta que confirmes',
    importance: Notifications.AndroidImportance.MAX,
    sound: 'default',
    vibrationPattern: [0, 600, 300, 600, 300, 900],
    lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    audioAttributes: {
      usage: Notifications.AndroidAudioUsage.ALARM,
      contentType: Notifications.AndroidAudioContentType.SONIFICATION,
    },
  });
  await Notifications.setNotificationChannelAsync(CH_INFO, {
    name: 'Avisos del tratamiento',
    description: 'Dosaje de tacrolimus y otros recordatorios',
    importance: Notifications.AndroidImportance.HIGH,
    sound: 'default',
  });
  await Notifications.setNotificationCategoryAsync(CATEGORY, [
    { identifier: 'taken', buttonTitle: 'Tomé', options: { opensAppToForeground: true } },
    { identifier: 'snooze', buttonTitle: 'Posponer 15 min', options: { opensAppToForeground: true } },
  ]);
  await Notifications.requestPermissionsAsync();
}

// Reprograma todo en orden: cancelar y volver a crear, sin dos tandas a la vez.
let queue = Promise.resolve();
function applySchedule(items) {
  queue = queue
    .then(async () => {
      await Notifications.cancelAllScheduledNotificationsAsync();
      const now = Date.now();
      for (const it of items) {
        if (!it || !(it.at > now)) continue;
        await Notifications.scheduleNotificationAsync({
          identifier: String(it.id).replace(/[^\w-]/g, '_'),
          content: {
            title: it.title,
            body: it.body,
            data: it.data || {},
            categoryIdentifier: it.info ? undefined : CATEGORY,
            sound: 'default',
            priority: Notifications.AndroidNotificationPriority.MAX,
            // La alarma de la toma no se puede deslizar: se queda hasta tocar Tomé o Posponer.
            sticky: !it.info,
          },
          trigger: {
            type: Notifications.SchedulableTriggerInputTypes.DATE,
            date: it.at,
            channelId: it.info ? CH_INFO : CH_DOSES,
            delivery: it.info ? 'bestEffort' : 'alarmClock',
          },
        });
      }
    })
    .catch((e) => console.warn('schedule', e));
}

function testAlarm() {
  Notifications.scheduleNotificationAsync({
    content: {
      title: 'Prueba · Mi Cuaderno Tx',
      body: 'Así sonará la alarma de tus medicamentos.',
      data: {},
      categoryIdentifier: CATEGORY,
      sound: 'default',
      priority: Notifications.AndroidNotificationPriority.MAX,
      sticky: true,
    },
    trigger: {
      type: Notifications.SchedulableTriggerInputTypes.DATE,
      date: Date.now() + 5000,
      channelId: CH_DOSES,
      delivery: 'alarmClock',
    },
  }).catch((e) => console.warn('test', e));
}

// Si hay una versión nueva de la app publicada, la baja y reinicia sola.
async function checkUpdate() {
  if (__DEV__ || !Updates.isEnabled) return;
  try {
    const r = await Updates.checkForUpdateAsync();
    if (!r.isAvailable) return;
    await Updates.fetchUpdateAsync();
    await Updates.reloadAsync();
  } catch (e) {
    console.warn('update', e);
  }
}

export default function App() {
  const web = useRef(null);
  const loaded = useRef(false);
  const pending = useRef([]);
  const handled = useRef(new Set());
  const scheme = useColorScheme();
  const [theme, setTheme] = useState(null);
  const dark = theme ? theme.dark : scheme === 'dark';
  const bg = (theme && theme.bg) || (dark ? '#0A1019' : '#F1F4F8');

  useEffect(() => {
    checkUpdate();
  }, []);

  const deliver = useCallback((action) => {
    const js = `window.mctNativeAction && window.mctNativeAction(${JSON.stringify(action)}); true;`;
    if (loaded.current && web.current) web.current.injectJavaScript(js);
    else pending.current.push(js);
  }, []);

  const onResponse = useCallback(
    (r) => {
      if (!r) return;
      const req = r.notification.request;
      const key = `${req.identifier}|${r.actionIdentifier}|${r.notification.date}`;
      if (handled.current.has(key)) return;
      handled.current.add(key);
      const data = req.content.data || {};
      Notifications.dismissNotificationAsync(req.identifier).catch(() => {});
      if (!data.k || !data.t) return;
      const action = r.actionIdentifier === 'taken' ? 'taken' : r.actionIdentifier === 'snooze' ? 'snooze' : 'open';
      deliver({ action, k: data.k, t: data.t });
    },
    [deliver]
  );

  useEffect(() => {
    setupNotifications().catch((e) => console.warn('setup', e));
    const sub = Notifications.addNotificationResponseReceivedListener(onResponse);
    Notifications.getLastNotificationResponseAsync()
      .then((r) => {
        onResponse(r);
        Notifications.clearLastNotificationResponse?.();
      })
      .catch(() => {});
    return () => sub.remove();
  }, [onResponse]);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (!web.current) return false;
      // Si hay una hoja abierta en la página, la cierra; si no, deja que Android salga.
      web.current.injectJavaScript(
        `(function(){var s=document.getElementById('sheet');if(s&&s.open){s.close();}else{window.ReactNativeWebView.postMessage(JSON.stringify({type:'exit'}));}})(); true;`
      );
      return true;
    });
    return () => sub.remove();
  }, []);

  const onMessage = useCallback((e) => {
    let msg;
    try {
      msg = JSON.parse(e.nativeEvent.data);
    } catch {
      return;
    }
    if (msg.type === 'schedule' && Array.isArray(msg.items)) applySchedule(msg.items);
    else if (msg.type === 'test') testAlarm();
    else if (msg.type === 'settings') Linking.openSettings();
    else if (msg.type === 'exit') BackHandler.exitApp();
    else if (msg.type === 'theme') setTheme({ bg: typeof msg.bg === 'string' ? msg.bg : null, dark: !!msg.dark });
  }, []);

  const onLoadEnd = useCallback(() => {
    loaded.current = true;
    const list = pending.current;
    pending.current = [];
    list.forEach((js) => web.current && web.current.injectJavaScript(js));
  }, []);

  // Los enlaces externos (correo, WhatsApp, otras páginas) se abren fuera de la app.
  const onShouldStart = useCallback((req) => {
    const url = req.url || '';
    if (url.startsWith('about:') || url.startsWith('data:') || url.startsWith(SITE)) return true;
    Linking.openURL(url).catch(() => {});
    return false;
  }, []);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={[styles.root, { backgroundColor: bg }]} edges={['top', 'bottom', 'left', 'right']}>
        <StatusBar style={dark ? 'light' : 'dark'} />
        <WebView
          ref={web}
          source={{ html: webapp, baseUrl: SITE }}
          originWhitelist={['*']}
          javaScriptEnabled
          domStorageEnabled
          setSupportMultipleWindows={false}
          onMessage={onMessage}
          onLoadEnd={onLoadEnd}
          onShouldStartLoadWithRequest={onShouldStart}
          style={{ backgroundColor: bg }}
          overScrollMode="never"
          textZoom={100}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({ root: { flex: 1 } });
