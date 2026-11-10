// AuraPlant core: mic loudness + light + talk detection

#include <Wire.h>        // I2C communication (used by the light sensor)
#include <Wireling.h>    // Controls the Wireling adapter ports
#include <TSL2572.h>     // Ambient light sensor library

#define MIC_PIN A0       // from your working mic test
#define LIGHT_PORT 0     // CHANGE to the adapter port the light sensor is plugged into

// ---- Settings you can tune ----
const float TALK_THRESHOLD = 5.0;            // we'll tune this in the test below
const int   LOUD_WINDOWS_NEEDED = 3;         // 3 loud windows in a row = "talking"
const unsigned long LIGHT_INTERVAL = 5000;   // read light every 5 seconds
const bool  DEBUG_LOUDNESS = true;           // prints every window's loudness

TSL2572 light_sensor;
int loudCount = 0;
unsigned long lastLightRead = 0;

void setup() {
  SerialUSB.begin(115200);
  while (!SerialUSB && millis() < 5000);   // wait up to 5 s for Serial Monitor

  analogReadResolution(12);                // mic: read values 0-4095 (from your test)

  Wire.begin();
  Wireling.begin();                        // powers up the Wireling adapter
  Wireling.selectPort(LIGHT_PORT);         // choose the port with the light sensor
  light_sensor.init(GAIN_16X);             // same gain as the tutorial
}

// One 100 ms window -> one loudness number (RMS)
float readLoudness() {
  const int N = 200;
  int samples[N];
  long sum = 0;

  for (int i = 0; i < N; i++) {
    samples[i] = analogRead(MIC_PIN);
    sum += samples[i];
    delayMicroseconds(500);
  }

  float mean = sum / (float)N;             // the "resting" level of the mic
  float sq = 0;
  for (int i = 0; i < N; i++) {
    float d = samples[i] - mean;           // distance from resting level
    sq += d * d;
  }
  return sqrt(sq / N);                     // RMS = how far the signal swings
}

void loop() {
  float loud = readLoudness();

  if (DEBUG_LOUDNESS) {
    SerialUSB.print("M:");
    SerialUSB.println(loud);
  }

  if (loud > TALK_THRESHOLD) loudCount++;
  else loudCount = 0;

  if (loudCount >= LOUD_WINDOWS_NEEDED) {
    SerialUSB.println("E:TALK");
    loudCount = 0;
  }

  if (millis() - lastLightRead > LIGHT_INTERVAL) {
    lastLightRead = millis();
    float lux = light_sensor.readAmbientLight();
    SerialUSB.print("L:");
    SerialUSB.println(lux);
  }
}