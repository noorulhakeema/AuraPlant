// AuraPlant Core v4: Vitality system + LIVE TUNING + ACCELEROMETER WATCH
// Serial Monitor must be set to "Newline". Type "help" for the command list.
#include <SPI.h>
#include <STBLE.h>
#include <Wire.h>
#include <Wireling.h>
#include <TSL2572.h>
#include <ServoDriver.h>
#include <TinyScreen.h>
#include <BMA250.h>

ServoDriver servo2(NO_R_REMOVED);

#ifndef BLE_DEBUG
#define BLE_DEBUG true
#endif

#define SerialMonitorInterface SerialUSB

// ================= TUNABLE VALUES (changed live with "set name value") =================
int restUs      = 900;    // lowest (drooped)
int happyUs     = 2100;   // highest (fully up)
int compStep    = 20;     // each compliment adds this much vitality (out of 100)

int waterUs     = 2100;   // how high water lifts the plant (max is 2100)
int waterRise   = 800;    // time to rise (ms), bigger = slower/smoother
int waterHold   = 5000;   // how long it stays up (ms)

int patLift     = 300;    // pat lifts the plant this much (us) above its normal height
int patRise     = 600;    // time to rise on a pat (ms)
int patHold     = 3000;   // how long it stays lifted (ms), heart shows meanwhile

int lightPct    = 50;     // in bright light the plant rises to this % of its range (50 = half)
int dizLow      = 900;
int dizHigh     = 2100;
int dizMs       = 350;
int dizDur      = 3000;
int compMs      = 500;
int returnMs    = 1500;
int tippedUs    = 1000;

// Accelerometer thresholds (tune with the "a" watch mode)
int spikeDelta  = 60;     // a reading change bigger than this counts as ONE bump
int shakeN      = 4;      // this many bumps close together = shake (dizzy)
                          // 1 or 2 bumps then quiet = pat

// Behaviour
unsigned long DECAY_INTERVAL = 5000;
unsigned long CARE_GRACE     = 10000;
float DECAY_NORMAL = 3.0f;
float DECAY_BRIGHT = 1.0f;
float DECAY_DARK   = 5.0f;
float LIGHT_DARK   = 10.0f;
float LIGHT_GOOD   = 50.0f;

const long ONE_G = 256;
const int ACCEL_PRINT_FLOOR = 10;   // watch mode ignores changes smaller than this (noise)

const int SERVO_CHANNEL   = 2;
const int SERVO_MIN_LIMIT = 900;
const int SERVO_MAX_LIMIT = 2100;
const unsigned long SERVO_STEP_MS = 20;
const int SERVO_DEADBAND_US = 15;

// ================= STATE =================
bool tuneMode = false;
bool accelDebug = false;          // "a" toggles: print accelerometer numbers
String cmdLine = "";

UBMA250 accel;
bool accelOk = false;
int lastAx = 0, lastAy = 0, lastAz = 0;
unsigned long lastAccelRead = 0;
unsigned long lastSpike = 0;
int spikeCount = 0;
int burstPeak = 0;                // biggest delta in the current burst
unsigned long accelLockUntil = 0;
int upX = 0, upY = 0, upZ = 0;
int freefallCount = 0;
unsigned long tippedSince = 0;
bool fellOver = false;
bool fakeTip = false;

uint8_t ble_rx_buffer[21];
uint8_t ble_rx_buffer_len = 0;
uint8_t ble_connection_state = false;
#define PIPE_UART_OVER_BTLE_UART_TX_TX 0

TSL2572 light_sensor;
TinyScreen display = TinyScreen(TinyScreenPlus);
float lastLux = 20.0f;
bool lightBright = false;         // true while there is sufficient light
unsigned long lastLightRead = 0;
unsigned long lastBleLight = 0;
unsigned long lastBleSend = 2500;

float vitality = 60.0f;
unsigned long lastDecay = 0;
unsigned long lastCare = 0;

