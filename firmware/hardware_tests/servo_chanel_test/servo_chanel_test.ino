// Servo channel finder: sweeps channel 1, then 2, 3, 4 and prints each number.
// Based on the TinyCircuits Servo Controller Basic Example by Ben Rose.

#include <Wire.h>
#include <ServoDriver.h>

ServoDriver servo(NO_R_REMOVED);

#define SerialMonitorInterface SerialUSB

void setup() {
  SerialMonitorInterface.begin(115200);
  Wire.begin();
  while (!SerialMonitorInterface && millis() < 5000);   // note the semicolon

  servo.useResetPin();
  if (servo.begin(20000)) {
    SerialMonitorInterface.println("Motor driver not detected!");
    while (1);
  }
  servo.setFailsafe(1000);
}

void sweep(int channel) {
  for (int pos = 1000; pos <= 2000; pos += 30) {
    servo.setServo(channel, pos);
    delay(20);
  }
  for (int pos = 2000; pos >= 1000; pos -= 30) {
    servo.setServo(channel, pos);
    delay(20);
  }
}

void loop() {
  for (int ch = 1; ch <= 4; ch++) {
    SerialMonitorInterface.print("Sweeping channel ");
    SerialMonitorInterface.println(ch);
    sweep(ch);
    delay(500);
  }
}