/*************************************************************************
 * TSL2572 Ambient Light Sensor Wireling Tutorial:
 * This program will print the lux value read from the sensor to both the 
 * TinyScreen+ if used, and the Serial Monitor
 * 
 * Hardware by: TinyCircuits
 * Code by: Laverena Wienclaw for TinyCircuits
 *
 * Initiated: Mon. 11/29/2017 
 * Updated: Thurs. 12/31/2020
 ************************************************************************/

#include <Wire.h>         // For I2C communication with sensor
#include <TinyScreen.h>   // For interfacing with the TinyScreen+
#include <Wireling.h>     // For interfacing with Wirelings
#include <TSL2572.h>      // For TSL2572 ambient light sensor

// TinyScreen+ variables
TinyScreen display = TinyScreen(TinyScreenPlus);
int background = TS_8b_Black;

TSL2572 light_sensor;

// Make Serial Monitor compatible for all TinyCircuits processors
#if defined(ARDUINO_ARCH_AVR)
  #define SerialMonitorInterface Serial
#elif defined(ARDUINO_ARCH_SAMD)
  #define SerialMonitorInterface SerialUSB
#endif

void setup() {
  SerialMonitorInterface.begin(115200);
  Wire.begin();

  // Initialize Wireling
  Wireling.begin();
  Wireling.selectPort(0); // Port #'s correspond to backside of the Adapter TinyShield

  // Setup and style for TinyScreen+
  display.begin();
  display.setFlip(true);
  display.setBrightness(15);
  display.setFont(thinPixel7_10ptFontInfo);
  display.fontColor(TS_8b_White, background);

//***************************************
// SETTINGS & ADJUSTMENTS 
//***************************************
//  light_sensor.init(GAIN_1X);
//  light_sensor.init(GAIN_8X);
  light_sensor.init(GAIN_16X);
//  light_sensor.init(GAIN_120X);
}

void loop() {
  SerialMonitorInterface.begin(9600);
  float AmbientLightLux = light_sensor.readAmbientLight();

  // Print lux value to Serial Monitor
  SerialMonitorInterface.print("Lux: ");
  SerialMonitorInterface.println(AmbientLightLux);

  // This will make the screen look a little unsteady but is needed in order
  // to clear old values 
  display.clearScreen();
  printScreen(AmbientLightLux); // Print lux to TinyScreen
  delay(500);
}

// Prints the lux values to the TinyScreen
void printScreen(float luxValue){
  // This will make the screen look a little unsteady but is needed in order
  // to clear old values 
  display.clearScreen();
  
  display.fontColor(TS_8b_White, background);
  display.setCursor(0, 0);
  display.print("TSL2572 Values:");

  display.fontColor(TS_8b_Yellow, background);
  display.setCursor(0, 12);
  display.print("Lux = ");
  display.print(luxValue);
}
