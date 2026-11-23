#include <Wire.h>
#include <SPI.h>
#include <TinyScreen.h>
#include <math.h>

TinyScreen display = TinyScreen(TinyScreenPlus);
#define SerialMonitorInterface SerialUSB

// 96x64 8-bit BGR233 framebuffer (TinyScreen native 8-bit color format)
uint8_t fb[96 * 64];

// Convert standard 8-bit R, G, B (0-255) to TinyScreen 8-bit BGR233
inline uint8_t rgb(uint8_t r, uint8_t g, uint8_t b) {
  return ((b & 0xC0)) | ((g & 0xE0) >> 3) | ((r & 0xE0) >> 5);
}

// Blend RGB color additively onto an existing BGR233 pixel (for glowing sun rays)
inline uint8_t blendAdd(uint8_t base, uint8_t addR, uint8_t addG, uint8_t addB) {
  int r = ((base & 0x07) << 5) + addR;
  int g = ((base & 0x38) << 2) + addG;
  int b = (base & 0xC0) + addB;
  if (r > 255) r = 255;
  if (g > 255) g = 255;
  if (b > 255) b = 255;
  return rgb((uint8_t)r, (uint8_t)g, (uint8_t)b);
}

// Palette matching your design sheet
uint8_t C_BLACK, C_WHITE, C_GREEN, C_GREEN_DK, C_PINK, C_PINK_DK;
uint8_t C_YELLOW, C_GOLD, C_CYAN, C_BLUE, C_RED, C_GRAY;

void initPalette() {
  C_BLACK    = rgb(0, 0, 0);
  C_WHITE    = rgb(255, 255, 255);
  C_GREEN    = rgb(40, 220, 70);
  C_GREEN_DK = rgb(20, 140, 40);
  C_PINK     = rgb(255, 110, 140);
  C_PINK_DK  = rgb(200, 60, 90);
  C_YELLOW   = rgb(255, 230, 40);
  C_GOLD     = rgb(255, 170, 0);
  C_CYAN     = rgb(60, 210, 255);
  C_BLUE     = rgb(30, 130, 255);
  C_RED      = rgb(255, 60, 60);
  C_GRAY     = rgb(110, 110, 110);
}

// ================= FRAMEBUFFER PRIMITIVES =================
void fbClear(uint8_t col = 0) {
  memset(fb, col, sizeof(fb));
}

inline void fbPixel(int x, int y, uint8_t col) {
  if ((unsigned)x < 96 && (unsigned)y < 64) fb[y * 96 + x] = col;
}

void fbRect(int x, int y, int w, int h, uint8_t col) {
  for (int dy = 0; dy < h; dy++)
    for (int dx = 0; dx < w; dx++)
      fbPixel(x + dx, y + dy, col);
}

void fbLine(int x0, int y0, int x1, int y1, uint8_t col) {
  int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
  int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
  int err = dx + dy;
  while (true) {
    fbPixel(x0, y0, col);
    if (x0 == x1 && y0 == y1) break;
    int e2 = 2 * err;
    if (e2 >= dy) { err += dy; x0 += sx; }
    if (e2 <= dx) { err += dx; y0 += sy; }
  }
}

void fbCircle(int cx, int cy, int r, uint8_t col) {
  for (int y = -r; y <= r; y++)
    for (int x = -r; x <= r; x++)
      if (x * x + y * y <= r * r) fbPixel(cx + x, cy + y, col);
}

void fbFlush() {
  display.goTo(0, 0);
  display.startData();
  display.writeBuffer(fb, sizeof(fb));
  display.endTransfer();
}

// ================= SPRITE & FEATURE HELPERS =================

// Top green sprout leaves (with optional horizontal wiggle & motion arcs)
void drawLeaves(int offsetX, bool wiggleArcs = false) {
  int cx = 48 + offsetX;
  // Stem
  fbRect(cx - 1, 8, 3, 5, C_GREEN_DK);
  // Left leaf
  fbRect(cx - 14, 4, 12, 4, C_GREEN);
  fbRect(cx - 12, 2, 8, 7, C_GREEN);
  fbPixel(cx - 14, 4, C_BLACK); // rounded tip
  // Right leaf
  fbRect(cx + 2, 4, 12, 4, C_GREEN);
  fbRect(cx + 4, 2, 8, 7, C_GREEN);
  fbPixel(cx + 13, 4, C_BLACK);

  if (wiggleArcs) {
    fbLine(cx - 19, 3, cx - 21, 6, C_WHITE);
    fbLine(cx - 21, 6, cx - 19, 9, C_WHITE);
    fbLine(cx + 19, 3, cx + 21, 6, C_WHITE);
    fbLine(cx + 21, 6, cx + 19, 9, C_WHITE);
  }
}

