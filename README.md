# BleBat aka BatteryGuard 
Android app project that was created to communicate to a self-made smart charging cable. Goal is to be able to have full control over battery charging process, charge to 80% instead of 100% and save battery life.

## Hardware
ESP32 + simple relay. Powered from charger.
- Arduino code available in `esp32_code/blebat_simple`
- requires esp32 module and relay module which is connected to GPIO25 on esp32.

## Communication:
Bluetooth LE

## Software (this project):
- Bluetooth devices listing
- knows battery state
- Foreground service to talk to device so it's not killed
- Stops charging when level is reached and keep it in the 3% range.
- option to charge to 100% just before the next alarm

