import { requireOptionalNativeModule } from 'expo';

export type MctAlarmNative = {
  schedule(json: string): void;
  scheduleOne(json: string): void;
  cancelAll(): void;
  stop(): void;
  takePendingAction(): string | null;
  canFullScreen(): boolean;
  openFullScreenSettings(): void;
};

// null fuera de Android (o en Expo Go): la app cae a las notificaciones normales.
export default requireOptionalNativeModule<MctAlarmNative>('MctAlarm');
