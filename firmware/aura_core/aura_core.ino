// AuraPlant Core: Audio DSP + Light + Non-Blocking Servo + Phone BLE Bridge + Accelerometer
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
const long ONE_G = 256;            // about 256 counts per g at the 2g range; check by printing at rest
const int SPIKE_DELTA = 60;     // change between readings that counts as a bump (tune this)
const bool DEBUG_ACCEL = true;  // prints deltas so you can tune; set false later

// ---- BLE ----
uint8_t ble_rx_buffer[21];
uint8_t ble_rx_buffer_len = 0;
uint8_t ble_connection_state = false;
#define PIPE_UART_OVER_BTLE_UART_TX_TX 0

// ---- Mic ----
#define MIC_PIN A1
#define MIC_PORT 1

// ---- Voice / timing settings ----
const float TALK_MARGIN = 2.5f;
float loudSmooth = 0.0f;
const unsigned long LIGHT_INTERVAL = 5000;
const bool DEBUG_LOUDNESS = false;          // set false for a quiet Serial Monitor
const unsigned long TALK_COOLDOWN = 3500;
unsigned long lastTalkTime = 0;
const float MAX_GOOD_BASELINE = 5.0f;

TSL2572 light_sensor;
TinyScreen display = TinyScreen(TinyScreenPlus);
int lastFace = -1;                                 // remembers which face is showing
float baseline = 2.5f;
float talkThreshold = 3.7f;
float talkScore = 0.0f;
unsigned long lastLightRead = 0;

// ---- Re-arm logic ----
bool armed = true;
unsigned long quietStart = 0;

// ---- Servo ----
const int SERVO_CHANNEL = 2;
const int SERVO_REST  = 1200;
const int SERVO_PERK  = 1500;
const int SERVO_HAPPY = 1900;
const unsigned long SERVO_STEP_MS = 20;
const int SERVO_US_PER_STEP = 8;          // normal slow speed
int servoUsPerStep = SERVO_US_PER_STEP;

// Water wiggle range. Start here and widen in ~100 us steps only if nothing buzzes or hits a stop.
const int SERVO_MIN_LIMIT = 900;
const int SERVO_MAX_LIMIT = 2100;
int wiggleLow = SERVO_PERK, wiggleHigh = SERVO_HAPPY;
unsigned long wiggleHalfMs = 400;

int servoPos    = SERVO_REST;
int servoTarget = SERVO_REST;
bool servoOk = false;
unsigned long lastServoStep = 0;
unsigned long lastServoMove = 0;
unsigned long lastServoCmd  = 0;
unsigned long servoRestAt   = 0;
unsigned long wiggleUntil   = 0;

// ---- Mood system ----
float mood = 60.0f;                          // 0-100
float lastLux = 0.0f;                        // latest light reading
unsigned long lastMoodTick = 0;
unsigned long lastMoodSend = 2500;           // offset so it doesn't collide with the light message
unsigned long lastWatered = 0;               // millis() when last watered

const unsigned long WATER_INTERVAL = 20000;  // DEMO: 20 s (real plant: 3 days = 259200000)
const float LIGHT_GOOD = 5.0f;               // placeholder, we'll tune it
const float LIGHT_DARK = 0.5f;               // placeholder, we'll tune it

// ---- Face / blink ----
unsigned long reactUntil = 0;     // forces the happy face until this time
unsigned long nextBlink = 4000;   // when the next blink starts
unsigned long blinkEnd = 0;
bool blinking = false;

// ================= SERVO =================
void servoWrite(int us) {
  if (!servoOk) return;
  servo2.setServo(SERVO_CHANNEL, us);
  lastServoCmd = millis();
}

void servoGoTo(int us) { servoTarget = us; }

