package pl.aq.belbat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements BluetoothFGServiceCallbacks, DeviceListAdapter.ItemClickListener {

    private BluetoothFGService bluetoothFGService;
    private DeviceListAdapter listAdapter;
    private boolean bound = false;
    private boolean cableConnectionState = false;

    public static final String MAC_PREF_KEY = "pl.blebat.mac";
    public static final String AUTO_PREF_KEY = "pl.blebat.autoconnect";
    public static final String TRAVELMODE_PREF_KEY = "pl.blebat.travel";

    public static final String MAC_ADDR_PARAM = "addr";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 123;
    private static final int REQUEST_ENABLE_BT = 1;

    private TextView batteryLevelTxt = null;
    private TextView batteryStatusTxt = null;
    private TextView connStatus = null;

    private Button connectBtn = null;
    private Button startScanBtn = null;
    private Button stopScanBtn = null;

    private EditText maxCharge = null;
    private EditText macAddress = null;

    private SwitchCompat travelMode = null;
    private SwitchCompat autoConnMode = null;

    private final Set<String> itemsOnList = new HashSet<>();
    private final List<Device> uiDevicesList = new ArrayList<>();
    private BluetoothLeScanner btScanner;

    private final Handler mHandler = new Handler();
    private final Handler mHandlerAutoCon = new Handler();
    private static final long SCAN_PERIOD = 10000;
    private static final long AUTO_CON_PERIOD = 2000;
    private boolean btScanning = false;
    private boolean serviceStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(new PowerConnectionReceiver(this), ifilter);

        batteryLevelTxt = findViewById(R.id.batteryLevelTxt);
        batteryStatusTxt = findViewById(R.id.chargingStatusTxt);
        connStatus = findViewById(R.id.connStatus);

        connectBtn = findViewById(R.id.connectBtn);
        startScanBtn = findViewById(R.id.startScanBtn);
        startScanBtn = findViewById(R.id.startScanBtn);

        maxCharge = findViewById(R.id.maxCharge);
        macAddress = findViewById(R.id.macAddress);

        travelMode = findViewById(R.id.travelMode);
        autoConnMode = findViewById(R.id.autoConnectSwitch);

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = btManager.getAdapter();
        btScanner = btAdapter != null ? btAdapter.getBluetoothLeScanner() : null;

        if (btAdapter != null && !btAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }

        // Make sure we have access coarse location enabled, if not, prompt the user to enable it
        if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("This app needs location access");
            builder.setMessage("Please grant location access so this app can detect peripherals.");
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
                            PERMISSION_REQUEST_COARSE_LOCATION);
                }
            });
            builder.show();
        }

        // set up the RecyclerView
        RecyclerView recyclerView = findViewById(R.id.itemsList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        listAdapter = new DeviceListAdapter(this, uiDevicesList);
        listAdapter.setClickListener(this);
        recyclerView.setAdapter(listAdapter);

        //fetch previous address from preferences
        String tmpAddress = getSavedString(MAC_PREF_KEY);
        if(tmpAddress != null) {
            macAddress.setText(tmpAddress);
        }

        String autoConnectStr = getSavedString(AUTO_PREF_KEY);
        if(autoConnectStr != null) {
            System.out.println("auto con " + autoConnectStr);
            autoConnMode.setChecked(autoConnectStr.equals(String.valueOf(true)));
        }

        String travelModeStr = getSavedString(TRAVELMODE_PREF_KEY);
        if(travelModeStr != null) {
            travelMode.setChecked(travelModeStr.equals(String.valueOf(true)));
        }
    }

    @Override
    public void onItemClick(View view, int position) {
        //Toast.makeText(this, "You clicked " + listAdapter.getItem(position) + " on row number " + position, Toast.LENGTH_SHORT).show();
        String mac = listAdapter.getItem(position).getMac();
        if(mac != null && !"".equals(mac)) {
            macAddress.setText(mac);
        } else {
            macAddress.setText("-");
        }
    }

    public void onConnectClick(View view) {
        String mac = macAddress.getText().toString();
        if(!serviceStarted) {
            if (!"".equals(mac)) {
                startService(mac);
                serviceStarted = true;
                connectBtn.setText(R.string.disconnect);

                //saved mac for later use
                saveInPreferences(MAC_PREF_KEY, mac);
            }
        } else {
            stopService();
            serviceStarted = false;
            connectBtn.setText(R.string.connect);
        }
    }

    public void onStartScanClick(View view) {
        if(!btScanning) {
            connStatus.setText(R.string.scanning_started);
            startScanning();
        }
    }

    public void onStopScanClick(View view) {
        getTimeToNextAlarm();
        if(btScanning) {
            stopScanning();
        }
    }

    public void startService(String bluetoothMac) {
        connStatus.setText(R.string.connecting);

        Intent serviceIntent = new Intent(this, BluetoothFGService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        serviceIntent.putExtra(MAC_ADDR_PARAM, bluetoothMac);

        Context context = getBaseContext();
        context.startForegroundService(serviceIntent);
    }


    public void stopService() {
        Intent serviceIntent = new Intent(this, BluetoothFGService.class);
        stopService(serviceIntent);

        if (bound) {
            bluetoothFGService.setCallbacks(null); // unregister
            unbindService(serviceConnection);
            bound = false;
        }

        connStatus.setText(R.string.service_disconnected);
    }

    /** Callbacks for service binding, passed to bindService() */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // cast the IBinder and get MyService instance
            BluetoothFGService.LocalBinder binder = (BluetoothFGService.LocalBinder) service;
            bluetoothFGService = binder.getService();
            bound = true;
            bluetoothFGService.setCallbacks(MainActivity.this); // register
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    // Device scan callback.
    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            System.out.println("Callbacktype: "+ callbackType);
            addResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            connStatus.setText(getString(R.string.scan_failed_code, errorCode));
        }

        private void addResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if(!itemsOnList.contains(device.getAddress())) {
                itemsOnList.add(device.getAddress());
                System.out.println("Found: " + device.getAddress());

                listAdapter.addElement(new Device(device.getAddress(), device.getName()));
            }
        }
    };

    public void startScanning() {
        System.out.println("start scanning");
        btScanning = true;
        itemsOnList.clear();
        listAdapter.clearData();
        connStatus.setText(R.string.scanning_started);
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if(btScanner != null) {
                    btScanner.startScan(leScanCallback);
                }
            }
        });

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopScanning();
            }
        }, SCAN_PERIOD);
    }

    public void stopScanning() {
        System.out.println("stopping scanning");
        connStatus.setText(R.string.scanning_stopped);
        btScanning = false;

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if(btScanner != null) {
                    btScanner.stopScan(leScanCallback);
                }
            }
        });
    }

    public long getTimeToNextAlarm() {
        AlarmManager alarmManager = (AlarmManager)getBaseContext().getSystemService(Context.ALARM_SERVICE);
        return getTimeToNextAlarm(alarmManager);
    }

    public static long getTimeToNextAlarm(AlarmManager alarmManager) {
        if(alarmManager.getNextAlarmClock() != null) {
            long nextAlarmTime = alarmManager.getNextAlarmClock().getTriggerTime();
            Date nextAlarmDate = new Date(nextAlarmTime);
            System.out.println(nextAlarmDate);

            return TimeUnit.MINUTES.convert(nextAlarmDate.getTime() - new Date().getTime(), TimeUnit.MILLISECONDS);
        }
        return -1;
    }

    public String formatTimeToAlarm(long timeToAlarm) {
        if(timeToAlarm > 0) {
            long hours = timeToAlarm / 60;
            long minutes = timeToAlarm % 60;
            return getString(R.string.time_to_next_alarm, hours, minutes);
        } else {
            return getString(R.string.no_alarm_set);
        }
    }

    public void updateBatteryTxt(int level,float temp, int status) {
        batteryLevelTxt.setText(getString(R.string.bat_level, level, temp));
        batteryStatusTxt.setText(getString(R.string.bat_status, getBatteryStatusString(status)));

        checkAutostart(status);
    }

    private void checkAutostart(int status) {
        mHandlerAutoCon.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(!serviceStarted &&
                        (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) &&
                        autoConnMode.isChecked()) {
                    onConnectClick(null);
                }
            }
        }, AUTO_CON_PERIOD);
    }

    private String getBatteryStatusString(int batteryStatus) {
        switch(batteryStatus) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return getString(R.string.charging);
            case BatteryManager.BATTERY_STATUS_FULL:
                return getString(R.string.full);
            default:
                return getString(R.string.discharge);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_COARSE_LOCATION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                System.out.println("Coarse location permission granted");
            } else {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.limit_title);
                builder.setMessage(R.string.limit_text);
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                    }
                });
                builder.show();
            }
        }
    }

    @Override
    public void updateStatus(String status) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connStatus.setText(status);
            }
        });
    }

    @Override
    public void updateConnectionStatus(boolean connectionState) {

    }

    @Override
    public int getSelectedChargeLevel() {
        try {
            return Integer.parseInt(maxCharge.getText().toString());
        } catch (NumberFormatException e) {
            //ignore
        }
        return 0;
    }

    public void onTravelModeClick(View view) {
        saveInPreferences(TRAVELMODE_PREF_KEY, "" + travelMode.isChecked());
    }

    public void onAutoConnectClick(View view) {
        saveInPreferences(AUTO_PREF_KEY, "" + autoConnMode.isChecked());
    }

    @Override
    public boolean isTravelModeEnabled() {
        return travelMode.isChecked();
    }

    private void saveInPreferences(String key, String mac) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(key, mac);
        editor.apply();
    }

    private String getSavedString(String key) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return preferences.getString(key, null);
    }
}