int lastFace = -1;
unsigned long dizzyUntil = 0;
unsigned long heartUntil = 0;
unsigned long happyUntil = 0;
unsigned long lastBeat = 0;
bool beatBig = true;
unsigned long nextBlink = 4000;
unsigned long blinkEnd = 0;
bool blinking = false;

int servoPos = 1200;
int servoStartPos = 1200;
int servoTarget = 1200;
unsigned long servoStartTime = 0;
unsigned long servoDurationMs = 1000;
unsigned long lastServoStep = 0;
unsigned long lastServoMove = 0;
unsigned long lastServoCmd = 0;
unsigned long wiggleUntil = 0;
unsigned long holdUntil = 0;      // servo stays where it is until this time (water hold)
int wiggleLow = 900;
int wiggleHigh = 2100;
bool wiggleDir = true;
unsigned long wiggleStepMs = 900;
bool servoOk = false;

// ================= SERVO =================
void servoWrite(int us) {
  if (!servoOk) return;
  servo2.setServo(SERVO_CHANNEL, us);
  lastServoCmd = millis();
}

void servoGoTo(int us, unsigned long durationMs) {
  us = constrain(us, SERVO_MIN_LIMIT, SERVO_MAX_LIMIT);
  if (abs(us - servoTarget) < SERVO_DEADBAND_US) return;
  servoStartPos = servoPos;
  servoTarget = us;
  servoStartTime = millis();
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
        float ease = t * t * (3.0f - 2.0f * t);
        servoPos = servoStartPos + (int)((servoTarget - servoStartPos) * ease);
      }
      servoWrite(servoPos);
      lastServoMove = now;
    }
  }
  if (now - lastServoCmd > 400) servoWrite(servoPos);
}

int vitalityToServo() {
  return restUs + (int)((happyUs - restUs) * (vitality / 100.0f));
}

// The plant's normal height: follows vitality, but in bright light it is
// never lower than lightPct% of the range (50 = halfway up).
int restingHeight() {
  int h = vitalityToServo();
  if (lightBright) {
    int lightUs = restUs + (int)((happyUs - restUs) * (lightPct / 100.0f));
    if (lightUs > h) h = lightUs;
  }
  return h;
}

// ================= VITALITY =================
void addVitality(float amount) {
  vitality += amount;
  if (vitality > 100.0f) vitality = 100.0f;
  if (vitality < 0.0f) vitality = 0.0f;
}

bool isTipped() { return fellOver || fakeTip; }

void updateVitality() {
  unsigned long now = millis();
  if (now - lastDecay < DECAY_INTERVAL) return;
  lastDecay = now;
  if (now - lastCare < CARE_GRACE) return;
  if (isTipped()) return;
  float step = DECAY_NORMAL;
  if (lastLux >= LIGHT_GOOD) step = DECAY_BRIGHT;
  else if (lastLux < LIGHT_DARK) step = DECAY_DARK;
  addVitality(-step);
}

// ================= REACTIONS =================
void reactCompliment() {
  bool alreadyMax = (vitality >= 99.5f);
  addVitality(compStep);                    // every compliment raises it a bit more
  lastCare = millis();
  happyUntil = millis() + 3000;
  dizzyUntil = 0;                           // a kind word cheers it up
  wiggleUntil = 0;

  if (millis() < holdUntil) return;         // water hold in progress: it will glide to the new height after

  if (alreadyMax) {                         // can't go higher: a quick happy bounce instead
    wiggleLow = happyUs - 150;
    wiggleHigh = happyUs;
    wiggleStepMs = 250;
    wiggleDir = false;                      // false = currently at the top, so it dips first
    wiggleUntil = millis() + 400;
    servoGoTo(wiggleLow, 250);
  } else {
    servoGoTo(restingHeight(), compMs);     // rise to the new, higher height
  }
}

void reactWater() {
  addVitality(30);
  lastCare = millis();
  happyUntil = millis() + waterRise + waterHold;   // happy face while it is up
  wiggleUntil = 0;                                  // no sway
  holdUntil = millis() + waterRise + waterHold;     // stay up, then glide back to normal
  servoGoTo(waterUs, waterRise);
}

