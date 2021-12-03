package pl.aq.belbat;

public interface ServiceCallbacks {
    void updateStatus(String status);
    void updateConnectionStatus(boolean connectionState);

    int getSelectedChargeLevel();
}
