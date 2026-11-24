// AuraPlant v6: vitality + live tuning + accelerometer + approved SmoothOrange display
// Needs AuraApprovedDisplay.h and AuraFrames.h (teammate's files) in this same sketch folder.
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

// ================= TUNABLE VALUES (change live with "set name value") =================
// Servo travel. Find YOUR real limits with the "g" command, then stay ~100 us inside them.
int restUs      = 900;    // lowest (drooped)
int happyUs     = 1850;   // highest (fully up)

// WATER: rise to the top, stay 5 s, then glide down
int waterUs     = 1850;
int waterRise   = 900;    // ms to reach the top
int waterHold   = 5000;   // ms it stays at the top before coming down

// COMPLIMENT: rise to the top FASTER and stay LONGER than water
int compUs      = 1850;
int compRise    = 400;
int compHold    = 8000;
int compStep    = 20;     // vitality gained per compliment (out of 100)

// PAT: small wiggle around where it is, then return to the same height
int patAmp      = 150;    // wiggle size in us either side
int patStepMs   = 140;    // time for each half-wiggle
int patWigMs    = 900;    // total wiggle time

// SHAKE: dizzy for dizDur ms, wiggling a little around where it is
int dizAmp      = 250;
int dizMs       = 300;
int dizDur      = 3000;

int returnMs    = 1500;   // time to glide back down to its normal height
int tippedUs    = 1000;
int lightPct    = 50;     // in bright light the plant rises to this % of its range

// Accelerometer (tune with the "a" watch mode)
int spikeDelta  = 60;     // a reading change bigger than this = ONE bump
int shakeN      = 4;      // this many bumps close together = shake; 1-2 bumps then quiet = pat

// Behaviour
unsigned long DECAY_INTERVAL = 5000;
unsigned long CARE_GRACE     = 10000;
float DECAY_NORMAL = 3.0f;
float DECAY_BRIGHT = 1.0f;
float DECAY_DARK   = 5.0f;
float LIGHT_DARK   = 0.0f;
float LIGHT_GOOD   = 1.8f;

const long ONE_G = 256;
const int ACCEL_PRINT_FLOOR = 10;

const int SERVO_CHANNEL     = 2;
const int SERVO_MIN_LIMIT   = 900;
const int SERVO_MAX_LIMIT   = 2100;
const unsigned long SERVO_STEP_MS = 20;
const int SERVO_DEADBAND_US = 15;    // ignore commands smaller than this
const int IDLE_DEADBAND_US  = 60;    // resting height only follows vitality when it changed this much

// Face IDs understood by the approved display (these index its own face table)
const int FACE_HAPPY      = 0;
const int FACE_CONTENT    = 1;
const int FACE_SAD        = 2;
const int FACE_DIZZY      = 4;
const int FACE_NEED_LIGHT = 5;
const int FACE_NEUTRAL    = 6;

// ================= STATE =================
bool tuneMode = false;
bool accelDebug = false;
String cmdLine = "";

UBMA250 accel;
bool accelOk = false;
int lastAx = 0, lastAy = 0, lastAz = 0;
unsigned long lastAccelRead = 0;
unsigned long lastSpike = 0;
int spikeCount = 0;
int burstPeak = 0;
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
bool lightBright = false;
unsigned long lastLightRead = 0;
unsigned long lastBleLight = 0;
unsigned long lastBleSend = 2500;

float vitality = 60.0f;
unsigned long lastDecay = 0;
unsigned long lastCare = 0;

int lastFace = -1;                // the display helper also reads/writes this
unsigned long dizzyUntil = 0;     // the display helper also reads this
unsigned long happyUntil = 0;
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
unsigned long holdUntil = 0;      // servo stays where it is until this time
int wiggleLow = 900;
int wiggleHigh = 2100;
bool wiggleDir = true;
unsigned long wiggleStepMs = 300;
bool servoOk = false;

// Teammate's display helper. Must come AFTER display, lastFace and dizzyUntil are declared.
#include "AuraApprovedDisplay.h"

// Wake the screen if it was sleeping
void markInteraction() {
  if (auraScene == AURA_SLEEP) auraWakeDisplay();
}

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
  // keep-alive so the controller's 1000 ms failsafe never switches the PWM off
  if (now - lastServoCmd > 400) servoWrite(servoPos);
}

