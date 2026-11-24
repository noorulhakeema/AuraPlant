// AuraPlant Core v3: Vitality system + LIVE TUNING MODE
// Type commands in the Serial Monitor (set it to "Newline" at the bottom) to
// test servo movement without re-uploading. Type "help" for the list.
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
// Servo positions (microseconds). Bigger number = further one way.
int restUs      = 1200;   // vitality 0   = drooped
int happyUs     = 1900;   // vitality 100 = fully up

// Reactions
int waterAmp    = 150;    // water sway goes +/- this far around the current height
int waterMs     = 700;    // time for ONE sway movement
int waterDur    = 3000;   // total length of the water sway
int dizLow      = 900;    // dizzy sweep lower end
int dizHigh     = 2100;   // dizzy sweep upper end
int dizMs       = 350;    // time for ONE dizzy sweep (smaller = faster)
int dizDur      = 3000;   // total length of dizzy
int compMs      = 500;    // compliment rise time (smaller = faster)
int returnMs    = 1500;   // glide time when going back to the "vitality" height
int tippedUs    = 1000;   // position when tipped over (limp)

// Behaviour
unsigned long DECAY_INTERVAL = 5000;
unsigned long CARE_GRACE     = 10000;
float DECAY_NORMAL = 3.0f;
float DECAY_BRIGHT = 1.0f;
float DECAY_DARK   = 5.0f;
float LIGHT_DARK   = 10.0f;
float LIGHT_GOOD   = 50.0f;

const int SPIKE_DELTA = 60;
const bool DEBUG_ACCEL = false;   // set true to see accelerometer numbers
const long ONE_G = 256;

const int SERVO_CHANNEL   = 2;
const int SERVO_MIN_LIMIT = 900;
const int SERVO_MAX_LIMIT = 2100;
const unsigned long SERVO_STEP_MS = 20;
const int SERVO_DEADBAND_US = 15;

// ================= STATE =================
bool tuneMode = false;            // true = decay, accelerometer and auto-return are frozen
String cmdLine = "";

UBMA250 accel;
bool accelOk = false;
int lastAx = 0, lastAy = 0, lastAz = 0;
unsigned long lastAccelRead = 0;
unsigned long lastSpike = 0;
int spikeCount = 0;
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
  vitality = 100.0f;
  lastCare = millis();
  happyUntil = millis() + 3000;
  servoGoTo(happyUs, compMs);
}

void reactWater() {
  addVitality(30);
  lastCare = millis();
  happyUntil = millis() + 3000;
  int centre = vitalityToServo();
  wiggleLow = centre - waterAmp;
  wiggleHigh = centre + waterAmp;
  wiggleStepMs = waterMs;
  wiggleUntil = millis() + waterDur;
  wiggleDir = true;
  servoGoTo(wiggleHigh, waterMs);
}

void reactPat() {
  heartUntil = millis() + 3000;
  addVitality(5);
  lastCare = millis();
}