void reactPat() {
  heartUntil = millis() + patRise + patHold;       // heart shows while lifted
  addVitality(5);
  lastCare = millis();
  if (millis() >= holdUntil) {                     // don't override a water hold
    wiggleUntil = 0;
    holdUntil = millis() + patRise + patHold;
    servoGoTo(restingHeight() + patLift, patRise);
  }
}

void reactDizzy() {
  dizzyUntil = millis() + dizDur;
  holdUntil = 0;
  addVitality(-5);
  wiggleLow = dizLow;
  wiggleHigh = dizHigh;
  wiggleStepMs = dizMs;
  wiggleUntil = millis() + dizDur;
  wiggleDir = true;
  servoGoTo(wiggleHigh, dizMs);
}

// ================= FACE =================
int chooseFace() {
  unsigned long now = millis();
  if (isTipped()) return 6;
  if (now < dizzyUntil) return 4;
  if (now < heartUntil) return 5;
  if (now < happyUntil) return 0;
  if (lastLux < LIGHT_DARK) return 3;
  if (vitality >= 70) return 0;
  if (vitality >= 40) return 1;
  return 2;
}

const uint8_t HEART[6] = {0b0110110, 0b1111111, 0b1111111, 0b0111110, 0b0011100, 0b0001000};

void drawHeart(bool big) {
  display.drawRect(26, 12, 44, 40, true, 0, 0, 0);
  int s = big ? 5 : 4;
  int x0 = 48 - (7 * s) / 2;
  int y0 = 32 - (6 * s) / 2;
  for (int r = 0; r < 6; r++) {
    for (int c = 0; c < 7; c++) {
      if (HEART[r] & (1 << (6 - c))) {
        display.drawRect(x0 + c * s, y0 + r * s, s, s, true, 255, 0, 60);
      }
    }
  }
}

void drawFace(int face) {
  display.clearScreen();

  if (face == 5) { drawHeart(true); beatBig = true; lastBeat = millis(); return; }

  if (face == 4) {
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
    display.drawLine(22, 24, 36, 24, 255, 255, 255);
    display.drawLine(60, 24, 74, 24, 255, 255, 255);
    display.drawRect(44, 40, 8, 6, true, 0, 150, 255);
    display.setFont(thinPixel7_10ptFontInfo);
    display.fontColor(TS_8b_Yellow, TS_8b_Black);
    display.setCursor(70, 4);
    display.print("zzz");
    return;
  }

  if (face == 6) {
    display.drawRect(24, 16, 10, 12, true, 255, 255, 255);
    display.drawRect(62, 16, 10, 12, true, 255, 255, 255);
    display.drawLine(20, 6, 36, 13, 255, 0, 0);
    display.drawLine(76, 6, 60, 13, 255, 0, 0);
    display.drawLine(28, 50, 36, 42, 255, 0, 0);
    display.drawLine(36, 42, 60, 42, 255, 0, 0);
    display.drawLine(60, 42, 68, 50, 255, 0, 0);
    return;
  }

  display.drawRect(24, 14, 10, 14, true, 255, 255, 255);
  display.drawRect(62, 14, 10, 14, true, 255, 255, 255);

  if (face == 0) {
    display.drawLine(28, 40, 36, 50, 0, 255, 0);
    display.drawLine(36, 50, 60, 50, 0, 255, 0);
    display.drawLine(60, 50, 68, 40, 0, 255, 0);
  } else if (face == 1) {
    display.drawLine(30, 46, 66, 46, 255, 255, 0);
  } else {
    display.drawLine(28, 50, 36, 42, 255, 0, 0);
    display.drawLine(36, 42, 60, 42, 255, 0, 0);
    display.drawLine(60, 42, 68, 50, 255, 0, 0);
  }
}

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
  if (lastFace != 0 && lastFace != 1 && lastFace != 2) return;
  unsigned long now = millis();
  if (!blinking && now > nextBlink) {
    blinking = true;
    blinkEnd = now + 150;
    drawEyes(false);
  } else if (blinking && now > blinkEnd) {
    blinking = false;
    nextBlink = now + 3000 + (now % 2000);
    drawEyes(true);
  }
}