int vitalityToServo() {
  return restUs + (int)((happyUs - restUs) * (vitality / 100.0f));
}

// Normal height: follows vitality, but in bright light never lower than lightPct% of the range.
int restingHeight() {
  int h = vitalityToServo();
  if (lightBright) {
    int lightUs = restUs + (int)((happyUs - restUs) * (lightPct / 100.0f));
    if (lightUs > h) h = lightUs;
  }
  return h;
}

bool isTipped() { return fellOver || fakeTip; }

// Glide to the normal height. Small vitality changes are ignored so the servo does not
// keep twitching every few seconds (that twitching also fed false readings to the accelerometer).
void followResting(bool force) {
  int goal = isTipped() ? tippedUs : restingHeight();
  if (force || abs(goal - servoTarget) > IDLE_DEADBAND_US) servoGoTo(goal, returnMs);
}

// ================= VITALITY =================
void addVitality(float amount) {
  vitality += amount;
  if (vitality > 100.0f) vitality = 100.0f;
  if (vitality < 0.0f) vitality = 0.0f;
}

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
// WATER: rise to the top, stay waterHold ms, then glide down. Screen: fill -> hold -> drain.
void reactWater() {
  markInteraction();
  unsigned long now = millis();
  unsigned long until = now + (unsigned long)waterRise + (unsigned long)waterHold;
  addVitality(30);
  lastCare = now;
  dizzyUntil = 0;
  wiggleUntil = 0;
  happyUntil = until;
  holdUntil = max(holdUntil, until);
  servoGoTo(waterUs, waterRise);
  auraStartWater();
}

// COMPLIMENT: rise to the top faster than water and stay longer. Screen: excited, then very happy.
void reactCompliment() {
  markInteraction();
  unsigned long now = millis();
  unsigned long until = now + (unsigned long)compRise + (unsigned long)compHold;
  addVitality(compStep);
  lastCare = now;
  dizzyUntil = 0;
  wiggleUntil = 0;
  happyUntil = until;
  holdUntil = max(holdUntil, until);
  servoGoTo(compUs, compRise);
  auraStartExcited();
}

// PAT: little wiggle around the current height, then back to where it was. Screen: suddenly excited.
void reactPat() {
  markInteraction();
  unsigned long now = millis();
  lastCare = now;
  happyUntil = now +5000UL;
  auraStartExcited();
  if (now >= holdUntil) {                       // not while it is holding up from water/compliment
    int base = servoPos;
    wiggleLow  = max(base - patAmp, SERVO_MIN_LIMIT);
    wiggleHigh = min(base + patAmp, SERVO_MAX_LIMIT);
    wiggleStepMs = patStepMs;
    wiggleDir = true;
    wiggleUntil = now + patWigMs;
    accelLockUntil = now + patWigMs + 900;      // its own wiggle must not count as another touch
    servoGoTo(wiggleHigh, patStepMs);
  }
}

// SHAKE / FALL: dizzy for dizDur ms
void reactDizzy() {
  markInteraction();
  unsigned long now = millis();
  int base = servoPos;
  dizzyUntil = now + dizDur;
  holdUntil = 0;
  addVitality(-5);
  wiggleLow  = max(base - dizAmp, SERVO_MIN_LIMIT);
  wiggleHigh = min(base + dizAmp, SERVO_MAX_LIMIT);
  wiggleStepMs = dizMs;
  wiggleDir = true;
  wiggleUntil = now + dizDur;
  accelLockUntil = now + dizDur + 1200;         // ignore the vibration it causes itself
  servoGoTo(wiggleHigh, dizMs);
}

// ================= FACE =================
int chooseFace() {
  unsigned long now = millis();
  if (isTipped() || now < dizzyUntil) return FACE_DIZZY;
  if (now < happyUntil) return FACE_HAPPY;
  if (lastLux < LIGHT_DARK) return FACE_NEED_LIGHT;
  if (vitality >= 70) return FACE_CONTENT;
  if (vitality >= 40) return FACE_NEUTRAL;
  return FACE_SAD;
}

void drawEyes(bool open) {
  if (lastFace == FACE_DIZZY) return;
  auraDrawLegacyFace(lastFace, !open);
}

