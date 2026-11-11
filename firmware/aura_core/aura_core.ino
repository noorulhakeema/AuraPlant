// AuraPlant Core: Audio DSP + Light + Non-Blocking Servo + Phone BLE Bridge
#include <SPI.h>
#include <STBLE.h>
#include <Wire.h>
#include <Wireling.h>
#include <TSL2572.h>
#include <ServoDriver.h>

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
const bool DEBUG_LOUDNESS = true;          // set false for a quiet Serial Monitor
const unsigned long TALK_COOLDOWN = 3500;
unsigned long lastTalkTime = 0;
const float MAX_GOOD_BASELINE = 5.0f;

TSL2572 light_sensor;
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
const int SERVO_US_PER_STEP = 25;          // was 15, faster so the wiggle is visible

int servoPos    = SERVO_REST;
int servoTarget = SERVO_REST;
bool servoOk = false;
unsigned long lastServoStep = 0;
unsigned long lastServoMove = 0;
unsigned long lastServoCmd  = 0;
unsigned long servoRestAt   = 0;
unsigned long wiggleUntil   = 0;           // NEW

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
void reactPerk() {                         // small nudge: "I heard you"
  servoGoTo(SERVO_PERK);
  servoRestAt = millis() + 2500;
}

void reactHappy() {                        // wiggle: "that was kind!"
  servoGoTo(SERVO_HAPPY);
  servoRestAt = millis() + 3500;
  wiggleUntil = millis() + 2500;
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
void setup() {
  SerialMonitorInterface.begin(115200);
  while (!SerialMonitorInterface && millis() < 3000);

  analogReadResolution(12);

  Wire.begin();
  Wireling.begin();
  Wireling.selectPort(MIC_PORT);
  light_sensor.init(GAIN_16X);

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
  else if (cmd == "R:PERK")             reactPerk();
}

void loop() {
  servoUpdate();

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
  bool plantBusy  = (servoTarget != SERVO_REST) || (millis() - lastServoMove < 1500);

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

  // Return to rest after the reaction ends
  if (servoTarget != SERVO_REST && millis() > servoRestAt && millis() >= wiggleUntil) {
    servoGoTo(SERVO_REST);
  }

  if (millis() - lastLightRead > LIGHT_INTERVAL) {
    lastLightRead = millis();
    float lux = light_sensor.readAmbientLight();
    SerialMonitorInterface.print("L:");
    SerialMonitorInterface.println(lux);
    bleSend("L:" + String(lux, 1) + "\n");
  }
}