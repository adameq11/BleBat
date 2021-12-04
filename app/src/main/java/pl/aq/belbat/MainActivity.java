package pl.aq.belbat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements BluetoothFGServiceCallbacks, DeviceListAdapter.ItemClickListener {

    private BluetoothFGService bluetoothFGService;
    private DeviceListAdapter listAdapter;
    private boolean bound = false;

    public static final String MAC_PREF_KEY = "pl.blebat.mac";

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

    private final Set<String> itemsOnList = new HashSet<>();
    private final List<Device> uiDevicesList = new ArrayList<>();
    private BluetoothLeScanner btScanner;

    private final Handler mHandler = new Handler();
    private static final long SCAN_PERIOD = 10000;
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

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = btManager.getAdapter();
        btScanner = btAdapter.getBluetoothLeScanner();

        if (!btAdapter.isEnabled()) {
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
        String tmpAddress = getSavedMac();
        if(tmpAddress != null) {
            macAddress.setText(tmpAddress);
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
                saveMacInPreferences(mac);
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
        if(btScanning) {
            stopScanning();
        }
    }

    public void startService(String bluetoothMac) {
        connStatus.setText(R.string.connecting);

        Intent serviceIntent = new Intent(this, BluetoothFGService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        serviceIntent.putExtra("inputExtra", "Foreground Service Example in Android");
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
                btScanner.startScan(leScanCallback);
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
                btScanner.stopScan(leScanCallback);
            }
        });
    }

    public void updateBatteryTxt(int level, String status) {
        batteryLevelTxt.setText(getString(R.string.bat_level, level));
        batteryStatusTxt.setText(getString(R.string.bat_status, status));
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

    private void saveMacInPreferences(String mac) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(MAC_PREF_KEY, mac);
        editor.apply();
    }

    private String getSavedMac() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return preferences.getString(MAC_PREF_KEY, null);
    }
}