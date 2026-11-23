// AuraPlant Core: Light + Smooth S-Curve Servo + Phone BLE Bridge + Accelerometer
#include <SPI.h>
#include <STBLE.h>
#include <Wire.h>
#include <Wireling.h>
#include <TSL2572.h>
#include <ServoDriver.h>
#include <TinyScreen.h>
#include <BMA250.h>

ServoDriver servo(NO_R_REMOVED);
ServoDriver servo2(NO_R_REMOVED);   // used after BLE, never pulses the reset pin

#ifndef BLE_DEBUG
#define BLE_DEBUG true
#endif

#define SerialMonitorInterface SerialUSB

// ---- Accelerometer ----
UBMA250 accel;
bool accelOk = false;
int lastAx = 0, lastAy = 0, lastAz = 0;
unsigned long lastAccelRead = 0;
unsigned long lastSpike = 0;
int spikeCount = 0;
unsigned long dizzyUntil = 0;
unsigned long accelLockUntil = 0;
int upX = 0, upY = 0, upZ = 0;     // reading when upright
int freefallCount = 0;
unsigned long tippedSince = 0;
bool fellOver = false;
const long ONE_G = 256;            // about 256 counts per g at the 2g range
const int SPIKE_DELTA = 60;        // change between readings that counts as a bump
const bool DEBUG_ACCEL = true;     // prints deltas so you can tune; set false later

// ---- BLE ----
uint8_t ble_rx_buffer[21];
uint8_t ble_rx_buffer_len = 0;
uint8_t ble_connection_state = false;
#define PIPE_UART_OVER_BTLE_UART_TX_TX 0

// ---- Light & Display ----
const unsigned long LIGHT_INTERVAL = 5000;
TSL2572 light_sensor;
TinyScreen display = TinyScreen(TinyScreenPlus);
int lastFace = -1;                 // remembers which face is showing
unsigned long lastLightRead = 0;

// ---- Mood system ----
float mood = 60.0f;                          // 0-100
float lastLux = 0.0f;                        // latest light reading
unsigned long lastMoodTick = 0;
unsigned long lastMoodSend = 2500;           // offset so it doesn't collide with the light message
unsigned long lastWatered = 0;               // millis() when last watered

const unsigned long WATER_INTERVAL = 20000;  // DEMO: 20 s (real plant: 3 days = 259200000)
const float LIGHT_GOOD = 5.0f;               // placeholder, tune to room lighting
const float LIGHT_DARK = 0.5f;               // placeholder, tune to dark room

// ---- Face / blink ----
unsigned long reactUntil = 0;     // forces the happy face until this time
unsigned long nextBlink = 4000;   // when the next blink starts
unsigned long blinkEnd = 0;
bool blinking = false;

// ---- Smooth S-Curve Servo ----
const int SERVO_CHANNEL   = 2;
const int SERVO_REST      = 1200;
const int SERVO_PERK      = 1500;
const int SERVO_HAPPY     = 1900;
const int SERVO_MIN_LIMIT = 900;
const int SERVO_MAX_LIMIT = 2100;

const unsigned long SERVO_STEP_MS = 20;   // 50 Hz update rate
const int SERVO_DEADBAND_US       = 15;   // ignores tiny mood drift so it won't twitch

int servoPos       = SERVO_REST;
int servoStartPos  = SERVO_REST;
int servoTarget    = SERVO_REST;
unsigned long servoStartTime  = 0;
unsigned long servoDurationMs = 1000;
unsigned long lastServoStep   = 0;
unsigned long lastServoMove   = 0;
unsigned long lastServoCmd    = 0;
unsigned long servoRestAt     = 0;
unsigned long wiggleUntil     = 0;

int wiggleLow  = SERVO_PERK;
int wiggleHigh = SERVO_HAPPY;
bool wiggleDir = true;
bool servoOk   = false;

// ================= SERVO =================
void servoWrite(int us) {
  if (!servoOk) return;
  servo2.setServo(SERVO_CHANNEL, us);
  lastServoCmd = millis();
}

// Move smoothly to 'us' over 'durationMs' milliseconds
void servoGoTo(int us, unsigned long durationMs = 1200) {
  us = constrain(us, SERVO_MIN_LIMIT, SERVO_MAX_LIMIT);
  // Ignore tiny changes (like slow mood decay) so the servo doesn't jitter back and forth
  if (abs(us - servoTarget) < SERVO_DEADBAND_US) return;

  servoStartPos   = servoPos;
  servoTarget     = us;
  servoStartTime  = millis();
  servoDurationMs = max(durationMs, 100UL);
}

