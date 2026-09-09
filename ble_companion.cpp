#ifdef USE_BLE_COMPANION

// NimBLE GATT server for the phone companion app. One service, one notify
// characteristic carrying JSON event lines (detections, button marks). The
// phone timestamps and geotags events as they arrive, so the device only
// needs to ship them promptly — no clock required on this side.

#include "ble_companion.h"
#include <Arduino.h>
#include <NimBLEDevice.h>

#define BLE_DEVICE_NAME "FlockYou"
#define BLE_SVC_UUID    "8f1d0001-6f9c-43fa-9f9a-4d5c1a2b3c4d"
#define BLE_EVT_UUID    "8f1d0002-6f9c-43fa-9f9a-4d5c1a2b3c4d"

static NimBLECharacteristic* evtChar = nullptr;
static volatile bool clientConnected = false;

class SrvCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*) override { clientConnected = true; }
  void onDisconnect(NimBLEServer*) override {
    clientConnected = false;
    NimBLEDevice::startAdvertising();
  }
};

void bleCompanionInit() {
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(247);

  NimBLEServer* srv = NimBLEDevice::createServer();
  srv->setCallbacks(new SrvCallbacks());

  NimBLEService* svc = srv->createService(BLE_SVC_UUID);
  evtChar = svc->createCharacteristic(
      BLE_EVT_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SVC_UUID);
  adv->setScanResponse(true);
  adv->start();
}

void bleCompanionNotify(const char* json, int len) {
  if (!evtChar || !clientConnected || len <= 0) return;
  evtChar->setValue((const uint8_t*)json, (size_t)len);
  evtChar->notify();
}

bool bleCompanionConnected() { return clientConnected; }

#endif  // USE_BLE_COMPANION