void reactDizzy() {
  dizzyUntil = millis() + dizDur;
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

  long mag2 = (long)ax * ax + (long)ay * ay + (long)az * az;
  if (mag2 < (ONE_G * 4 / 10) * (ONE_G * 4 / 10)) {
    freefallCount++;
  } else {
    if (freefallCount >= 2) {
      reactDizzy();
      accelLockUntil = now + 2500;
      SerialMonitorInterface.println("E:FALL");
      bleSend("E:FALL\n");
    }
    freefallCount = 0;
  }

  long dot = (long)ax * upX + (long)ay * upY + (long)az * upZ;
  long upMag2 = (long)upX * upX + (long)upY * upY + (long)upZ * upZ;
  if (dot < upMag2 / 2) {
    if (tippedSince == 0) tippedSince = now;
    if (!fellOver && now - tippedSince > 1500) {
      fellOver = true;
      addVitality(-10);
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

  if (now < accelLockUntil) { spikeCount = 0; return; }
  if (isTipped()) { spikeCount = 0; return; }

  bool servoShaking = (now - lastServoMove < 250) || (now < wiggleUntil);

  if (delta > SPIKE_DELTA && !servoShaking) {
    if (now - lastSpike > 600) spikeCount = 0;
    spikeCount++;
    lastSpike = now;
  }

  if (spikeCount >= 4) {
    spikeCount = 0;
    reactDizzy();
    accelLockUntil = now + 2000;
    SerialMonitorInterface.println("E:SHAKE");
    bleSend("E:SHAKE\n");
    return;
  }

  if (spikeCount > 0 && now - lastSpike > 500) {
    if (spikeCount <= 2) {
      SerialMonitorInterface.println("E:PAT");
      bleSend("E:PAT\n");
      reactPat();
    }
    spikeCount = 0;
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
  SerialMonitorInterface.println(F("t            toggle TUNE MODE (freezes decay, accel, auto-return)"));
  SerialMonitorInterface.println(F("g 1500       move servo to 1500 us (uses returnMs for speed)"));
  SerialMonitorInterface.println(F("f            glide back to the height matching vitality (tests the return)"));
  SerialMonitorInterface.println(F("v 50         set vitality to 50"));
  SerialMonitorInterface.println(F("w h p d u    water, compliment, pat, dizzy, toggle tipped"));
  SerialMonitorInterface.println(F("set NAME N   change a value, e.g.  set waterAmp 250"));
  SerialMonitorInterface.println(F("             names: rest happy waterAmp waterMs waterDur"));
  SerialMonitorInterface.println(F("                    dizLow dizHigh dizMs dizDur compMs returnMs tipped"));
  SerialMonitorInterface.println(F("show         print all values as code to paste into the sketch"));
}

void printValues() {
  SerialMonitorInterface.println(F("---- PASTE THESE over the matching lines at the top of the sketch ----"));
  SerialMonitorInterface.print("int restUs   = "); SerialMonitorInterface.print(restUs);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int happyUs  = "); SerialMonitorInterface.print(happyUs);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterAmp = "); SerialMonitorInterface.print(waterAmp); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterMs  = "); SerialMonitorInterface.print(waterMs);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int waterDur = "); SerialMonitorInterface.print(waterDur); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizLow   = "); SerialMonitorInterface.print(dizLow);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizHigh  = "); SerialMonitorInterface.print(dizHigh);  SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizMs    = "); SerialMonitorInterface.print(dizMs);    SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int dizDur   = "); SerialMonitorInterface.print(dizDur);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int compMs   = "); SerialMonitorInterface.print(compMs);   SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int returnMs = "); SerialMonitorInterface.print(returnMs); SerialMonitorInterface.println(";");
  SerialMonitorInterface.print("int tippedUs = "); SerialMonitorInterface.print(tippedUs); SerialMonitorInterface.println(";");
}

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "help") { printHelp(); return; }
  if (line == "show") { printValues(); return; }
  if (line == "t") {
    tuneMode = !tuneMode;
    SerialMonitorInterface.println(tuneMode ? "TUNE MODE ON (frozen)" : "TUNE MODE OFF (normal)");
    return;
  }
  if (line == "w") { SerialMonitorInterface.println("water"); reactWater(); return; }
  if (line == "h") { SerialMonitorInterface.println("compliment"); reactCompliment(); return; }
  if (line == "p") { SerialMonitorInterface.println("pat"); reactPat(); return; }
  if (line == "d") { SerialMonitorInterface.println("dizzy"); reactDizzy(); return; }
  if (line == "u") { fakeTip = !fakeTip; SerialMonitorInterface.println(fakeTip ? "tipped ON" : "tipped OFF"); return; }
  if (line == "f") {
    wiggleUntil = 0;
    servoGoTo(vitalityToServo(), returnMs);
    SerialMonitorInterface.print("returning to "); SerialMonitorInterface.println(vitalityToServo());
    return;
  }
  if (line.startsWith("g ")) {
    int us = line.substring(2).toInt();
    wiggleUntil = 0;
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
    else if (name == "waterAmp") waterAmp = val;
    else if (name == "waterMs") waterMs = val;
    else if (name == "waterDur") waterDur = val;
    else if (name == "dizLow") dizLow = val;
    else if (name == "dizHigh") dizHigh = val;
    else if (name == "dizMs") dizMs = val;
    else if (name == "dizDur") dizDur = val;
    else if (name == "compMs") compMs = val;
    else if (name == "returnMs") returnMs = val;
    else if (name == "tipped") tippedUs = val;
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

  if (!tuneMode) {
    updateVitality();
    updateAccel();
  }

  // Wiggle (water sway / dizzy sweep)
  if (now < wiggleUntil) {
    if (servoPos == servoTarget) {
      wiggleDir = !wiggleDir;
      servoGoTo(wiggleDir ? wiggleHigh : wiggleLow, wiggleStepMs);
    }
  } else if (!tuneMode) {
    if (isTipped()) servoGoTo(tippedUs, returnMs);
    else servoGoTo(vitalityToServo(), returnMs);
  }

  aci_loop();
  handleIncoming();

  if (now - lastLightRead > 500) {
    lastLightRead = now;
    lastLux = light_sensor.readAmbientLight();
  }
  if (now - lastBleLight > 5000) {
    lastBleLight = now;
    if (!tuneMode) {
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