void servoUpdate() {
  if (!servoOk) return;
  unsigned long now = millis();

  if (now - lastServoStep >= SERVO_STEP_MS) {
    lastServoStep = now;

    if (servoPos != servoTarget) {
      float t = (float)(now - servoStartTime) / (float)servoDurationMs;
      if (t >= 1.0f) {
        servoPos = servoTarget;
      } else {
        // Cubic S-curve: slow start, smooth cruise, soft landing
        float ease = t * t * (3.0f - 2.0f * t);
        servoPos = servoStartPos + (int)((servoTarget - servoStartPos) * ease);
      }
      servoWrite(servoPos);
      lastServoMove = now;
    }
  }

  // Keep-alive so the controller's failsafe (1000 ms) doesn't switch PWM off
  if (now - lastServoCmd > 400) servoWrite(servoPos);
}

// ================= MOOD =================
void addMood(float amount) {
  mood += amount;
  if (mood > 100.0f) mood = 100.0f;
  if (mood < 0.0f)   mood = 0.0f;
}

// ================= REACTIONS =================
void reactPerk() {                         // gentle 1.0s rise, holds 2.5s
  servoGoTo(SERVO_PERK, 1000);
  servoRestAt = millis() + 3500;
  addMood(5);
}

void reactHappy() {                        // graceful 1.6s full rise, holds 3.0s
  servoGoTo(SERVO_HAPPY, 1600);
  servoRestAt = millis() + 4600;
  addMood(15);
  reactUntil = millis() + 3000;
}

void reactWater() {                        // smooth, wide celebratory sway
  wiggleLow   = SERVO_MIN_LIMIT;
  wiggleHigh  = SERVO_MAX_LIMIT;
  wiggleUntil = millis() + 4500;
  servoRestAt = wiggleUntil;
  reactUntil  = wiggleUntil;
  wiggleDir   = true;
  servoGoTo(wiggleHigh, 900);              // first smooth sweep
}

void scanI2C(const char* label) {
  SerialMonitorInterface.print(label);
  SerialMonitorInterface.print(": ");
  for (uint8_t a = 1; a < 127; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) {
      SerialMonitorInterface.print("0x");
      SerialMonitorInterface.print(a, HEX);
      SerialMonitorInterface.print(" ");
    }
  }
  SerialMonitorInterface.println();
}

// Runs once per second: slow, steady changes
void updateMood() {
  if (millis() - lastMoodTick < 1000) return;
  lastMoodTick = millis();

  addMood(-0.05f);                                   // everything fades slowly

  if (lastLux >= LIGHT_GOOD)      addMood(+0.5f);
  else if (lastLux < LIGHT_DARK)  addMood(-0.3f);

  if (millis() - lastWatered > WATER_INTERVAL) addMood(-1.0f);
}

int moodToServo() {
  // mood 0 -> SERVO_REST (down), mood 100 -> SERVO_HAPPY (fully up)
  return SERVO_REST + (int)((SERVO_HAPPY - SERVO_REST) * (mood / 100.0f));
}

// ================= FACE =================
// Which face should we show right now? 0=happy 1=okay 2=sad 3=thirsty 4=dizzy
int chooseFace() {
  if (millis() < dizzyUntil) return 4;
  if (millis() < reactUntil) return 0;
  if (millis() - lastWatered > WATER_INTERVAL) return 3;
  if (mood >= 70) return 0;
  if (mood >= 40) return 1;
  return 2;
}

void drawFace(int face) {
  display.clearScreen();

  if (face == 4) {
    // Dizzy: X eyes and wavy mouth
    display.drawLine(22, 12, 36, 28, 255, 255, 255);
    display.drawLine(36, 12, 22, 28, 255, 255, 255);
    display.drawLine(60, 12, 74, 28, 255, 255, 255);
    display.drawLine(74, 12, 60, 28, 255, 255, 255);
    display.drawLine(30, 46, 40, 40, 255, 128, 0);
    display.drawLine(40, 40, 50, 46, 255, 128, 0);
    display.drawLine(50, 46, 60, 40, 255, 128, 0);
    display.drawLine(60, 40, 66, 46, 255, 128, 0);
    return;
  }

  if (face == 3) {
    // Thirsty: closed eyes (flat lines) and a small round mouth
    display.drawLine(22, 22, 36, 22, 255, 255, 255);
    display.drawLine(60, 22, 74, 22, 255, 255, 255);
    display.drawRect(44, 38, 8, 8, true, 0, 150, 255);   // blue "drop" mouth
    display.setFont(thinPixel7_10ptFontInfo);
    display.fontColor(TS_8b_Yellow, TS_8b_Black);
    display.setCursor(28, 52);
    display.print("water me");
    return;
  }

  // Eyes (same for happy, okay and sad)
  display.drawRect(24, 14, 10, 14, true, 255, 255, 255);
  display.drawRect(62, 14, 10, 14, true, 255, 255, 255);

  if (face == 0) {          // happy: U-shaped smile
    display.drawLine(28, 40, 36, 50, 0, 255, 0);
    display.drawLine(36, 50, 60, 50, 0, 255, 0);
    display.drawLine(60, 50, 68, 40, 0, 255, 0);
  } else if (face == 1) {   // okay: flat mouth
    display.drawLine(30, 46, 66, 46, 255, 255, 0);
  } else {                  // sad: upside-down U
    display.drawLine(28, 50, 36, 42, 255, 0, 0);
    display.drawLine(36, 42, 60, 42, 255, 0, 0);
    display.drawLine(60, 42, 68, 50, 255, 0, 0);
  }
}

