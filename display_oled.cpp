#ifdef USE_OLED_DISPLAY

// SSD1306 OLED backend for the dongleDisplay* hooks (see display_dongle.h).
// Two geometries:
//   - default: 0.96" 128x64 (classic ESP32 dev board, SDA=21 SCL=22)
//   - OLED_72X40: 0.42" panel (01Space ESP32-C3 board, SDA=5 SCL=6), which the
//     SSD1306 maps into the middle of its 132x64 buffer, hence the offsets.
// Idle screen runs the fly animation ported from flock-you-C3-OLED; alerts
// mirror the T-Dongle layout (method / mac / RSSI / channel).

#include "display_dongle.h"
#include <Arduino.h>
#include <U8g2lib.h>
#include <Wire.h>
#include "display_oled_bitmaps.h"

#ifndef OLED_SDA_PIN
#define OLED_SDA_PIN 21
#endif
#ifndef OLED_SCL_PIN
#define OLED_SCL_PIN 22
#endif

#ifdef OLED_72X40
static const int xOff = 30;  // = (132-70)/2
static const int yOff = 24;  // = (64-40)/2 + buffer quirk
#define FONT_BIG   u8g2_font_tiny5_tf
#define FONT_SMALL u8g2_font_u8glib_4_tf
#define LINE_H 6
#else
static const int xOff = 0;
static const int yOff = 0;
#define FONT_BIG   u8g2_font_7x13B_tf
#define FONT_SMALL u8g2_font_5x8_tf
#define LINE_H 10
#endif

static U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE,
                                                OLED_SCL_PIN, OLED_SDA_PIN);

#define OLED_ANIM_FRAME_MS 180
#define OLED_SPLASH_MS 2500

static unsigned long alertUntilMs = 0;
static unsigned long splashUntilMs = 0;
static unsigned long lastAnimAt = 0;
static bool inAlert = false;
static int animframe = 0;

static const unsigned char* flyFrame(int f) {
  switch (f & 3) {
    case 0: return fly00_bitmap;
    case 1: return fly01_bitmap;
    case 2: return fly02_bitmap;
    default: return fly03_bitmap;
  }
}

static void drawIdle(uint8_t ch, int detCount) {
  u8g2.clearBuffer();
#ifdef OLED_72X40
  u8g2.drawXBMP(xOff, yOff, 32, 24, flyFrame(animframe));
  u8g2.setFont(FONT_SMALL);
  u8g2.setCursor(xOff + 36, yOff + 10);
  u8g2.print("Listening");
  u8g2.setCursor(xOff + 36, yOff + 18);
  u8g2.printf("Ch%u", (unsigned)ch);
  u8g2.setCursor(xOff, yOff + 32);
  u8g2.printf("Hits: %d", detCount);
#else
  u8g2.drawXBMP(0, 12, 32, 24, flyFrame(animframe));
  u8g2.setFont(FONT_SMALL);
  u8g2.setCursor(40, 26);
  u8g2.print("Listening...");
  u8g2.setCursor(0, 50);
  u8g2.printf("Ch: %u", (unsigned)ch);
  u8g2.setCursor(0, 62);
  u8g2.printf("Hits: %d", detCount);
#endif
  u8g2.sendBuffer();
}

void dongleDisplayInit() {
  u8g2.begin();
  u8g2.setContrast(255);
  u8g2.setBusClock(400000);
  u8g2.clearBuffer();
#ifdef OLED_72X40
  u8g2.drawXBMP(xOff, yOff, 70, 40, bird_bitmap);
#else
  u8g2.drawXBMP(29, 12, 70, 40, bird_bitmap);
#endif
  u8g2.sendBuffer();
  splashUntilMs = millis() + OLED_SPLASH_MS;
}

void dongleDisplayShowIdle(uint8_t ch, int detCount) {
  inAlert = false;
  alertUntilMs = 0;
  if (splashUntilMs && (long)(millis() - splashUntilMs) < 0) return;
  drawIdle(ch, detCount);
}

void dongleDisplayShowAlert(const char* method, const char* mac, int8_t rssi,
                            uint8_t ch, unsigned long alertMs) {
  inAlert = true;
  splashUntilMs = 0;
  if (alertMs == 0) alertMs = 1;
  alertUntilMs = millis() + alertMs;

  u8g2.clearBuffer();
  u8g2.setFont(FONT_BIG);
  u8g2.setCursor(xOff, yOff + LINE_H);
  u8g2.print("DETECT");
  u8g2.setFont(FONT_SMALL);
  u8g2.setCursor(xOff, yOff + 2 * LINE_H + 2);
  u8g2.print(method ? method : "?");
  u8g2.setCursor(xOff, yOff + 3 * LINE_H + 2);
  u8g2.print(mac ? mac : "");
  u8g2.setCursor(xOff, yOff + 4 * LINE_H + 2);
  u8g2.printf("RSSI %d  CH %u", (int)rssi, (unsigned)ch);
  u8g2.sendBuffer();
}

bool dongleDisplayInAlert(unsigned long now) {
  return inAlert && alertUntilMs != 0 && (long)(now - alertUntilMs) < 0;
}

void dongleDisplayTick(unsigned long now, uint8_t ch, int detCount) {
  if (inAlert) {
    if (alertUntilMs != 0 && (long)(now - alertUntilMs) >= 0) {
      inAlert = false;
      alertUntilMs = 0;
      drawIdle(ch, detCount);
      lastAnimAt = now;
    }
    return;
  }
  if (splashUntilMs) {
    if ((long)(now - splashUntilMs) < 0) return;
    splashUntilMs = 0;
  }
  if (now - lastAnimAt >= OLED_ANIM_FRAME_MS) {
    animframe = (animframe + 1) & 3;
    drawIdle(ch, detCount);
    lastAnimAt = now;
  }
}

#endif  // USE_OLED_DISPLAY
