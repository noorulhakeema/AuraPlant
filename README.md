**AuraPlant**

AuraPlant is an interactive, 3D-printed smart desk plant built on the TinyCircuits TinyScreen+ platform. A servo-driven stem raises and lowers the plant's leaves while a OLED display renders animated facial expressions. The device senses ambient light, pats, shakes, falls, and orientation, and communicates over Bluetooth Low Energy (BLE) with a companion Android app that processes spoken affirmations with Google Gemini and logs daily care metrics.

**What It Does**

*Say something kind (via the app)*: The servo rises quickly to the top, holds for 8 seconds with an excited/happy face, and boosts vitality by 20 points.

*Press Water (via the app)*: The leaves lift to the top for 5 seconds while a water-fill animation plays, adding 30 vitality before gliding back down.

*Tap or pat it*: Gives a brief wiggle, shows an excited/happy face for 5 seconds, resets the care timer, and notifies the app.

*Shake it*: Loses 5 vitality, wiggles with a dizzy face for 3 seconds, and sends a shake alert to the app.

*Drop it or tip it over*: Dropping triggers a 3-second dizzy reaction; laying it on its side drops the leaves, subtracts 10 vitality, holds the dizzy face, and alerts the app until set upright.

*Leave it in the dark or ignore it*: Vitality slowly decays over time (fastest in the dark), causing the leaves to droop and the face to turn sad or ask for light; after 10 minutes without interaction, it falls asleep.

**Features**

*Device (Firmware)*

Animated Face: OLED display showing mood states (happy, content, neutral, sad, dizzy, needs-light) and event animations (water, sunlight, talking, excited, wink, wiggle, sleep).
Actuated Leaves: Servo-controlled stem height that responds to vitality levels and care events.
Environmental & Gesture Sensing: Tracks ambient light (TSL2572) and detects pats, shakes, free-falls, and tipping (BMA250).
Live Serial Tuning: Adjust servo travel limits, timings, and sensor thresholds over USB Serial without reflashing.

*Android App*

BLE Connectivity: Connects to the BlueNRG peripheral to send care actions and receive live sensor telemetry.
Voice Affirmations: Records spoken phrases, transcribes them on-device, and uses Google Gemini to evaluate sentiment.
Care Tracking: Includes an in-app watering control, ambient light monitor, pat/shake alerts, mood calendar, and local persistence via Room.

**Hardware**

Controller & Display: TinyScreen+ (ASM2022)
BLE: ST BLE TinyShield (ASD2116-R)
Accelerometer: Accelerometer TinyShield (ASD2511-R-A)
Servo Control: Servo Controller TinyShield (ASD2303) + ES9251 Servo (Channel 2)
Light Sensor: Ambient Light Wireling (TSL2572, I²C 0x39)
Power: 150 mAh Li-Po Battery (ASR0003)
Enclosure: Custom 3D-printed parts (cad/*.stl)

**Repository Structure**

AuraPlant/
├── andriod app/          # Kotlin & Jetpack Compose Android application
├── cad/                  # 3D-printable STL files for the pot, stem, leaves, and brackets
├── docs/                 # BLE UUID reference and project documentation
├── firmware/
│   ├── aura_core/        # Main Arduino firmware (aura_core.ino, UART.ino, display headers)
│   └── hardware_tests/   # Individual sensor and actuator test sketches
├── index.html            # Web Bluetooth test page for desktop browser testing
└── README.md


**Getting Started**

*1. Firmware*

Install the TinyCircuits board package and required libraries (TinyScreen, Wireling, STBLE, ServoDriver, BMA250, and TSL2572) in the Arduino IDE.

Assemble the TinyShield stack and connect the ES9251 servo to Channel 2.

Open firmware/aura_core/aura_core.ino (keeping AuraApprovedDisplay.h, AuraFrames.h, and UART.ino in the same folder).

Select TinyScreen+, upload the sketch, and keep the plant upright and stationary during boot so the accelerometer calibrates its upright reference.

*2 Android App*

Open the andriod app folder in Android Studio.

Add your Gemini API key to local.properties in the Android project root:

GEMINI_API_KEY=your_key_here

Build and run on a physical Android device, grant Bluetooth, microphone, and notification permissions, and tap Connect.

**Youtube Link**

**Team** - P2K

Noorul — Hardware & firmware, video directing, github set up
Hafsah — CAD design, filming, prototyping
Allye — Database development, app integration, video editing
Nicole — Mobile app UI design, app integration
Mahima — TinyScreen UI design, scriptwriting
Priscilla — Voice-over, database development