/*

ESP32 arduino code that communicates with BatteryGuard android app (BleBat project)

*/
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

BLECharacteristic *pCharacteristic;
bool deviceConnected = false;
const int PIN = 25; 
int count = 0;
boolean sendPing = false;

#define SERVICE_UUID           "98454726-61CE-11EC-90D6-0242AC120003"
#define CHARACTERISTIC_UUID_RX "98454AB4-61CE-11EC-90D6-0242AC120003"
#define CHARACTERISTIC_UUID_TX "98454C62-61CE-11EC-90D6-0242AC120003"

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      
      // re-start advertising
      digitalWrite(PIN, HIGH);
      pServer->getAdvertising()->start();
      Serial.println("Waiting a client connection to notify (reconnecting)...");
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      std::string rxValue = pCharacteristic->getValue();

      if (rxValue.length() > 0) {
        Serial.println("*********");
        Serial.print("Received Value: ");

        for (int i = 0; i < rxValue.length(); i++) {
          Serial.print(rxValue[i]);
        }

        Serial.println();

        // Do stuff based on the command received from the app
        if (rxValue.find("D") != -1) { 
          Serial.print("disable charging");
          digitalWrite(PIN, LOW);
        }
        else {
          Serial.print("enable charging");
          digitalWrite(PIN, HIGH);
        }
        
        Serial.println("*********");
      }
    }
};

void setup() {
  Serial.begin(115200);

  pinMode(PIN, OUTPUT);

  //enable charging by default
  digitalWrite(PIN, HIGH); 

  // Create the BLE Device
  BLEDevice::init("BatteryGuard module"); // Give it a name

  // Create the BLE Server
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create the BLE Service
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create a BLE Characteristic
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID_TX,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );

  pCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pCharacteristic = pService->createCharacteristic(
                                         CHARACTERISTIC_UUID_RX,
                                         BLECharacteristic::PROPERTY_WRITE
                                       );

  pCharacteristic->setCallbacks(new MyCallbacks());

  // Start the service
  pService->start();

  // Start advertising
  pServer->getAdvertising()->start();
  Serial.println("Waiting a client connection to notify...");
}

void loop() {

  if (deviceConnected && sendPing) {
    count = count + 1;
    if(count % 60 == 0) {
      pCharacteristic->setValue("ping");
      pCharacteristic->notify();
    }
  }
  delay(1000);
}