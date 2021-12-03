package pl.aq.belbat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements ServiceCallbacks, DeviceListAdapter.ItemClickListener {

    private ForegroundService foregroundService;
    private DeviceListAdapter listAdapter;
    private boolean bound = false;

    public static final String LEVEL_LBL = "Battery level: ";
    public static final String STATUS_LBL = "Battery status: ";

    public static final String MAC_PREF_KEY = "pl.blebat.mac";

    public static final String MAC_ADDR_PARAM = "addr";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 1;

    private TextView batteryLevelTxt = null;
    private TextView batteryStatusTxt = null;
    private TextView connStatus = null;

    private Button connectBtn = null;
    private Button startScanBtn = null;
    private Button stopScanBtn = null;

    private EditText maxCharge = null;
    private EditText macAddress = null;
    private RecyclerView devList = null;

    private Set<String> itemsOnList = new HashSet<>();
    private List<Device> uiDevicesList = new ArrayList<>();
    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner btScanner;

    private Handler mHandler = new Handler();
    private static final long SCAN_PERIOD = 10000;
    private boolean btScanning = false;
    private boolean serviceStarted = false;

    private String tmpAddress = null;

    @Override
    protected void onStart() {
        super.onStart();

        //Intent intent = new Intent(this, ForegroundService.class);
       // bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

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

    /** Callbacks for service binding, passed to bindService() */
    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // cast the IBinder and get MyService instance
            ForegroundService.LocalBinder binder = (ForegroundService.LocalBinder) service;
            foregroundService = binder.getService();
            bound = true;
            foregroundService.setCallbacks(MainActivity.this); // register
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

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
        devList = findViewById(R.id.itemsList);

        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();
        btScanner = btAdapter.getBluetoothLeScanner();

        if (btAdapter != null && !btAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }

        // Make sure we have access coarse location enabled, if not, prompt the user to enable it
        if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("This app needs location access");
            builder.setMessage("Please grant location access so this app can detect peripherals.");
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
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
        tmpAddress = getSavedMac();
        if(tmpAddress != null) {
            macAddress.setText(tmpAddress);
        }
    }

    public void onConnectClick(View view) {
        String mac = macAddress.getText().toString();
        if(!serviceStarted) {
            if (mac != null && !"".equals(mac)) {
                startService(mac);
                serviceStarted = true;
                connectBtn.setText("Disconnect");

                //saved mac for later use
                saveMacInPreferences(mac);
            }
        } else {
            stopService();
            serviceStarted = false;
            connectBtn.setText("Connect");
        }
    }

    public void onStartScanClick(View view) {
        if(!btScanning) {
            connStatus.setText("Scanning started...");
            startScanning();
        }
    }

    public void onStopScanClick(View view) {
        if(btScanning) {
            stopScanning();
        }
    }

    public void startService(String bluetoothMac) {
        connStatus.setText("Connecting...");

        Intent serviceIntent = new Intent(this, ForegroundService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        serviceIntent.putExtra("inputExtra", "Foreground Service Example in Android");
        serviceIntent.putExtra(MAC_ADDR_PARAM, bluetoothMac);

        Context context = getBaseContext();
        context.startForegroundService(serviceIntent);
    }


    public void stopService() {
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        stopService(serviceIntent);

        if (bound) {
            foregroundService.setCallbacks(null); // unregister
            unbindService(serviceConnection);
            bound = false;
        }

        connStatus.setText("Service disconnected");
    }


    // Device scan callback.
    private ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            System.out.println("prefound " + device.getAddress());
            if(!itemsOnList.contains(device.getAddress())) {
                itemsOnList.add(device.getAddress());
                System.out.println("Found: " + device.getAddress());

                listAdapter.addElement(new Device(device.getAddress(), device.getName()));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            connStatus.setText("Scanning failed.");
        }
    };

    public void startScanning() {
        System.out.println("start scanning");
        btScanning = true;
        itemsOnList.clear();
        listAdapter.clearData();
        connStatus.setText("Started scanning...");
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
                connStatus.setText("Scanning stopped.");
            }
        }, SCAN_PERIOD);
    }

    public void stopScanning() {
        System.out.println("stopping scanning");
        connStatus.setText("Stopped scanning.");
        btScanning = false;

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                btScanner.stopScan(leScanCallback);
            }
        });
    }

    public void updateBatteryTxt(int level, String status) {
        batteryLevelTxt.setText(LEVEL_LBL + level);
        batteryStatusTxt.setText(STATUS_LBL + status);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    System.out.println("Coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
                return;
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