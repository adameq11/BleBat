package pl.aq.belbat;

public interface BluetoothFGServiceCallbacks {
    void updateStatus(String status);
    void updateConnectionStatus(boolean connectionState);

    int getSelectedChargeLevel();
    boolean isTravelModeEnabled();
}
