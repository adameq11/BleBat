package pl.aq.belbat;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import java.util.UUID;

public class BluetoothFGService extends Service {

    public static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

    public static final int MINUTES_BEFORE_ALARM = 60;

    public static final String CHANNEL_ID = "ForegroundServiceChannel";
    private boolean deviceConnected = false;

    private BluetoothAdapter btAdapter;
    private BluetoothGatt bluetoothGatt;

    private int requiredBatteryLevel = 83;

    private boolean chargingStatus = true;
    private static final String CHARGE_MESSAGE = "C";
    private static final String DISCHARGE_MESSAGE = "D";

    private final IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

    private final IBinder binder = new LocalBinder();
    private BluetoothFGServiceCallbacks bluetoothFGServiceCallbacks;

    private final boolean debug = false;

    // Class used for the client Binder.
    public class LocalBinder extends Binder {
        BluetoothFGService getService() {
            return BluetoothFGService.this;
        }
    }

    public void setCallbacks(BluetoothFGServiceCallbacks callbacks) {
        bluetoothFGServiceCallbacks = callbacks;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        bluetoothGatt.disconnect();
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String input = intent.getStringExtra("inputExtra");
        String mac = intent.getStringExtra(MainActivity.MAC_ADDR_PARAM);
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, 0);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText(input)
                //.setSmallIcon(R.drawable.)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);
        //do heavy work on a background thread

        System.out.println("mac address received: " + mac);
        BluetoothDevice device = btAdapter.getRemoteDevice(mac);
        bluetoothGatt = device.connectGatt(this, false, btleGattCallback);

        timerHandler.postDelayed(timerRunnable, 10000);

        //stopSelf();
        return START_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        bluetoothGatt.disconnect();
        timerHandler.removeCallbacksAndMessages(null);
        stopSelf();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        bluetoothGatt.disconnect();
        timerHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    //bluetooth stuff

    int count = 0;
    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            activity();

            timerHandler.postDelayed(this, debug ? 3000 : 60000);
        }
    };

    private void activity() {
        if(bluetoothFGServiceCallbacks != null && bluetoothFGServiceCallbacks.isTravelModeEnabled()) {
            long minutesToAlarm = MainActivity.getTimeToNextAlarm((AlarmManager) getBaseContext().getSystemService(Context.ALARM_SERVICE));
            System.out.println("minutes to alarm: " + minutesToAlarm);
            if (minutesToAlarm >= 0 && minutesToAlarm < MINUTES_BEFORE_ALARM) {
                if (!chargingStatus) {
                    if (enableCharging(true)) {
                        chargingStatus = true;
                        System.out.println("start charging because close to alarm.");
                    }
                }
                return;  //check for any other rules is not needed, as we have to have charge on.
            }
        }

        int reqBat = bluetoothFGServiceCallbacks != null ? bluetoothFGServiceCallbacks.getSelectedChargeLevel() : requiredBatteryLevel;
        int batLevel = getCurrentBatteryLevel();
        System.out.println("current bat level " + batLevel + " charging status " + chargingStatus);
        if (!chargingStatus && batLevel <= (reqBat - 2)) {
            if (enableCharging(true)) {
                chargingStatus = true;
                System.out.println("++++ would send " + CHARGE_MESSAGE);
            }
        } else if (chargingStatus && batLevel > reqBat) {
            if (enableCharging(false)) {
                chargingStatus = false;
                System.out.println("++++ would send " + DISCHARGE_MESSAGE);
            }
        }



        if(debug) {
            if (count % 2 == 0) {
                transferStringToArduinoService("5");
            } else {
                transferStringToArduinoService("6");
            }
            count++;
        }
    }

    public boolean enableCharging(boolean enable) {
        if(enable) {
            return transferStringToArduinoService(CHARGE_MESSAGE);
        } else {
            return transferStringToArduinoService(DISCHARGE_MESSAGE);
        }
    }

    public boolean transferStringToArduinoService(String value) {
        System.out.println("trying to send " + value + " --- " + deviceConnected);
        if(deviceConnected) {
            BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(SERVICE_UUID));
            System.out.println("Service: " + service);
            if(service != null) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(RX_UUID));
                System.out.println("characteristic: " + characteristic);
                if(characteristic != null) {
                    System.out.println("-- physica send " + value);
                    characteristic.setValue(value);
                    bluetoothGatt.writeCharacteristic(characteristic);
                    return true;
                }
            } else {
                System.out.println("---- service jest nullem");
            }
        }
        return false;
    }


    // Device connect call back
    private final BluetoothGattCallback btleGattCallback = new BluetoothGattCallback() {

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            // this will get called anytime you perform a read or write characteristic operation
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            // this will get called when a device connects or disconnects
            System.out.println("=== newState " + newState);
            switch (newState) {
                case 2:
                    deviceConnected = true;
                    bluetoothGatt.discoverServices();
                    if(bluetoothFGServiceCallbacks != null) {
                        bluetoothFGServiceCallbacks.updateStatus(getString(R.string.connected));
                        bluetoothFGServiceCallbacks.updateConnectionStatus(false);
                    }
                    break;
                default:
                    deviceConnected = false;
                    if(bluetoothFGServiceCallbacks != null) {
                        bluetoothFGServiceCallbacks.updateStatus(getString(R.string.conn_problem, newState));
                        bluetoothFGServiceCallbacks.updateConnectionStatus(false);
                    }
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            // this will get called after the client initiates a BluetoothGatt.discoverServices() call
            System.out.println("device services have been discovered");
            // displayGattServices(bluetoothGatt.getServices());
        }
    };

    public int getCurrentBatteryLevel() {
        Intent intent = registerReceiver(null, ifilter);
        return PowerConnectionReceiver.getBatteryLevel(intent);
    }
}