// AuraPlant core: mic + light + talk detection, sent to phone over BLE.
// BLE parts based on TinyCircuits ST BLE TinyShield UART example by Ben Rose.
// Light code based on TinyCircuits TSL2572 Wireling tutorial by Laverena Wienclaw.
// Mic code based on our own mic test sketch.

#include <SPI.h>
#include <STBLE.h>       // BLE library (UART.ino needs this)
#include <Wire.h>
#include <Wireling.h>
#include <TSL2572.h>

#ifndef BLE_DEBUG
#define BLE_DEBUG true
#endif

#define SerialMonitorInterface SerialUSB

// ---- BLE variables (UART.ino fills these in) ----
uint8_t ble_rx_buffer[21];
uint8_t ble_rx_buffer_len = 0;
uint8_t ble_connection_state = false;
#define PIPE_UART_OVER_BTLE_UART_TX_TX 0

// ---- Mic ----
#define MIC_PIN A1        // mic Wireling in adapter port 1 (check against TinyCircuits mic page)
#define MIC_PORT 1

// ---- Settings you can tune ----
const float TALK_MARGIN = 0.8;               // how far ABOVE silence counts as talking
float loudSmooth = 0;             // smoothed loudness (add near your other variables)
const unsigned long LIGHT_INTERVAL = 5000;   // read light every 5 s
const bool DEBUG_LOUDNESS = true;
const unsigned long TALK_COOLDOWN = 4000;   // ignore new talking for 4 s after a trigger
unsigned long lastTalkTime = 0;
const float MAX_GOOD_BASELINE = 4.0;   // above this, the mic is considered unsettled

TSL2572 light_sensor;
float baseline = 0;
float talkThreshold = 6.0;   // replaced by calibrate()
float talkScore = 0;
unsigned long lastLightRead = 0;

void setup() {
  SerialMonitorInterface.begin(115200);
  while (!SerialMonitorInterface && millis() < 3000);

  analogReadResolution(12);

  Wire.begin();
  Wireling.begin();
  Wireling.selectPort(MIC_PORT);   // power the port the MIC is in
  light_sensor.init(GAIN_16X);     // light sensor is on the main bus (0x39)

  BLEsetup();                      // from UART.ino
  // Warm-up: read the mic for 20 s and throw the readings away
  SerialMonitorInterface.println("Warming up mic (20 s), stay quiet...");
  unsigned long warmStart = millis();
  while (millis() - warmStart < 20000) readLoudness();

  // Calibrate, and retry up to 5 times if the result is still noisy
  for (int attempt = 0; attempt < 5; attempt++) {
    calibrate();
    if (baseline < MAX_GOOD_BASELINE) break;
    SerialMonitorInterface.println("Baseline too high, retrying...");
  }}

void bleSend(String msg) {
  if (msg.length() > 19) msg = msg.substring(0, 19);
  if (!lib_aci_send_data(PIPE_UART_OVER_BTLE_UART_TX_TX,
                         (uint8_t*)msg.c_str(), msg.length())) {
    SerialMonitorInterface.println(F("TX dropped!"));
  }
}

float readLoudness() {
  const int N = 200;
  int samples[N];
  long sum = 0;
  for (int i = 0; i < N; i++) {
    samples[i] = analogRead(MIC_PIN);
    sum += samples[i];
    delayMicroseconds(500);
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
  const int WINDOWS = 30;
  float v[WINDOWS];
  for (int i = 0; i < WINDOWS; i++) v[i] = readLoudness();

  // Sort smallest -> largest (insertion sort)
  for (int i = 1; i < WINDOWS; i++) {
    float key = v[i];
    int j = i - 1;
    while (j >= 0 && v[j] > key) { v[j + 1] = v[j]; j--; }
    v[j + 1] = key;
  }

  // Average only the quietest half
  float total = 0;
  for (int i = 0; i < WINDOWS / 2; i++) total += v[i];
  baseline = total / (WINDOWS / 2);
  talkThreshold = baseline + TALK_MARGIN;

  SerialMonitorInterface.print("Baseline: ");
  SerialMonitorInterface.print(baseline);
  SerialMonitorInterface.print("  Threshold: ");
  SerialMonitorInterface.println(talkThreshold);

  if (baseline > 6.0) {
    SerialMonitorInterface.println("WARNING: mic is noisy. Check the connector and press reset.");
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
}

void loop() {
  float loud = readLoudness();
  aci_loop();
  handleIncoming();

  // Smooth over a few windows so single spikes and gaps matter less
  loudSmooth = 0.6f * loudSmooth + 0.4f * loud;

  // Baseline only drifts DOWN (toward quiet), never up while sound is present
  if (loudSmooth < baseline) baseline = 0.9f * baseline + 0.1f * loudSmooth;
  talkThreshold = baseline + TALK_MARGIN;

  if (DEBUG_LOUDNESS) {
    SerialMonitorInterface.print("M:");
    SerialMonitorInterface.print(loud);
    SerialMonitorInterface.print("  smooth:");
    SerialMonitorInterface.print(loudSmooth);
    SerialMonitorInterface.print("  thr:");
    SerialMonitorInterface.println(talkThreshold);
  }

  if (loudSmooth > talkThreshold) talkScore += 1.0;
  else talkScore = max(0.0f, talkScore - 0.25f);   // decays slower than before

  bool micOk = baseline < 6.0;
  if (micOk && talkScore >= 3.0 && millis() - lastTalkTime > TALK_COOLDOWN) {    
    SerialMonitorInterface.println("E:TALK");
    bleSend("E:TALK\n");
    lastTalkTime = millis();
    talkScore = 0;
  }

  if (millis() - lastLightRead > LIGHT_INTERVAL) {
    lastLightRead = millis();
    float lux = light_sensor.readAmbientLight();
    SerialMonitorInterface.print("L:");
    SerialMonitorInterface.println(lux);
    bleSend("L:" + String(lux, 1) + "\n");
  }
}