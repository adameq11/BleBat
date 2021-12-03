package pl.aq.belbat;

public class Device {
    private String mac;
    private String name;

    public Device(String mac, String name) {
        this.mac = mac;
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public String getName() {
        return name;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "" + name + " (" + mac + ")";
    }
}
