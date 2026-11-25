import { requireOptionalNativeModule } from 'expo';

export type MctAlarmNative = {
  schedule(json: string): void;
  scheduleOne(json: string): void;
  cancelAll(): void;
  stop(): void;
  takePendingAction(): string | null;
  canFullScreen(): boolean;
  openFullScreenSettings(): void;
  // Desde la versión 1.4 del APK (pueden faltar en versiones anteriores)
  diagnostics?(): string;
  getLog?(): string;
  openSettings?(what: string): void;
};

// null fuera de Android (o en Expo Go): la app cae a las notificaciones normales.
export default requireOptionalNativeModule<MctAlarmNative>('MctAlarm');