void updateFace() {
  int f = chooseFace();
  if (f != lastFace) {
    lastFace = f;
    blinking = false;
    drawFace(f);
  }
  if (lastFace == 5 && millis() - lastBeat > 300) {
    lastBeat = millis();
    beatBig = !beatBig;
    drawHeart(beatBig);
  }
}

// ================= BLE =================
void bleSend(String msg) {
  if (!ble_connection_state) return;
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

  if (cmd == "A:1" || cmd == "R:HAPPY") reactCompliment();
  else if (cmd == "W:1") reactWater();
  else if (cmd == "R:PERK") { addVitality(10); lastCare = millis(); }
}

// ================= ACCELEROMETER =================
// In tune mode this still runs and PRINTS what it detects, but triggers no reactions.
void updateAccel() {
  if (!accelOk) return;
  unsigned long now = millis();
  if (now - lastAccelRead < 40) return;
  lastAccelRead = now;

  accel.read();
  int ax = accel.X, ay = accel.Y, az = accel.Z;
  int delta = abs(ax - lastAx) + abs(ay - lastAy) + abs(az - lastAz);
  lastAx = ax; lastAy = ay; lastAz = az;

  // ---- WATCH MODE: print every meaningful change ----
  if (accelDebug && delta >= ACCEL_PRINT_FLOOR) {
    SerialMonitorInterface.print("delta=");
    SerialMonitorInterface.print(delta);
    if (delta > spikeDelta) SerialMonitorInterface.print("   <-- counts as a BUMP (spikeDelta=" + String(spikeDelta) + ")");
    SerialMonitorInterface.println();
  }

  // ---- Free fall ----
  long mag2 = (long)ax * ax + (long)ay * ay + (long)az * az;
  if (mag2 < (ONE_G * 4 / 10) * (ONE_G * 4 / 10)) {
    freefallCount++;
  } else {
    if (freefallCount >= 2) {
      SerialMonitorInterface.println("E:FALL");
      accelLockUntil = now + 2500;
      if (!tuneMode) { reactDizzy(); bleSend("E:FALL\n"); }
    }
    freefallCount = 0;
  }

  // ---- Tipped over ----
  long dot = (long)ax * upX + (long)ay * upY + (long)az * upZ;
  long upMag2 = (long)upX * upX + (long)upY * upY + (long)upZ * upZ;
  if (dot < upMag2 / 2) {
    if (tippedSince == 0) tippedSince = now;
    if (!fellOver && now - tippedSince > 1500) {
      fellOver = true;
      if (!tuneMode) addVitality(-10);
      SerialMonitorInterface.println("E:TIPPED");
      if (!tuneMode) bleSend("E:TIPPED\n");
    }
  } else if (dot > upMag2 * 8 / 10) {
    tippedSince = 0;
    if (fellOver) {
      fellOver = false;
      SerialMonitorInterface.println("E:UPRIGHT");
      if (!tuneMode) bleSend("E:UPRIGHT\n");
    }
  }

  if (now < accelLockUntil) { spikeCount = 0; burstPeak = 0; return; }
  if (isTipped()) { spikeCount = 0; burstPeak = 0; return; }

  // The servo's own vibration must not count as a touch
  bool servoShaking = (now - lastServoMove < 250) || (now < wiggleUntil);

  if (delta > spikeDelta && !servoShaking) {
    if (now - lastSpike > 600) { spikeCount = 0; burstPeak = 0; }   // new burst
    spikeCount++;
    if (delta > burstPeak) burstPeak = delta;
    lastSpike = now;
  }

  // Shake: shakeN or more bumps close together
  if (spikeCount >= shakeN) {
    SerialMonitorInterface.print("E:SHAKE  (bumps=");
    SerialMonitorInterface.print(spikeCount);
    SerialMonitorInterface.print(", biggest=");
    SerialMonitorInterface.print(burstPeak);
    SerialMonitorInterface.println(")");
    spikeCount = 0; burstPeak = 0;
    accelLockUntil = now + 2000;
    if (!tuneMode) { reactDizzy(); bleSend("E:SHAKE\n"); }
    return;
  }

  // Burst finished (quiet for 500 ms)
  if (spikeCount > 0 && now - lastSpike > 500) {
    if (spikeCount <= 2) {
      SerialMonitorInterface.print("E:PAT  (bumps=");
      SerialMonitorInterface.print(spikeCount);
      SerialMonitorInterface.print(", biggest=");
      SerialMonitorInterface.print(burstPeak);
      SerialMonitorInterface.println(")");
      if (!tuneMode) { bleSend("E:PAT\n"); reactPat(); }
    } else if (accelDebug) {
      SerialMonitorInterface.print("burst ignored: ");
      SerialMonitorInterface.print(spikeCount);
      SerialMonitorInterface.print(" bumps (needs <=2 for pat or >=");
      SerialMonitorInterface.print(shakeN);
      SerialMonitorInterface.println(" for shake)");
    }
    spikeCount = 0; burstPeak = 0;
  }
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

// ================= TUNING COMMANDS =================
void printHelp() {
  SerialMonitorInterface.println(F("---- COMMANDS (type then press Enter) ----"));
  SerialMonitorInterface.println(F("t            toggle TUNE MODE (freezes decay + reactions; accel only prints)"));
  SerialMonitorInterface.println(F("a            toggle ACCEL WATCH (prints numbers while you pat/shake)"));
  SerialMonitorInterface.println(F("g 1500       move servo to 1500 us"));
  SerialMonitorInterface.println(F("f            glide back to the height matching vitality"));
  SerialMonitorInterface.println(F("v 50         set vitality to 50"));
  SerialMonitorInterface.println(F("w h p d u    water, compliment, pat, dizzy, toggle tipped"));
  SerialMonitorInterface.println(F("set NAME N   e.g. set spike 80"));
  SerialMonitorInterface.println(F("   names: rest happy waterUs waterRise waterHold dizLow dizHigh"));
  SerialMonitorInterface.println(F("          dizMs dizDur compMs returnMs tipped spike shakeN"));
  SerialMonitorInterface.println(F("          patLift patRise patHold lightPct lightGood lightDark compStep"));
  SerialMonitorInterface.println(F("l            print the current light reading (lux)"));
  SerialMonitorInterface.println(F("show         print all values as code to paste into the sketch"));
}

void printValues() {
  SerialMonitorInterface.println(F("---- PASTE THESE over the matching lines at the top of the sketch ----"));
  SerialMonitorInterface.print("int restUs   = "); SerialMonitorInterface.print(restUs);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int happyUs  = "); SerialMonitorInterface.print(happyUs);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterUs   = "); SerialMonitorInterface.print(waterUs);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterRise = "); SerialMonitorInterface.print(waterRise); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterHold = "); SerialMonitorInterface.print(waterHold); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizLow   = "); SerialMonitorInterface.print(dizLow);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizHigh  = "); SerialMonitorInterface.print(dizHigh);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizMs    = "); SerialMonitorInterface.print(dizMs);    SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizDur   = "); SerialMonitorInterface.print(dizDur);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int compMs   = "); SerialMonitorInterface.print(compMs);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int returnMs = "); SerialMonitorInterface.print(returnMs); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int tippedUs = "); SerialMonitorInterface.print(tippedUs); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int spikeDelta = "); SerialMonitorInterface.print(spikeDelta); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int shakeN   = "); SerialMonitorInterface.print(shakeN);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int patLift   = "); SerialMonitorInterface.print(patLift);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int patRise   = "); SerialMonitorInterface.print(patRise);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int patHold   = "); SerialMonitorInterface.print(patHold);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int lightPct  = "); SerialMonitorInterface.print(lightPct); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int compStep  = "); SerialMonitorInterface.print(compStep); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("float LIGHT_GOOD = "); SerialMonitorInterface.print(LIGHT_GOOD); SerialMonitorInterface.println("f;");
  SerialMonitorInterface.print("float LIGHT_DARK = "); SerialMonitorInterface.print(LIGHT_DARK); SerialMonitorInterface.println("f;");
}

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "help") { printHelp(); return; }
  if (line == "show") { printValues(); return; }
  if (line == "t") {
    tuneMode = !tuneMode;
    SerialMonitorInterface.println(tuneMode ? "TUNE MODE ON (frozen; accel only prints)" : "TUNE MODE OFF (normal)");
    return;
  }
  if (line == "a") {
    accelDebug = !accelDebug;
    spikeCount = 0; burstPeak = 0;
    SerialMonitorInterface.println(accelDebug ? "ACCEL WATCH ON" : "ACCEL WATCH OFF");
    return;
  }
  if (line == "l") {
    SerialMonitorInterface.print("lux = "); SerialMonitorInterface.print(lastLux);
    SerialMonitorInterface.print("   good>="); SerialMonitorInterface.print(LIGHT_GOOD);
    SerialMonitorInterface.print("   dark<"); SerialMonitorInterface.print(LIGHT_DARK);
    SerialMonitorInterface.println(lightBright ? "   -> SUFFICIENT" : "   -> not enough");
    return;
  }
  if (line == "w") { SerialMonitorInterface.println("water"); reactWater(); return; }
  if (line == "h") { SerialMonitorInterface.println("compliment"); reactCompliment(); return; }
  if (line == "p") { SerialMonitorInterface.println("pat"); reactPat(); return; }
  if (line == "d") { SerialMonitorInterface.println("dizzy"); reactDizzy(); return; }
  if (line == "u") { fakeTip = !fakeTip; SerialMonitorInterface.println(fakeTip ? "tipped ON" : "tipped OFF"); return; }
  if (line == "f") {
    wiggleUntil = 0;
    holdUntil = 0;
    servoGoTo(restingHeight(), returnMs);
    SerialMonitorInterface.print("returning to "); SerialMonitorInterface.println(restingHeight());
    return;
  }
  if (line.startsWith("g ")) {
    int us = line.substring(2).toInt();
    wiggleUntil = 0;
    holdUntil = 0;
    servoGoTo(us, returnMs);
    SerialMonitorInterface.print("going to "); SerialMonitorInterface.println(constrain(us, SERVO_MIN_LIMIT, SERVO_MAX_LIMIT));
    return;
  }
  if (line.startsWith("v ")) {
    vitality = constrain(line.substring(2).toInt(), 0, 100);
    SerialMonitorInterface.print("vitality = "); SerialMonitorInterface.println((int)vitality);
    return;
  }
  if (line.startsWith("set ")) {
    String rest = line.substring(4);
    int sp = rest.indexOf(' ');
    if (sp < 0) { SerialMonitorInterface.println("usage: set NAME NUMBER"); return; }
    String name = rest.substring(0, sp);
    int val = rest.substring(sp + 1).toInt();
    bool ok = true;
    if (name == "rest") restUs = val;
    else if (name == "happy") happyUs = val;
    else if (name == "waterUs") waterUs = val;
    else if (name == "waterRise") waterRise = val;
    else if (name == "waterHold") waterHold = val;
    else if (name == "dizLow") dizLow = val;
    else if (name == "dizHigh") dizHigh = val;
    else if (name == "dizMs") dizMs = val;
    else if (name == "dizDur") dizDur = val;
    else if (name == "compMs") compMs = val;
    else if (name == "returnMs") returnMs = val;
    else if (name == "tipped") tippedUs = val;
    else if (name == "spike") spikeDelta = val;
    else if (name == "shakeN") shakeN = val;
    else if (name == "patLift") patLift = val;
    else if (name == "patRise") patRise = val;
    else if (name == "patHold") patHold = val;
    else if (name == "lightPct") lightPct = val;
    else if (name == "lightGood") LIGHT_GOOD = val;
    else if (name == "lightDark") LIGHT_DARK = val;
    else if (name == "compStep") compStep = val;
    else ok = false;
    if (ok) { SerialMonitorInterface.print(name); SerialMonitorInterface.print(" = "); SerialMonitorInterface.println(val); }
    else SerialMonitorInterface.println("unknown name, type help");
    return;
  }
  SerialMonitorInterface.println("unknown command, type help");
}

