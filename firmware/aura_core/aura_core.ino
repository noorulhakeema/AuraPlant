// AuraPlant Core: Audio DSP + Light + Non-Blocking Servo + Phone BLE Bridge
#include <SPI.h>
#include <STBLE.h>
#include <Wire.h>
#include <Wireling.h>
#include <TSL2572.h>
#include <ServoDriver.h>
#include <TinyScreen.h>

ServoDriver servo(NO_R_REMOVED);
ServoDriver servo2(NO_R_REMOVED);   // used after BLE, never pulses the reset pin

#ifndef BLE_DEBUG
#define BLE_DEBUG true
#endif

#define SerialMonitorInterface SerialUSB

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
TinyScreen display = TinyScreen(TinyScreenPlus);   // same line as the light tutorial
int lastFace = -1;                                 // remembers which face is showing
float baseline = 2.5f;
float talkThreshold = 3.7f;
float talkScore = 0.0f;
unsigned long lastLightRead = 0;

// ---- Re-arm logic (NEW) ----
bool armed = true;
unsigned long quietStart = 0;

// ---- Servo ----
const int SERVO_CHANNEL = 1;
const int SERVO_REST  = 1200;
const int SERVO_PERK  = 1500;
const int SERVO_HAPPY = 1900;
const unsigned long SERVO_STEP_MS = 20;
const int SERVO_US_PER_STEP = 8;          // was 15, faster so the wiggle is visible

int servoPos    = SERVO_REST;
int servoTarget = SERVO_REST;
bool servoOk = false;
unsigned long lastServoStep = 0;
unsigned long lastServoMove = 0;
unsigned long lastServoCmd  = 0;
unsigned long servoRestAt   = 0;
unsigned long wiggleUntil   = 0;           // NEW

// ---- Mood system ----
float mood = 60.0f;                          // 0-100
float lastLux = 0.0f;                        // latest light reading
unsigned long lastMoodTick = 0;
unsigned long lastMoodSend = 2500;           // offset so it doesn't collide with the light message
unsigned long lastWatered = 0;               // millis() when last watered

const unsigned long WATER_INTERVAL = 20000;  // DEMO: 1 minute (real plant: 3 days = 259200000)
const float LIGHT_GOOD = 5.0f;               // placeholder, we'll tune it
const float LIGHT_DARK = 0.5f;               // placeholder, we'll tune it

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
      int move = SERVO_US_PER_STEP * (int)steps;
      if (servoPos < servoTarget) servoPos = min(servoPos + move, servoTarget);
      else                        servoPos = max(servoPos - move, servoTarget);
      servoWrite(servoPos);
      lastServoMove = now;
    }
  }

  // Keep-alive so the controller's failsafe (1000 ms) doesn't switch the PWM off
  if (now - lastServoCmd > 400) servoWrite(servoPos);
}

// ---- Reactions (NEW) ----
void reactPerk() {                         // small rise: "I heard you"
  servoGoTo(SERVO_PERK);
  servoRestAt = millis() + 2500;           // starts lowering after 2.5 s
  addMood(5);
}

void reactHappy() {                        // full rise: "that was kind!"
  servoGoTo(SERVO_HAPPY);
  servoRestAt = millis() + 4500;           // rises ~1.75 s, holds ~2.75 s, then lowers
  addMood(15);
}

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

void addMood(float amount) {
  mood += amount;
  if (mood > 100.0f) mood = 100.0f;
  if (mood < 0.0f)   mood = 0.0f;
}

// Runs once per second: slow, steady changes
void updateMood() {
  if (millis() - lastMoodTick < 1000) return;
  lastMoodTick = millis();

  addMood(-0.05f);                                   // everything fades slowly

  if (lastLux >= LIGHT_GOOD)      addMood(+0.5f);    // was +0.10f
  else if (lastLux < LIGHT_DARK)  addMood(-0.3f);    // was -0.10f

  if (millis() - lastWatered > WATER_INTERVAL) addMood(-1.0f);   // was -0.20f
}

int moodToServo() {
  // mood 0 -> SERVO_REST (down), mood 100 -> SERVO_HAPPY (fully up)
  return SERVO_REST + (int)((SERVO_HAPPY - SERVO_REST) * (mood / 100.0f));
}
// Which face should we show right now? 0=happy 1=okay 2=sad 3=thirsty
int chooseFace() {
  if (millis() - lastWatered > WATER_INTERVAL) return 3;
  if (mood >= 70) return 0;
  if (mood >= 40) return 1;
  return 2;
}

void drawFace(int face) {
  display.clearScreen();

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

// Call every loop. Only redraws when the face changes.
void updateFace() {
  int f = chooseFace();
  if (f != lastFace) {
    lastFace = f;
    drawFace(f);
  }
}

void setup() {
  SerialMonitorInterface.begin(115200);
  while (!SerialMonitorInterface && millis() < 3000);

  analogReadResolution(12);

  Wire.begin();
  Wireling.begin();
  Wireling.selectPort(MIC_PORT);
  light_sensor.init(GAIN_16X);
  display.begin();
  display.setFlip(true);        // as in the light tutorial; remove if the face is upside down
  display.setBrightness(10);
  scanI2C("before servo");

  BLEsetup();                       // BLE first
  scanI2C("after BLE");

  pinMode(9, OUTPUT);               // release the shared reset line
  digitalWrite(9, HIGH);
  delay(200);                       // give the controller time to boot
  scanI2C("after releasing pin 9");

  servoOk = (servo2.begin(20000) == 0);   // no reset pin used
  scanI2C("after servo2 begin");
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

void bleSend(String msg) {
  if (!ble_connection_state) return;       // NEW: nobody connected, don't send
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

  if (cmd == "A:1" || cmd == "R:HAPPY") reactHappy();   // kind word -> wiggle
  else if (cmd == "R:PERK") reactPerk();
  else if (cmd == "W:1") {
    lastWatered = millis();
    addMood(20);
    SerialMonitorInterface.println("Watered!");
  }
}

void loop() {
  servoUpdate();

  updateMood();
  updateFace();
  // Happy wiggle: flip between two positions every 400 ms
  if (millis() < wiggleUntil) {
    servoGoTo(((millis() / 400) % 2) ? SERVO_HAPPY : SERVO_PERK);
  }

  // Manual test keys from the Serial Monitor
  while (SerialMonitorInterface.available()) {
    char c = SerialMonitorInterface.read();
    if (c == 'p') { SerialMonitorInterface.println("CMD p"); reactPerk(); }
    if (c == 'h') { SerialMonitorInterface.println("CMD h"); reactHappy(); }
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
  // Re-arm only after 1.5 s of real quiet (NEW)
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
    armed = false;                         // NEW: must hear quiet before the next trigger
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
  lastLux = lux;                        // add this line

    if (millis() - lastMoodSend > 5000) {
    lastMoodSend = millis();
    SerialMonitorInterface.print("MOOD:");
    SerialMonitorInterface.println((int)mood);
    bleSend("S:" + String((int)mood) + "\n");
  }
}