void servoUpdate() {
  if (!servoOk) return;
  unsigned long now = millis();

  if (now - lastServoStep >= SERVO_STEP_MS) {
    unsigned long steps = (now - lastServoStep) / SERVO_STEP_MS;
    lastServoStep = now;
    if (servoPos != servoTarget) {
      int move = servoUsPerStep * (int)steps;
      if (servoPos < servoTarget) servoPos = min(servoPos + move, servoTarget);
      else                        servoPos = max(servoPos - move, servoTarget);
      servoWrite(servoPos);
      lastServoMove = now;
    }
  }

  // Keep-alive so the controller's failsafe (1000 ms) doesn't switch the PWM off
  if (now - lastServoCmd > 400) servoWrite(servoPos);
}

// ================= MOOD =================
void addMood(float amount) {
  mood += amount;
  if (mood > 100.0f) mood = 100.0f;
  if (mood < 0.0f)   mood = 0.0f;
}

// ================= REACTIONS =================
void reactPerk() {                         // small rise: "I heard you"
  servoGoTo(SERVO_PERK);
  servoRestAt = millis() + 2500;           // starts lowering after 2.5 s
  addMood(5);
}

void reactHappy() {                        // full rise: "that was kind!"
  servoGoTo(SERVO_HAPPY);
  servoRestAt = millis() + 4500;           // rises ~1.75 s, holds ~2.75 s, then lowers
  addMood(15);
  reactUntil = millis() + 3000;            // show the happy face for 3 s
}

void reactWater() {                        // big fast sweep: "thank you for the water!"
  servoUsPerStep = 30;                     // fast: ~1500 us/s
  wiggleLow = SERVO_MIN_LIMIT;
  wiggleHigh = SERVO_MAX_LIMIT;
  wiggleHalfMs = 1000;                     // long enough to reach each end
  wiggleUntil = millis() + 4000;           // about 4 sweeps
  servoRestAt = wiggleUntil;
  reactUntil = wiggleUntil;                // happy face while it wiggles
}

// ================= MIC =================
float readLoudness() {
  const int N = 150;
  int samples[N];
  long sum = 0;
  for (int i = 0; i < N; i++) {
    samples[i] = analogRead(MIC_PIN);
    sum += samples[i];
    delayMicroseconds(400);
  }
  float mean = sum / (float)N;
  float sq = 0;
  for (int i = 0; i < N; i++) {
    float d = samples[i] - mean;
    sq += d * d;
  }
  return sqrt(sq / N);
}

