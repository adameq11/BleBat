package pl.aq.belbat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.appcompat.app.AppCompatActivity;

public class PowerConnectionReceiver extends BroadcastReceiver {

    MainActivity mainActivity = null;

    public PowerConnectionReceiver(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }


    @Override
    public void onReceive(Context context, Intent intent) {
        if(mainActivity != null) {
            mainActivity.updateBatteryTxt(getBatteryLevel(intent), getBatteryStatus(intent));
        } /*else if(service != null) {
            service.setCurrentBatteryLevel(getBatteryLevel(intent));
        }*/
    }

    public static int getBatteryLevel(Intent batteryStatus) {
        int batteryLevel = -1;
        int batteryScale = 1;
        if (batteryStatus != null) {
            batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryLevel);
            batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, batteryScale);
        }
        return new Float(batteryLevel / (float) batteryScale * 100).intValue();
    }

    private String getBatteryStatus(Intent batteryStatus) {
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        switch(status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "Charging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "Full";
            default:
                return "Discharging";
        }
    }
}