void updateBlink() {
  if (auraAnimationActive()) return;
  if (lastFace < 0 || lastFace == FACE_DIZZY) return;
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
  if (auraAnimationActive()) return;
  int f = chooseFace();
  if (f != lastFace) {
    lastFace = f;
    blinking = false;
    auraDrawLegacyFace(f, false);
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
  else if (cmd == "R:PERK") {                 // phone heard speech: "I heard you"
    markInteraction();
    addVitality(10);
    lastCare = millis();
    auraStartTalking();
  }
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

  // The servo moving (or having just stopped) shakes the whole body.
  // While that is happening, never count it as a touch, a fall or a shake.
  bool servoBusy = (servoPos != servoTarget) || (now - lastServoMove < 700) || (now < wiggleUntil);

  if (accelDebug && delta >= ACCEL_PRINT_FLOOR) {
    SerialMonitorInterface.print("delta=");
    SerialMonitorInterface.print(delta);
    if (servoBusy) SerialMonitorInterface.print("   (servo busy, ignored)");
    else if (delta > spikeDelta) SerialMonitorInterface.print("   <-- counts as a BUMP (spikeDelta=" + String(spikeDelta) + ")");
    SerialMonitorInterface.println();
  }

  // ---- Free fall ----
  long mag2 = (long)ax * ax + (long)ay * ay + (long)az * az;
  if (servoBusy) {
    freefallCount = 0;
  } else if (mag2 < (ONE_G * 4 / 10) * (ONE_G * 4 / 10)) {
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
      markInteraction();
      if (!tuneMode) addVitality(-10);
      SerialMonitorInterface.println("E:TIPPED");
      if (!tuneMode) bleSend("E:TIPPED\n");
    }
  } else if (dot > upMag2 * 8 / 10) {
    tippedSince = 0;
    if (fellOver) {
      fellOver = false;
      markInteraction();
      SerialMonitorInterface.println("E:UPRIGHT");
      if (!tuneMode) bleSend("E:UPRIGHT\n");
    }
  }

  if (now < accelLockUntil || servoBusy || isTipped()) { spikeCount = 0; burstPeak = 0; return; }

  if (delta > spikeDelta) {
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

  // Burst finished (quiet for 500 ms): 1-2 bumps = pat
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
  SerialMonitorInterface.println(F("g 1500       move servo to 1500 us (use this to find your real min/max)"));
  SerialMonitorInterface.println(F("f            glide back to the height matching vitality"));
  SerialMonitorInterface.println(F("v 50         set vitality to 50"));
  SerialMonitorInterface.println(F("w h p d u    water, compliment, pat, dizzy, toggle tipped"));
  SerialMonitorInterface.println(F("i            scan I2C (if the servo controller is missing here, it reset = power problem)"));
  SerialMonitorInterface.println(F("x NAME       preview a screen animation:"));
  SerialMonitorInterface.println(F("             wink look think wiggle excited talk sleep sun water"));
  SerialMonitorInterface.println(F("set NAME N   e.g. set compHold 9000"));
  SerialMonitorInterface.println(F("   names: rest happy waterUs waterRise waterHold compUs compRise compHold compStep"));
  SerialMonitorInterface.println(F("          patAmp patStepMs patWigMs dizAmp dizMs dizDur returnMs tipped"));
  SerialMonitorInterface.println(F("          spike shakeN lightPct lightGood lightDark"));
  SerialMonitorInterface.println(F("l            print the current light reading (lux)"));
  SerialMonitorInterface.println(F("show         print all values as code to paste into the sketch"));
}

void printInt(const char* name, long v) {
  SerialMonitorInterface.print("int "); SerialMonitorInterface.print(name);
  SerialMonitorInterface.print(" = "); SerialMonitorInterface.print(v);
  SerialMonitorInterface.println(";");
}

void printValues() {
  SerialMonitorInterface.println(F("---- PASTE THESE over the matching lines at the top of the sketch ----"));
  printInt("restUs", restUs);       printInt("happyUs", happyUs);
  printInt("waterUs", waterUs);     printInt("waterRise", waterRise);   printInt("waterHold", waterHold);
  printInt("compUs", compUs);       printInt("compRise", compRise);     printInt("compHold", compHold);
  printInt("compStep", compStep);
  printInt("patAmp", patAmp);       printInt("patStepMs", patStepMs);   printInt("patWigMs", patWigMs);
  printInt("dizAmp", dizAmp);       printInt("dizMs", dizMs);           printInt("dizDur", dizDur);
  printInt("returnMs", returnMs);   printInt("tippedUs", tippedUs);     printInt("lightPct", lightPct);
  printInt("spikeDelta", spikeDelta); printInt("shakeN", shakeN);
  SerialMonitorInterface.print("float LIGHT_GOOD = "); SerialMonitorInterface.print(LIGHT_GOOD); SerialMonitorInterface.println("f;");
  SerialMonitorInterface.print("float LIGHT_DARK = "); SerialMonitorInterface.print(LIGHT_DARK); SerialMonitorInterface.println("f;");
}

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "help") { printHelp(); return; }
  if (line == "show") { printValues(); return; }
  if (line == "i") { scanI2C("I2C now"); return; }
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
    followResting(true);
    SerialMonitorInterface.print("returning to "); SerialMonitorInterface.println(restingHeight());
    return;
  }
  if (line.startsWith("x ")) {
    String n = line.substring(2);
    n.trim();
    markInteraction();
    if (n == "wink") auraStartWink();            // only plays while the content face is showing
    else if (n == "look") auraStartLook();
    else if (n == "think") auraStartThink();
    else if (n == "wiggle") auraStartWiggle();
    else if (n == "excited") auraStartExcited();
    else if (n == "talk") auraStartTalking();
    else if (n == "sleep") auraStartSleeping();
    else if (n == "sun") auraStartSunlight();
    else if (n == "water") auraStartWater();
    else { SerialMonitorInterface.println("unknown animation, type help"); return; }
    SerialMonitorInterface.print("animation: "); SerialMonitorInterface.println(n);
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
    else if (name == "compUs") compUs = val;
    else if (name == "compRise") compRise = val;
    else if (name == "compHold") compHold = val;
    else if (name == "compStep") compStep = val;
    else if (name == "patAmp") patAmp = val;
    else if (name == "patStepMs") patStepMs = val;
    else if (name == "patWigMs") patWigMs = val;
    else if (name == "dizAmp") dizAmp = val;
    else if (name == "dizMs") dizMs = val;
    else if (name == "dizDur") dizDur = val;
    else if (name == "returnMs") returnMs = val;
    else if (name == "tipped") tippedUs = val;
    else if (name == "spike") spikeDelta = val;
    else if (name == "shakeN") shakeN = val;
    else if (name == "lightPct") lightPct = val;
    else if (name == "lightGood") LIGHT_GOOD = val;
    else if (name == "lightDark") LIGHT_DARK = val;
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

  // Display settings required by the approved BGR332 frames
  display.begin();
  display.setBitDepth(TSBitDepth8);
  display.setColorMode(TSColorModeBGR);
  display.setFlip(false);            // if the picture is upside down, change to false
  display.setBrightness(7);
  auraDrawLegacyFace(FACE_CONTENT, false);   // show a face right away

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

  auraStartThink();                 // little "just woke up" animation on power-on
  printHelp();
}