void calibrate() {
  SerialMonitorInterface.println("Calibrating... stay quiet for 3 s");
  const int WINDOWS = 25;
  float v[WINDOWS];
  for (int i = 0; i < WINDOWS; i++) v[i] = readLoudness();

  for (int i = 1; i < WINDOWS; i++) {
    float key = v[i];
    int j = i - 1;
    while (j >= 0 && v[j] > key) { v[j + 1] = v[j]; j--; }
    v[j + 1] = key;
  }

  float total = 0;
  for (int i = 0; i < WINDOWS / 2; i++) total += v[i];
  baseline = total / (WINDOWS / 2);
  talkThreshold = baseline + TALK_MARGIN;

  SerialMonitorInterface.print("Baseline: ");
  SerialMonitorInterface.print(baseline);
  SerialMonitorInterface.print("  Threshold: ");
  SerialMonitorInterface.println(talkThreshold);
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

  // ---------- GREEN LEAVES ----------
  // Shared across all expressions.
  display.drawRect(39, 5, 9, 4, true, 83, 220, 77);
  display.drawRect(30, 7, 17, 5, true, 83, 220, 77);
  display.drawRect(23, 10, 22, 5, true, 83, 220, 77);
  display.drawRect(20, 14, 20, 4, true, 83, 220, 77);
  display.drawRect(24, 18, 13, 3, true, 83, 220, 77);

  display.drawRect(48, 5, 9, 4, true, 83, 220, 77);
  display.drawRect(49, 7, 17, 5, true, 83, 220, 77);
  display.drawRect(51, 10, 22, 5, true, 83, 220, 77);
  display.drawRect(56, 14, 20, 4, true, 83, 220, 77);
  display.drawRect(59, 18, 13, 3, true, 83, 220, 77);

  // ---------- BLUSH ----------
  // Dizzy uses cyan-blue; all other faces use pink.
  if (face == 4) {
    for (int i = 0; i < 3; i++) {
      int x = 12 + i * 4;
      int y = (i == 1) ? 43 : 41;
      display.drawRect(x, y, 2, 7, true, 112, 211, 255);
      display.drawRect(82 - i * 4, y, 2, 7, true, 112, 211, 255);
    }
  } else {
    display.drawRect(11, 42, 11, 4, true, 255, 143, 177);
    display.drawRect(13, 46, 7, 2, true, 255, 143, 177);
    display.drawRect(74, 42, 11, 4, true, 255, 143, 177);
    display.drawRect(76, 46, 7, 2, true, 255, 143, 177);
  }

  // ---------- EYES ----------
  if (face == 0) {
    // Happy: closed, upward-curved eyes.
    display.drawLine(24, 32, 29, 26, 255, 255, 255);
    display.drawLine(29, 26, 34, 32, 255, 255, 255);
    display.drawLine(62, 32, 67, 26, 255, 255, 255);
    display.drawLine(67, 26, 72, 32, 255, 255, 255);

  } else if (face == 3) {
    // Thirsty: tired, horizontal eyes.
    display.drawRect(24, 31, 11, 3, true, 255, 255, 255);
    display.drawRect(61, 31, 11, 3, true, 255, 255, 255);

  } else if (face == 4) {
    // Dizzy: two pixel-style spirals.
    display.drawLine(23, 29, 23, 25, 255, 255, 255);
    display.drawLine(23, 25, 28, 22, 255, 255, 255);
    display.drawLine(28, 22, 34, 25, 255, 255, 255);
    display.drawLine(34, 25, 34, 32, 255, 255, 255);
    display.drawLine(34, 32, 27, 35, 255, 255, 255);
    display.drawLine(27, 35, 26, 29, 255, 255, 255);
    display.drawLine(26, 29, 30, 27, 255, 255, 255);

    display.drawLine(61, 29, 61, 25, 255, 255, 255);
    display.drawLine(61, 25, 66, 22, 255, 255, 255);
    display.drawLine(66, 22, 72, 25, 255, 255, 255);
    display.drawLine(72, 25, 72, 32, 255, 255, 255);
    display.drawLine(72, 32, 65, 35, 255, 255, 255);
    display.drawLine(65, 35, 64, 29, 255, 255, 255);
    display.drawLine(64, 29, 68, 27, 255, 255, 255);

  } else {
    // Okay and Sad: upright white eyes.
    display.drawRect(25, 25, 9, 12, true, 255, 255, 255);
    display.drawRect(62, 25, 9, 12, true, 255, 255, 255);
  }

  // ---------- MOUTHS ----------
  if (face == 0) {
    // Happy: pink open smile.
    display.drawRect(39, 43, 18, 5, true, 255, 150, 185);
    display.drawRect(42, 48, 12, 4, true, 255, 150, 185);
    display.drawRect(45, 52, 6, 2, true, 255, 150, 185);

  } else if (face == 1) {
    // Okay: small white smile.
    display.drawLine(42, 44, 46, 48, 255, 255, 255);
    display.drawLine(46, 48, 50, 48, 255, 255, 255);
    display.drawLine(50, 48, 54, 44, 255, 255, 255);

  } else if (face == 2) {
    // Sad: downturned mouth.
    display.drawLine(40, 51, 45, 46, 255, 255, 255);
    display.drawLine(45, 46, 51, 46, 255, 255, 255);
    display.drawLine(51, 46, 56, 51, 255, 255, 255);

  } else if (face == 3) {
    // Thirsty: small white frown.
    display.drawLine(42, 49, 46, 45, 255, 255, 255);
    display.drawLine(46, 45, 50, 45, 255, 255, 255);
    display.drawLine(50, 45, 54, 49, 255, 255, 255);

    // Small cyan water indicator.
    display.drawRect(82, 23, 4, 5, true, 70, 190, 255);
    display.drawRect(80, 28, 8, 5, true, 70, 190, 255);
    display.drawRect(82, 33, 4, 2, true, 70, 190, 255);

  } else if (face == 4) {
    // Dizzy: connected ^^^ mouth.
    display.drawLine(36, 50, 40, 45, 255, 255, 255);
    display.drawLine(40, 45, 44, 50, 255, 255, 255);
    display.drawLine(44, 50, 48, 45, 255, 255, 255);
    display.drawLine(48, 45, 52, 50, 255, 255, 255);
    display.drawLine(52, 50, 56, 45, 255, 255, 255);
    display.drawLine(56, 45, 60, 50, 255, 255, 255);
  }
}