void readSerialCommands() {
  while (SerialMonitorInterface.available()) {
    char c = SerialMonitorInterface.read();
    if (c == '\n' || c == '\r') {
      if (cmdLine.length()) { handleCommand(cmdLine); cmdLine = ""; }
    } else {
      cmdLine += c;
    }
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
  display.setFlip(false);
  display.setBrightness(10);
  accel.begin(BMA250_range_2g, BMA250_update_time_16ms);
  scanI2C("before servo");

  BLEsetup();                       // BLE first (keep this order!)
  scanI2C("after BLE");

  pinMode(9, OUTPUT);
  digitalWrite(9, HIGH);
  delay(200);
  scanI2C("after releasing pin 9");

  servoOk = (servo2.begin(20000) == 0);
  scanI2C("after servo2 begin");

  accel.read();
  accelOk = true;
  lastAx = accel.X; lastAy = accel.Y; lastAz = accel.Z;
  upX = accel.X; upY = accel.Y; upZ = accel.Z;   // hold it upright and still at power-on!

  if (servoOk) {
    servo2.setFailsafe(1000);
    SerialMonitorInterface.println("Servo controller OK");
    servoPos = servoTarget = servoStartPos = vitalityToServo();
    servoWrite(servoPos);
  } else {
    SerialMonitorInterface.println("Servo controller NOT detected!");
  }
  printHelp();
}

// ================= LOOP =================
void loop() {
  unsigned long now = millis();

  readSerialCommands();
  servoUpdate();
  updateFace();
  updateBlink();
  updateAccel();                    // always runs (prints only, in tune mode)

  if (!tuneMode) updateVitality();

  if (now < wiggleUntil) {
    if (servoPos == servoTarget) {
      wiggleDir = !wiggleDir;
      servoGoTo(wiggleDir ? wiggleHigh : wiggleLow, wiggleStepMs);
    }
  } else if (!tuneMode && now >= holdUntil) {
    if (isTipped()) servoGoTo(tippedUs, returnMs);
    else servoGoTo(restingHeight(), returnMs);
  }

  aci_loop();
  handleIncoming();

  if (now - lastLightRead > 500) {
    lastLightRead = now;
    lastLux = light_sensor.readAmbientLight();

    // Bright when lux >= LIGHT_GOOD. Stays bright until lux drops 20% below it.
    bool wasBright = lightBright;
    if (!lightBright && lastLux >= LIGHT_GOOD) lightBright = true;
    else if (lightBright && lastLux < LIGHT_GOOD * 0.8f) lightBright = false;
    if (lightBright != wasBright) {
      SerialMonitorInterface.print(lightBright ? "LIGHT: sufficient (lux=" : "LIGHT: not enough (lux=");
      SerialMonitorInterface.print(lastLux);
      SerialMonitorInterface.println(")");
    }
  }
  if (now - lastBleLight > 5000) {
    lastBleLight = now;
    if (!tuneMode && !accelDebug) {
      SerialMonitorInterface.print("L:");
      SerialMonitorInterface.println(lastLux);
    }
    bleSend("L:" + String(lastLux, 1) + "\n");
  }
  if (now - lastBleSend > 5000) {
    lastBleSend = now;
    bleSend("S:" + String((int)vitality) + "\n");
  }
}