// Redraw only the eyes. Black rectangles erase the old eyes first.
void drawEyes(bool open) {
  display.drawRect(22, 12, 14, 18, true, 0, 0, 0);
  display.drawRect(60, 12, 14, 18, true, 0, 0, 0);
  if (open) {
    display.drawRect(24, 14, 10, 14, true, 255, 255, 255);
    display.drawRect(62, 14, 10, 14, true, 255, 255, 255);
  } else {
    display.drawLine(24, 21, 34, 21, 255, 255, 255);
    display.drawLine(62, 21, 72, 21, 255, 255, 255);
  }
}

void updateBlink() {
  if (lastFace == 3 || lastFace == 4) return;   // thirsty and dizzy faces have their own eyes
  unsigned long now = millis();
  if (!blinking && now > nextBlink) {
    blinking = true;
    blinkEnd = now + 150;                       // eyes stay closed 150 ms
    drawEyes(false);
  } else if (blinking && now > blinkEnd) {
    blinking = false;
    nextBlink = now + 3000 + (now % 2000);      // next blink in 3 to 5 s
    drawEyes(true);
  }
}

// Call every loop. Only redraws when the face changes.
void updateFace() {
  int f = chooseFace();
  if (f != lastFace) {
    lastFace = f;
    blinking = false;
    drawFace(f);
  }
}

// ================= BLE =================
void bleSend(String msg) {
  if (!ble_connection_state) return;       // nobody connected, don't send
  if (msg.length() > 19) msg = msg.substring(0, 19);
  if (!lib_aci_send_data(PIPE_UART_OVER_BTLE_UART_TX_TX, (uint8_t*)msg.c_str(), msg.length())) {
    SerialMonitorInterface.println(F("TX dropped!"));
  }
}

void handleIncoming() {
  if (!ble_rx_buffer_len) return;
  ble_rx_buffer[ble_rx_buffer_len] = '\0';
  String cmd = String((char*)ble_rx_buffer);
  cmd.trim();
  ble_rx_buffer_len = 0;

  SerialMonitorInterface.print("Phone sent: ");
  SerialMonitorInterface.println(cmd);

  if (cmd == "A:1" || cmd == "R:HAPPY") reactHappy();   // kind word -> rise
  else if (cmd == "R:PERK") reactPerk();
  else if (cmd == "W:1") {
    lastWatered = millis();
    addMood(20);
    reactWater();
    SerialMonitorInterface.println("Watered!");
  }
}

// ================= ACCELEROMETER =================
void updateAccel() {
  if (!accelOk) return;
  unsigned long now = millis();
  if (now - lastAccelRead < 40) return;
  lastAccelRead = now;

  accel.read();
  int ax = accel.X, ay = accel.Y, az = accel.Z;
  int delta = abs(ax - lastAx) + abs(ay - lastAy) + abs(az - lastAz);
  lastAx = ax; lastAy = ay; lastAz = az;

  if (DEBUG_ACCEL && delta > 20) {
    SerialMonitorInterface.print("Accel delta: ");
    SerialMonitorInterface.println(delta);
  }

  // ---- Free fall: total acceleration near 0 g for 2+ readings ----
  long mag2 = (long)ax * ax + (long)ay * ay + (long)az * az;
  if (mag2 < (ONE_G * 4 / 10) * (ONE_G * 4 / 10)) {      // below 0.4 g
    freefallCount++;
  } else {
    if (freefallCount >= 2) {                            // fall just ended
      dizzyUntil = now + 4000;
      addMood(-20);
      servoGoTo(SERVO_REST, 600);
      servoRestAt = now + 3000;
      accelLockUntil = now + 2500;                       // ignore the impact bounce
      SerialMonitorInterface.println("E:FALL");
      bleSend("E:FALL\n");
    }
    freefallCount = 0;
  }

  // ---- Tipped over: gravity no longer points the way it did at startup ----
  long dot = (long)ax * upX + (long)ay * upY + (long)az * upZ;
  long upMag2 = (long)upX * upX + (long)upY * upY + (long)upZ * upZ;
  if (dot < upMag2 / 2) {                                // more than about 60 degrees off
    if (tippedSince == 0) tippedSince = now;
    if (!fellOver && now - tippedSince > 1500) {
      fellOver = true;
      SerialMonitorInterface.println("E:TIPPED");
      bleSend("E:TIPPED\n");
    }
  } else if (dot > upMag2 * 8 / 10) {
    tippedSince = 0;
    if (fellOver) {
      fellOver = false;
      SerialMonitorInterface.println("E:UPRIGHT");
      bleSend("E:UPRIGHT\n");
    }
  }
  if (fellOver) dizzyUntil = now + 500;                  // keep the dizzy face while it lies on its side

  if (now < accelLockUntil) { spikeCount = 0; return; }  // ignore the bounce and shake tail

  // Don't mistake the servo's own vibration for a touch
  bool servoShaking = (now - lastServoMove < 250) || (now < wiggleUntil);

  // Count bumps in a burst
  if (delta > SPIKE_DELTA && !servoShaking) {
    if (now - lastSpike > 600) spikeCount = 0;           // new burst
    spikeCount++;
    lastSpike = now;
  }

  // Shake: several spikes close together
  if (spikeCount >= 4) {
    spikeCount = 0;
    dizzyUntil = now + 3000;
    accelLockUntil = now + 2000;
    addMood(-10);
    servoGoTo(SERVO_REST, 600);
    servoRestAt = now + 3000;
    SerialMonitorInterface.println("E:SHAKE");
    bleSend("E:SHAKE\n");
    return;
  }

  // Pat: 1-2 bumps, then quiet for 500 ms
  if (spikeCount > 0 && now - lastSpike > 500) {
    if (spikeCount <= 2) {
      SerialMonitorInterface.println("E:PAT");
      bleSend("E:PAT\n");
      reactPerk();
    }
    spikeCount = 0;
  }
}