// Redraw only the eyes. Black rectangles erase the old eyes first.

void drawEyes(bool open) {
  // Do not alter special thirsty or dizzy eyes.
  if (lastFace == 3 || lastFace == 4) return;

  // Clear ONLY the eye regions.
  display.drawRect(22, 22, 15, 17, true, 0, 0, 0);
  display.drawRect(59, 22, 15, 17, true, 0, 0, 0);

  if (!open || lastFace == 0) {
    // Blink: both eyes become upward arches.
    // Happy already uses these eyes.
    display.drawLine(24, 32, 29, 26, 255, 255, 255);
    display.drawLine(29, 26, 34, 32, 255, 255, 255);

    display.drawLine(62, 32, 67, 26, 255, 255, 255);
    display.drawLine(67, 26, 72, 32, 255, 255, 255);
  } else {
    // Restore the original rectangular eyes.
    display.drawRect(25, 25, 9, 12, true, 255, 255, 255);
    display.drawRect(62, 25, 9, 12, true, 255, 255, 255);
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

  // ---- Free fall: total acceleration near 0 g for 2+ readings ----
  long mag2 = (long)ax * ax + (long)ay * ay + (long)az * az;
  if (mag2 < (ONE_G * 4 / 10) * (ONE_G * 4 / 10)) {      // below 0.4 g
    freefallCount++;
  } else {
    if (freefallCount >= 2) {                            // fall just ended
      dizzyUntil = now + 4000;
      addMood(-20);
      servoGoTo(SERVO_REST);
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
  bool servoShaking = (now - lastServoMove < 200) || (now < wiggleUntil);

  // Count bumps in a burst
if (delta > SPIKE_DELTA && !servoShaking) {
      if (now - lastSpike > 600) spikeCount = 0;   // new burst
    spikeCount++;
    lastSpike = now;
    lastTalkTime = now;                          // taps are noisy: keep the mic from reacting
  }

  // Shake: several spikes close together
  if (spikeCount >= 4) {
    spikeCount = 0;
    dizzyUntil = now + 3000;
    accelLockUntil = now + 2000;
    addMood(-10);
    servoGoTo(SERVO_REST);
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

// ================= SETUP =================
void setup() {
  SerialMonitorInterface.begin(115200);
  while (!SerialMonitorInterface && millis() < 3000);

  analogReadResolution(12);

  Wire.begin();
  Wireling.begin();
  Wireling.selectPort(MIC_PORT);
  light_sensor.init(GAIN_16X);
  display.begin();
  display.setFlip(true);        // remove if the face is upside down
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

  drawFace(1);                  // show a neutral face while warming up

  SerialMonitorInterface.println("Warming up mic (20 s), stay quiet...");
  unsigned long warmStart = millis();
  while (millis() - warmStart < 20000) {
    readLoudness();
    aci_loop();
    servoUpdate();
  }

  for (int attempt = 0; attempt < 5; attempt++) {
    calibrate();
    if (baseline < MAX_GOOD_BASELINE) break;
    SerialMonitorInterface.println("Baseline too high, retrying...");
  }
  loudSmooth = baseline;
}

// ================= LOOP =================
void loop() {
  servoUpdate();

  updateMood();
  updateFace();
  updateBlink();
  updateAccel();

  // Wiggle: flip between two positions (fast and wide after watering, small otherwise)
  if (millis() < wiggleUntil) {
    servoGoTo(((millis() / wiggleHalfMs) % 2) ? wiggleHigh : wiggleLow);
  } else if (servoUsPerStep != SERVO_US_PER_STEP) {
    servoUsPerStep = SERVO_US_PER_STEP;      // back to normal speed
    wiggleLow = SERVO_PERK; wiggleHigh = SERVO_HAPPY; wiggleHalfMs = 400;
  }

  // Manual test keys from the Serial Monitor
  while (SerialMonitorInterface.available()) {
    char c = SerialMonitorInterface.read();
    if (c == 'p') { SerialMonitorInterface.println("CMD p"); reactPerk(); }
    if (c == 'h') { SerialMonitorInterface.println("CMD h"); reactHappy(); }
    if (c == 'w') { SerialMonitorInterface.println("CMD w"); reactWater(); }
  }

  float loud = readLoudness();
  aci_loop();
  handleIncoming();

  loudSmooth = 0.3f * loudSmooth + 0.7f * loud;

  if (loudSmooth < baseline) baseline = 0.95f * baseline + 0.05f * loudSmooth;
  talkThreshold = baseline + TALK_MARGIN;

  if (DEBUG_LOUDNESS) {
    SerialMonitorInterface.print("M:");
    SerialMonitorInterface.print(loud);
    SerialMonitorInterface.print("  smooth:");
    SerialMonitorInterface.print(loudSmooth);
    SerialMonitorInterface.print("  thr:");
    SerialMonitorInterface.println(talkThreshold);
  }

  bool inCooldown = (millis() - lastTalkTime < TALK_COOLDOWN);
  bool plantBusy = (millis() - lastServoMove < 1500) || (millis() < servoRestAt);
  // Re-arm only after 1.5 s of real quiet
  if (loudSmooth < baseline + 1.0f) {
    if (quietStart == 0) quietStart = millis();
    if (millis() - quietStart > 1500) armed = true;
  } else {
    quietStart = 0;
  }

  if (armed && !plantBusy && !inCooldown && loudSmooth > talkThreshold) {
    talkScore += 1.0f;
  } else {
    talkScore = 0.0f;
  }

  bool micOk = baseline < MAX_GOOD_BASELINE;

  if (micOk && talkScore >= 2.0f) {
    SerialMonitorInterface.println("E:TALK");
    bleSend("E:TALK\n");
    lastTalkTime = millis();
    armed = false;                         // must hear quiet before the next trigger
    talkScore = 0.0f;
    reactPerk();
  }

  // After a reaction ends, settle at the height that matches the mood
  if (millis() > servoRestAt && millis() >= wiggleUntil) {
    servoGoTo(moodToServo());
  }

  if (millis() - lastLightRead > LIGHT_INTERVAL) {
    lastLightRead = millis();
    float lux = light_sensor.readAmbientLight();
    SerialMonitorInterface.print("L:");
    SerialMonitorInterface.println(lux);
    bleSend("L:" + String(lux, 1) + "\n");
  }
  float lux = light_sensor.readAmbientLight();
  lastLux = lux;

  if (millis() - lastMoodSend > 5000) {
    lastMoodSend = millis();
    SerialMonitorInterface.print("MOOD:");
    SerialMonitorInterface.println((int)mood);
    bleSend("S:" + String((int)mood) + "\n");
  }
}