void drawCheeks() {
  fbRect(14, 38, 7, 3, C_PINK);
  fbRect(75, 38, 7, 3, C_PINK);
}

// Eye styles:
// 0=normal(with lookX/Y), 1=happy arch (^ ^), 2=excited (> <), 3=wink (> and normal),
// 4=flat (- -), 5=droopy/thirsty, 6=sad with tears, 7=dizzy (X X)
void drawEyes(int style, int lookX = 0, int lookY = 0) {
  int lx = 26, rx = 62, ey = 22;

  if (style == 0 || style == 3) {
    // Left eye: '>' if winking, else normal eye
    if (style == 3) {
      fbLine(lx, ey + 2, lx + 8, ey + 6, C_WHITE);
      fbLine(lx + 8, ey + 6, lx, ey + 10, C_WHITE);
      fbLine(lx, ey + 3, lx + 7, ey + 6, C_WHITE);
      fbLine(lx + 7, ey + 6, lx, ey + 9, C_WHITE);
    } else {
      fbRect(lx, ey, 9, 13, C_WHITE);
      fbRect(lx + 2 + lookX, ey + 3 + lookY, 3, 4, C_BLACK); // pupil notch
    }
    // Right eye: normal
    fbRect(rx, ey, 9, 13, C_WHITE);
    fbRect(rx + 2 + lookX, ey + 3 + lookY, 3, 4, C_BLACK);
  }
  else if (style == 1) { // Happy arch (∩ ∩)
    for (int i = 0; i < 2; i++) {
      int bx = (i == 0) ? lx : rx;
      fbLine(bx, ey + 8, bx + 3, ey + 4, C_WHITE);
      fbLine(bx + 3, ey + 4, bx + 6, ey + 4, C_WHITE);
      fbLine(bx + 6, ey + 4, bx + 9, ey + 8, C_WHITE);
      fbLine(bx + 1, ey + 8, bx + 3, ey + 5, C_WHITE);
      fbLine(bx + 6, ey + 5, bx + 8, ey + 8, C_WHITE);
    }
  }
  else if (style == 2) { // Excited (> <)
    fbLine(lx, ey + 2, lx + 8, ey + 6, C_WHITE);
    fbLine(lx + 8, ey + 6, lx, ey + 10, C_WHITE);
    fbLine(lx, ey + 3, lx + 7, ey + 6, C_WHITE);
    fbLine(lx + 7, ey + 6, lx, ey + 9, C_WHITE);

    fbLine(rx + 8, ey + 2, rx, ey + 6, C_WHITE);
    fbLine(rx, ey + 6, rx + 8, ey + 10, C_WHITE);
    fbLine(rx + 8, ey + 3, rx + 1, ey + 6, C_WHITE);
    fbLine(rx + 1, ey + 6, rx + 8, ey + 9, C_WHITE);
  }
  else if (style == 4) { // Neutral flat (- -)
    fbRect(lx, ey + 5, 10, 3, C_WHITE);
    fbRect(rx, ey + 5, 10, 3, C_WHITE);
  }
  else if (style == 5) { // Droopy / Thirsty / Need Light
    fbRect(lx, ey + 4, 10, 3, C_WHITE);
    fbRect(lx + 2, ey + 7, 6, 3, C_WHITE);
    fbRect(rx, ey + 4, 10, 3, C_WHITE);
    fbRect(rx + 2, ey + 7, 6, 3, C_WHITE);
  }
  else if (style == 6) { // Sad + tears
    fbLine(lx, ey + 6, lx + 9, ey + 3, C_WHITE);
    fbLine(lx, ey + 7, lx + 9, ey + 4, C_WHITE);
    fbLine(rx, ey + 3, rx + 9, ey + 6, C_WHITE);
    fbLine(rx, ey + 4, rx + 9, ey + 7, C_WHITE);
    // Tear drops
    fbRect(lx + 2, ey + 11, 2, 4, C_CYAN);
    fbRect(rx + 6, ey + 11, 2, 4, C_CYAN);
  }
}

