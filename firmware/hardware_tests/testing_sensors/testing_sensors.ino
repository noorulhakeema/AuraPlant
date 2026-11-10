#include <Wire.h>
#include <Wireling.h>

void scanBus() {
  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      SerialUSB.print("  found 0x");
      SerialUSB.println(addr, HEX);
    }
  }
}

void setup() {
  SerialUSB.begin(115200);
  while (!SerialUSB && millis() < 5000);
  Wire.begin();
  Wireling.begin();

  SerialUSB.println("Main bus:");
  scanBus();

  for (uint8_t port = 0; port < 4; port++) {
    Wireling.selectPort(port);
    delay(10);
    SerialUSB.print("Port ");
    SerialUSB.println(port);
    scanBus();
  }
}

void loop() {}