// ================= SETUP =================
void setup() {
  SerialMonitorInterface.begin(115200);
  while (!SerialMonitorInterface && millis() < 3000);

  Wire.begin();
  Wireling.begin();
  light_sensor.init(GAIN_16X);
  display.begin();
  display.setFlip(false);           // flipped orientation
  display.setBrightness(10);
  accel.begin(BMA250_range_2g, BMA250_update_time_16ms);
  scanI2C("before servo");

  BLEsetup();                       // BLE first
  scanI2C("after BLE");

  pinMode(9, OUTPUT);               // release the shared reset line
  digitalWrite(9, HIGH);
  delay(200);                       // give the controller time to boot
  scanI2C("after releasing pin 9");

  servoOk = (servo2.begin(20000) == 0);   // no reset pin used
  scanI2C("after servo2 begin");

  accel.read();
  accelOk = true;
  lastAx = accel.X; lastAy = accel.Y; lastAz = accel.Z;
  upX = accel.X; upY = accel.Y; upZ = accel.Z;

  if (servoOk) {
    servo2.setFailsafe(1000);
    SerialMonitorInterface.println("Servo controller OK");
    servoWrite(SERVO_REST);
  } else {
    SerialMonitorInterface.println("Servo controller NOT detected!");
  }

  drawFace(1);                      // start immediately in neutral face
}

// ================= LOOP =================
void loop() {
  servoUpdate();

  updateMood();
  updateFace();
  updateBlink();
  updateAccel();

  // Smooth wiggle: only reverses AFTER reaching each milestone cleanly
  if (millis() < wiggleUntil) {
    if (servoPos == servoTarget) {         // wait until S-curve finishes decelerating
      wiggleDir = !wiggleDir;
      servoGoTo(wiggleDir ? wiggleHigh : wiggleLow, 900);
    }
  }

  // After a reaction ends, glide smoothly to the height matching current mood
  if (millis() > servoRestAt && millis() >= wiggleUntil) {
    servoGoTo(moodToServo(), 1500);        // 1.5s gentle glide back
  }

  // Manual test keys from the Serial Monitor
  while (SerialMonitorInterface.available()) {
    char c = SerialMonitorInterface.read();
    if (c == 'p') { SerialMonitorInterface.println("CMD p"); reactPerk(); }
    if (c == 'h') { SerialMonitorInterface.println("CMD h"); reactHappy(); }
    if (c == 'w') { SerialMonitorInterface.println("CMD w"); reactWater(); }
  }

  aci_loop();
  handleIncoming();

  float lux = light_sensor.readAmbientLight();
  lastLux = lux;

  if (millis() - lastLightRead > LIGHT_INTERVAL) {
    lastLightRead = millis();
    SerialMonitorInterface.print("L:");
    SerialMonitorInterface.println(lux);
    bleSend("L:" + String(lux, 1) + "\n");
  }

  if (millis() - lastMoodSend > 5000) {
    lastMoodSend = millis();
    SerialMonitorInterface.print("MOOD:");
    SerialMonitorInterface.println((int)mood);
    bleSend("S:" + String((int)mood) + "\n");
  }
}