// Mouth styles: 0=smile, 1=open happy tongue, 2=flat, 3=frown, 4=small talk O
void drawMouth(int style) {
  int mx = 42, my = 42;
  if (style == 0) { // Cute small smile
    fbLine(mx, my, mx + 3, my + 3, C_WHITE);
    fbLine(mx + 3, my + 3, mx + 9, my + 3, C_WHITE);
    fbLine(mx + 9, my + 3, mx + 12, my, C_WHITE);
  }
  else if (style == 1) { // Open happy mouth with pink tongue
    fbRect(mx, my - 1, 13, 3, C_WHITE);
    fbRect(mx + 1, my + 2, 11, 5, C_PINK);
    fbRect(mx + 3, my + 7, 7, 2, C_PINK_DK);
  }
  else if (style == 2) { // Neutral flat
    fbRect(mx + 1, my + 2, 11, 2, C_WHITE);
  }
  else if (style == 3) { // Sad / Thirsty frown
    fbLine(mx + 1, my + 4, mx + 4, my + 1, C_WHITE);
    fbLine(mx + 4, my + 1, mx + 8, my + 1, C_WHITE);
    fbLine(mx + 8, my + 1, mx + 11, my + 4, C_WHITE);
  }
  else if (style == 4) { // Talking open circle
    fbRect(mx + 3, my, 7, 6, C_PINK);
    fbLine(mx + 3, my, mx + 9, my, C_WHITE);
  }
}

// ================= OVERLAYS & ICONS =================

void drawSparkle(int cx, int cy, int r) {
  fbLine(cx - r, cy, cx + r, cy, C_YELLOW);
  fbLine(cx, cy - r, cx, cy + r, C_YELLOW);
  fbPixel(cx, cy, C_WHITE);
}

void drawWaterDropIcon(int x, int y) {
  fbPixel(x + 3, y, C_CYAN);
  fbRect(x + 2, y + 1, 3, 2, C_CYAN);
  fbRect(x + 1, y + 3, 5, 3, C_CYAN);
  fbRect(x + 1, y + 6, 5, 2, C_BLUE);
  fbPixel(x + 2, y + 4, C_WHITE); // highlight
}

void drawLightbulbIcon(int x, int y) {
  fbCircle(x + 4, y + 4, 4, C_YELLOW);
  fbRect(x + 2, y + 8, 5, 2, C_GRAY);
  fbRect(x + 3, y + 10, 3, 1, C_WHITE);
  // Glow rays
  fbPixel(x - 1, y + 4, C_GOLD);
  fbPixel(x + 9, y + 4, C_GOLD);
  fbPixel(x + 4, y - 1, C_GOLD);
}

void drawQuestionMark(int x, int y) {
  fbRect(x + 1, y, 4, 2, C_YELLOW);
  fbRect(x + 4, y + 2, 2, 2, C_YELLOW);
  fbRect(x + 2, y + 4, 3, 2, C_YELLOW);
  fbRect(x + 2, y + 8, 2, 2, C_YELLOW);
}

void drawSoundWaves() {
  int phase = (millis() / 150) % 2;
  int len = phase ? 6 : 4;
  fbLine(6, 26, 6 + len, 24, C_YELLOW);
  fbLine(6, 32, 6 + len, 32, C_YELLOW);
  fbLine(6, 38, 6 + len, 40, C_YELLOW);

  fbLine(89, 26, 89 - len, 24, C_YELLOW);
  fbLine(89, 32, 89 - len, 32, C_YELLOW);
  fbLine(89, 38, 89 - len, 40, C_YELLOW);
}

// Section B: Dynamic sine-wave water overlay
void drawWaterWave(int waterY) {
  float phase = millis() * 0.014f;
  for (int x = 0; x < 96; x++) {
    int wy = waterY + (int)(sinf(x * 0.22f + phase) * 2.5f);
    if (wy < 64) {
      fbPixel(x, wy, C_WHITE);
      fbPixel(x, wy + 1, C_CYAN);
      for (int y = max(0, wy + 2); y < 64; y++) {
        // Checkerboard dither lets face remain visible through the water
        if ((x + y) % 2 == 0) fbPixel(x, y, C_CYAN);
      }
    }
  }
}

