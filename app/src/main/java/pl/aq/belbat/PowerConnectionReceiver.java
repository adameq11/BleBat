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
            mainActivity.updateBatteryTxt(getBatteryLevel(intent), getBatteryTemp(intent), intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN));
        }
    }

    public static int getBatteryLevel(Intent batteryStatus) {
        int batteryLevel = -1;
        int batteryScale = 1;
        if (batteryStatus != null) {
            batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryLevel);
            batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, batteryScale);
        }
        return Float.valueOf(batteryLevel / (float) batteryScale * 100).intValue();
    }

    public static float getBatteryTemp(Intent batteryStatus) {
        int batteryTemp = -1;
        if (batteryStatus != null) {
            batteryTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, batteryTemp);
        }
        return Float.valueOf(batteryTemp) / 10f;
    }
}