// Aura Plant: approved SmoothOrange BGR332 frames + event-driven display.
// Include after display, lastFace and dizzyUntil are declared in aura_core.ino.
#ifndef AURA_APPROVED_ART_H
#define AURA_APPROVED_ART_H
#include <Arduino.h>
#include <string.h>
#include "AuraFrames.h"

static uint8_t auraFramebuffer[96 * 64];
static uint16_t auraLastFrameId = 65535;
static const uint16_t AURA_FRAME_COUNT = sizeof(FRAME_OFFSETS)/sizeof(FRAME_OFFSETS[0]) - 1;

// Same full-frame decoder and transfer sequence as the approved standalone test.
static void auraShowFrame(uint16_t id) {
  if (id == auraLastFrameId || id >= AURA_FRAME_COUNT) return;
  uint32_t src = FRAME_OFFSETS[id], end = FRAME_OFFSETS[id + 1];
  unsigned int dst = 0;
  while (src < end && dst < sizeof(auraFramebuffer)) {
    uint8_t tag = ART_DATA[src++];
    unsigned int count = (tag & 127) + 1;
    if (dst + count > sizeof(auraFramebuffer)) return;
    if (tag & 128) {
      if (src >= end) return;
      memset(auraFramebuffer + dst, ART_DATA[src++], count);
    } else {
      if (src + count > end) return;
      memcpy(auraFramebuffer + dst, ART_DATA + src, count);
      src += count;
    }
    dst += count;
  }
  if (dst != sizeof(auraFramebuffer) || src != end) return;
  display.goTo(0, 0);
  display.startData();
  display.writeBuffer(auraFramebuffer, sizeof(auraFramebuffer));
  display.endTransfer();
  auraLastFrameId = id;
}

// Existing core face IDs: happy, content, sad, thirsty, dizzy, need light, neutral.
// These values index the approved standalone STAGES table, not artwork face IDs.
static const uint8_t auraFaceStages[] = {2, 0, 6, 4, 7, 5, 3};
static void auraDrawLegacyFace(int face, bool blink) {
  if (face < 0 || face >= 7) return;
  const Stage &s = STAGES[auraFaceStages[face]];
  auraShowFrame(FRAME_SEQUENCE[s.first + ((blink && face != 4) ? 1 : 0)]);
}

// Scene IDs correspond to the approved stage table. No automatic demo cycling.
// Preserve the original water priority, dizzy override and 10-minute idle sleep.
enum AuraScene : uint8_t {
  AURA_IDLE = 255, AURA_WINK = 1, AURA_WATER = 8, AURA_SUN = 9,
  AURA_TALK = 10, AURA_LOOK = 11, AURA_THINK = 12, AURA_WIGGLE = 13,
  AURA_EXCITED = 14, AURA_SLEEP = 15
};
static AuraScene auraScene = AURA_IDLE;
static unsigned long auraSceneStart = 0;
static unsigned long auraLastActivity = 0;
static const unsigned long AURA_IDLE_SLEEP_MS = 600000UL;
static bool auraAnimationActive() { return auraScene != AURA_IDLE; }
static bool auraSceneIsWater() { return auraScene == AURA_WATER; }
static void auraFinishScene(unsigned long now) {
  auraScene = AURA_IDLE;
  auraLastActivity = now;
  lastFace = -1; // force the core to restore the current mood face on its next pass
}
static void auraStartScene(AuraScene scene) {
  if (auraSceneIsWater() && scene != AURA_WATER) return;
  unsigned long now = millis();
  if (now < dizzyUntil) return;
  auraScene = scene;
  auraSceneStart = now;
  auraLastActivity = now;
}
static void auraStartWater() { auraStartScene(AURA_WATER); }
static void auraStartSunlight() { auraStartScene(AURA_SUN); }
static void auraStartTalking() { auraStartScene(AURA_TALK); }
static void auraStartWink() {
  if (auraScene == AURA_IDLE && lastFace == 1) auraStartScene(AURA_WINK);
}
static void auraStartLook() { auraStartScene(AURA_LOOK); }
static void auraStartThink() { auraStartScene(AURA_THINK); }
static void auraStartWiggle() { auraStartScene(AURA_WIGGLE); }
static void auraStartExcited() { auraStartScene(AURA_EXCITED); }
static void auraStartSleeping() { auraStartScene(AURA_SLEEP); }
static void auraWakeDisplay() {
  if (!auraSceneIsWater()) auraFinishScene(millis());
}

// Runs from the existing loop; no delay, no sensor/servo/BLE changes.
static void auraTick() {
  unsigned long now = millis();
  if (now < dizzyUntil) {
    if (auraAnimationActive()) auraFinishScene(now);
    else auraLastActivity = now;
    return;
  }
  if (auraScene == AURA_IDLE) {
    if (now - auraLastActivity < AURA_IDLE_SLEEP_MS) return;
    auraStartSleeping();
  }
  const Stage &s = STAGES[static_cast<uint8_t>(auraScene)];
  unsigned long elapsed = now - auraSceneStart;
  // Sleep persists until activity, as in the original integrated firmware.
  // Water: 1800 ms fill + 10000 ms hold + 1800 ms drain.
  // Sun: 700 ms initial ray + 1100 ms growth + 1800 ms happy.
  if (elapsed >= s.duration && auraScene != AURA_SLEEP) {
    auraFinishScene(now);
    return;
  }
  unsigned long index = elapsed / s.step;
  if (index >= s.count) index = s.count - 1;
  auraShowFrame(FRAME_SEQUENCE[s.first + index]);
}
#endif