// Section C: Diagonal golden sun ray beam sweeping from top-right (95, 0)
void drawSunBeam(float widthFactor, bool showSunOrb) {
  // Ray vector from (95, 0) toward bottom-left
  for (int y = 0; y < 64; y++) {
    for (int x = 0; x < 96; x++) {
      int dx = 95 - x;
      int dy = y;
      // Angle metric: 0 along top edge, 1 along right edge
      float dist = sqrtf((float)(dx * dx + dy * dy)) + 0.1f;
      float diagBias = 1.0f - fabsf((dx - dy * 1.3f) / (dist * 1.4f));
      if (diagBias > (1.0f - widthFactor)) {
        float intensity = (diagBias - (1.0f - widthFactor)) / widthFactor;
        uint8_t addR = (uint8_t)(160 * intensity);
        uint8_t addG = (uint8_t)(135 * intensity);
        fb[y * 96 + x] = blendAdd(fb[y * 96 + x], addR, addG, 0);
      }
    }
  }
  if (showSunOrb) {
    fbCircle(86, 9, 7, C_GOLD);
    fbCircle(86, 9, 4, C_YELLOW);
  }
}

// ================= DEMO STAGES (ALL 15 FROM SHEET) =================
const char* STAGE_NAMES[] = {
  "A1: Very Happy",
  "A2: Content",
  "A3: Neutral",
  "A4: Thirsty",
  "A5: Need Light",
  "A6: Sad",
  "B1: Watering - Filling",
  "B2: Watering - Full + Hold",
  "B3: Watering - Draining",
  "C1: Sun Ray - Initial",
  "C2: Sun Ray - Growing",
  "C3: Sun Ray - In the Sun!",
  "D1: Talking / Hearing",
  "D2: Wink & Look Around",
  "D3: Leaves Wiggle, Thinking & Sleep"
};
const int NUM_STAGES = 15;
int currentStage = 0;
unsigned long stageStart = 0;
const unsigned long STAGE_DURATION = 4000; // 4 seconds per stage

