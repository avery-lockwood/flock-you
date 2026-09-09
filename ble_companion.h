#pragma once

#include <stdint.h>

// BLE companion link: a GATT service that streams detection/mark events as
// JSON lines to a phone app (Web Bluetooth). Enabled with USE_BLE_COMPANION.

#ifdef USE_BLE_COMPANION

void bleCompanionInit();
// One JSON event line, <= ~180 bytes; notified to a subscribed phone.
void bleCompanionNotify(const char* json, int len);
bool bleCompanionConnected();

#else

static inline void bleCompanionInit() {}
static inline void bleCompanionNotify(const char*, int) {}
static inline bool bleCompanionConnected() { return false; }

#endif