// ================= LOOP =================
void loop() {
  unsigned long now = millis();

  readSerialCommands();
  servoUpdate();
  updateAccel();                    // always runs (prints only, in tune mode)

  if (isTipped()) dizzyUntil = now + 500;   // keep the dizzy face, stop animations covering it
  if (tuneMode) auraLastActivity = now;     // never fall asleep while tuning
  auraTick();                               // advances the current screen animation
  updateFace();
  updateBlink();

  if (!tuneMode) updateVitality();

  // Servo: wiggle (pat / dizzy) -> otherwise hold -> otherwise glide to the normal height
  static bool wasWiggling = false;
  bool wiggling = now < wiggleUntil;
  if (wiggling) {
    if (servoPos == servoTarget) {
      wiggleDir = !wiggleDir;
      servoGoTo(wiggleDir ? wiggleHigh : wiggleLow, wiggleStepMs);
    }
  } else if (!tuneMode && now >= holdUntil) {
    followResting(wasWiggling);     // after a wiggle always return to the normal height
  }
  wasWiggling = wiggling;

  aci_loop();
  handleIncoming();

  static bool firstLuxRead = true;
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
      if (lightBright && !firstLuxRead && !tuneMode) {
        markInteraction();
        auraStartSunlight();        // sun rays sweep in
      }
    }
    firstLuxRead = false;
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