void renderStage(int stage, unsigned long elapsed) {
  fbClear(C_BLACK);
  float progress = constrain((float)elapsed / (float)STAGE_DURATION, 0.0f, 1.0f);

  switch (stage) {
    // --- SECTION A: CORE EXPRESSIONS ---
    case 0: { // Very Happy + twinkling stars
      drawLeaves(0);
      drawEyes(1);
      drawCheeks();
      drawMouth(1);
      int pulse = ((elapsed / 250) % 2) ? 4 : 2;
      drawSparkle(12, 16, pulse);
      drawSparkle(84, 18, 6 - pulse);
      break;
    }
    case 1: { // Content (with natural periodic blink)
      drawLeaves(0);
      bool blink = (elapsed % 2000) > 1820;
      drawEyes(blink ? 1 : 0);
      drawCheeks();
      drawMouth(0);
      break;
    }
    case 2: { // Neutral
      drawLeaves(0);
      drawEyes(4);
      drawCheeks();
      drawMouth(2);
      break;
    }
    case 3: { // Thirsty + cyan drop icon
      drawLeaves(0);
      drawEyes(5);
      drawCheeks();
      drawMouth(3);
      drawWaterDropIcon(81, 8);
      break;
    }
    case 4: { // Need Light + lightbulb icon
      drawLeaves(0);
      drawEyes(5);
      drawMouth(2);
      drawLightbulbIcon(80, 7);
      break;
    }
    case 5: { // Sad + tears
      drawLeaves(0);
      drawEyes(6);
      drawMouth(3);
      break;
    }

    // --- SECTION B: WATERING ANIMATION ---
    case 6: { // Filling... (rising dynamic waves)
      drawLeaves(0);
      drawEyes(1);
      drawCheeks();
      drawMouth(0);
      int waterY = 64 - (int)(progress * 44.0f); // rises from 64 to 20
      drawWaterWave(waterY);
      break;
    }
    case 7: { // Hold celebration (> < eyes + sparkles)
      drawLeaves(0);
      drawEyes(2);
      drawCheeks();
      drawMouth(1);
      drawSparkle(14, 15, 4);
      drawSparkle(82, 15, 4);
      drawSparkle(18, 48, 3);
      drawSparkle(78, 48, 3);
      break;
    }
    case 8: { // Draining... (wave lowers back to Content)
      drawLeaves(0);
      drawEyes(1);
      drawCheeks();
      drawMouth(0);
      int waterY = 20 + (int)(progress * 44.0f); // drains from 20 to 64
      drawWaterWave(waterY);
      break;
    }

    // --- SECTION C: SUNLIGHT ANIMATION ---
    case 9: { // Ray appears (narrow)
      drawLeaves(0);
      drawEyes(0);
      drawMouth(2);
      drawSunBeam(0.28f, false);
      break;
    }
    case 10: { // Rays grow (wider, eyes turn happy)
      drawLeaves(0);
      drawEyes(1);
      drawCheeks();
      drawMouth(0);
      drawSunBeam(0.55f, false);
      break;
    }
    case 11: { // In the sun! (full warm glow + sun orb)
      drawLeaves(0);
      drawEyes(1);
      drawCheeks();
      drawMouth(1);
      drawSunBeam(0.95f, true);
      break;
    }

    // --- SECTION D: MICRO-ANIMATIONS ---
    case 12: { // Talking / Hearing
      drawLeaves(0);
      drawEyes(0);
      drawCheeks();
      drawMouth(((elapsed / 180) % 2) ? 4 : 0);
      drawSoundWaves();
      break;
    }
    case 13: { // First 2s: Wink | Next 2s: Look Around 4 corners
      drawLeaves(0);
      if (elapsed < 2000) {
        drawEyes(3); // left eye wink '>'
      } else {
        int corner = ((elapsed - 2000) / 450) % 4;
        int lx = (corner == 1 || corner == 2) ? 2 : -1;
        int ly = (corner == 2 || corner == 3) ? 3 : -1;
        drawEyes(0, lx, ly);
      }
      drawCheeks();
      drawMouth(0);
      break;
    }
    case 14: { // 0-1.5s: Leaf Wiggle | 1.5-2.7s: Thinking (?) | 2.7-4s: Sleeping (zZ)
      if (elapsed < 1500) {
        int wig = ((elapsed / 140) % 2) ? 2 : -2;
        drawLeaves(wig, true);
        drawEyes(1);
        drawCheeks();
        drawMouth(0);
      } else if (elapsed < 2700) {
        drawLeaves(0);
        drawEyes(5);
        drawMouth(2);
        drawQuestionMark(80, 12);
      } else {
        drawLeaves(0);
        drawEyes(4);
        drawCheeks();
        drawMouth(2);
        // Floating Z's
        int drift = ((elapsed - 2700) / 200) % 5;
        fbLine(76, 16 - drift, 81, 16 - drift, C_CYAN);
        fbLine(81, 16 - drift, 76, 20 - drift, C_CYAN);
        fbLine(76, 20 - drift, 81, 20 - drift, C_CYAN);

        fbLine(84, 10 - drift, 88, 10 - drift, C_BLUE);
        fbLine(88, 10 - drift, 84, 13 - drift, C_BLUE);
        fbLine(84, 13 - drift, 88, 13 - drift, C_BLUE);
      }
      break;
    }
  }

  fbFlush();
}

void nextStage() {
  currentStage = (currentStage + 1) % NUM_STAGES;
  stageStart = millis();
  SerialMonitorInterface.print("Showing -> ");
  SerialMonitorInterface.println(STAGE_NAMES[currentStage]);
}

void setup() {
  SerialMonitorInterface.begin(115200);
  Wire.begin();
  display.begin();
  display.setFlip(false);      // match your AuraPlant mounting orientation
  display.setBrightness(12);
  initPalette();

  stageStart = millis();
  SerialMonitorInterface.println("=== AuraPlant Display Test ===");
  SerialMonitorInterface.println("Auto-cycles every 4s, or press any TinyScreen button / send Serial char to skip.");
  SerialMonitorInterface.print("Showing -> ");
  SerialMonitorInterface.println(STAGE_NAMES[0]);
}

uint8_t lastButtons = 0;

void loop() {
  unsigned long now = millis();

  // Check TinyScreen+ physical side buttons to manually skip ahead
  uint8_t btns = display.getButtons();
  if (btns && !lastButtons) {
    nextStage();
  }
  lastButtons = btns;

  // Check Serial Monitor input to skip ahead
  while (SerialMonitorInterface.available()) {
    SerialMonitorInterface.read();
    nextStage();
  }

  // Auto-advance every 4 seconds
  if (now - stageStart >= STAGE_DURATION) {
    nextStage();
  }

  // Render at ~25 FPS (40 ms frame interval)
  static unsigned long lastFrame = 0;
  if (now - lastFrame >= 40) {
    lastFrame = now;
    renderStage(currentStage, now - stageStart);
  }
}