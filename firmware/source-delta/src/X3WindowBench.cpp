#ifndef X3_WINDOW_BENCH
// No‑op when the bench flag is absent — zero cost, no header drags.
// The foreman must add   -DX3_WINDOW_BENCH   to the build_flags of the
// X3 environment in platformio.ini and insert the following into setup()
// after setupDisplayAndFonts() (typically in main.cpp):
//
//   #ifdef X3_WINDOW_BENCH
//   x3WindowBenchRun();
//   #endif
//
// This file obtains the FreeInkDisplay through the global `display` object’s
// benchRawDisplay() accessor — that accessor is guarded by the same flag and
// is the only legitimate way for bench code to reach the facade.
extern "C" void x3WindowBenchRun() {}

#else  // X3_WINDOW_BENCH

#include <Arduino.h>
#include <HalDisplay.h>
#include <FreeInkDisplay.h>

using freeink::FreeInkDisplay;

namespace {

// Write a single monochrome pixel into a 1bpp framebuffer row.
// black=true -> 0 (ink on), black=false -> 1 (white).
// The MSB of each byte is the leftmost pixel in that byte (bit 7).
inline void setPixel(uint8_t* fb, uint16_t wBytes, uint16_t x, uint16_t y, bool black) {
  uint16_t byteIdx = static_cast<uint16_t>(y) * wBytes + static_cast<uint16_t>(x / 8);
  uint8_t bit = static_cast<uint8_t>(7 - (x & 7));
  if (black) {
    fb[byteIdx] &= static_cast<uint8_t>(~(1u << bit));
  } else {
    fb[byteIdx] |= static_cast<uint8_t>(1u << bit);
  }
}

// Draw a vertically‑asymmetric pattern into a windowed rect, ensuring that
// **every** pixel inside the driver’s outward‑rounded window is fully defined.
//   ─ first, the entire rounded rectangle is filled white.
//   ─ then, a 2‑px black edge line is drawn at the very top of the source rect.
//   ─ finally, a solid black band fills the top ~25 % of the source rect.
// On the glass the band MUST appear at the TOP — if it appears at the bottom
// the driver’s row order is mirrored (R13 violation).
void drawAsymmetricBand(FreeInkDisplay& d, uint16_t rx, uint16_t ry, uint16_t rw, uint16_t rh) {
  uint8_t* fb = d.getFrameBuffer();
  if (!fb) return;

  // Clamp to panel (matching the driver’s own safety clamp).
  const uint16_t panW = d.getDisplayWidth();
  const uint16_t panH = d.getDisplayHeight();
  if (rx >= panW || ry >= panH) return;
  if (rx + rw > panW) rw = static_cast<uint16_t>(panW - rx);
  if (ry + rh > panH) rh = static_cast<uint16_t>(panH - ry);
  if (rw == 0 || rh == 0) return;

  const uint16_t wBytes = d.getDisplayWidthBytes();

  // Replicate the driver’s outward byte‑rounding (displayWindow line 152‑157
  // in Uc8253X3DisplayWindow.cpp). This guarantees the harness covers the
  // exact column range that will be refreshed.
  const uint16_t xEndLogical = static_cast<uint16_t>(rx + rw - 1);
  const uint16_t xs = static_cast<uint16_t>(rx & ~7u);
  const uint16_t xe = static_cast<uint16_t>((xEndLogical | 7u) < (panW - 1) ? (xEndLogical | 7u) : (panW - 1));
  const uint16_t roundedW = static_cast<uint16_t>(xe - xs + 1);

  // 1. Paint the ENTIRE rounded rectangle white.
  for (uint16_t row = 0; row < rh; row++) {
    for (uint16_t col = 0; col < roundedW; col++) {
      setPixel(fb, wBytes, static_cast<uint16_t>(xs + col), static_cast<uint16_t>(ry + row), false);
    }
  }

  // 2. Lay down the asymmetric black pattern inside the original (possibly unaligned) rect.
  const uint16_t bandRows = (rh > 4) ? static_cast<uint16_t>(rh / 4) : rh;
  for (uint16_t row = 0; row < rh; row++) {
    const bool black = (row < 2) || (row < bandRows);
    if (black) {
      for (uint16_t col = 0; col < rw; col++) {
        setPixel(fb, wBytes, static_cast<uint16_t>(rx + col), static_cast<uint16_t>(ry + row), true);
      }
    }
  }
}

// Fill the entire framebuffer with a 16×16‑tile checkerboard.
void fillFullScreenPattern(FreeInkDisplay& d) {
  uint8_t* fb = d.getFrameBuffer();
  if (!fb) return;
  const uint16_t w = d.getDisplayWidth();
  const uint16_t h = d.getDisplayHeight();
  const uint16_t wBytes = d.getDisplayWidthBytes();
  for (uint16_t y = 0; y < h; y++) {
    for (uint16_t x = 0; x < w; x++) {
      const bool black = ((x / 16) + (y / 16)) % 2 == 0;
      setPixel(fb, wBytes, x, y, black);
    }
  }
}

// Print a [BENCH] expectation line for one windowed step.
void printExpectTop(uint16_t rx, uint16_t ry, uint16_t rw, uint16_t rh, const __FlashStringHelper* note) {
  Serial.print(F("[BENCH] expect: solid band at TOP of rect=("));
  Serial.print(rx); Serial.print(','); Serial.print(ry);
  Serial.print(F(") w=")); Serial.print(rw); Serial.print('x'); Serial.print(rh);
  Serial.print(F("  ("));
  Serial.print(note);
  Serial.println(F(")"));
}

}  // namespace

// ---------------------------------------------------------------------------
// X3 window‑refresh bench harness.
// Call x3WindowBenchRun() once after display initialisation.
// Requires   -DX3_WINDOW_BENCH   in build_flags and the global `display`
// object’s benchRawDisplay() accessor (already guarded by the same flag).
// The harness draws known patterns, times a full‑panel FAST reference, then
// exercises four windowed rectangles. It prints [BENCH] lines while the
// driver emits the authoritative [X3] displayWindow: lines.
// Runs in a single pass; no latched state between calls.
// ---------------------------------------------------------------------------
extern "C" void x3WindowBenchRun() {
  FreeInkDisplay& d = display.benchRawDisplay();

  // ----- safety gates -----
  if (!d.framebufferReady()) {
    Serial.println(F("[BENCH] skip: framebuffer not ready (begin() not called, released, or alloc failed)"));
    return;
  }
  if (!d.isX3Mode()) {
    Serial.println(F("[BENCH] skip: panel is not X3 (windowed override is X3‑only)"));
    return;
  }

  // Purge any in‑flight async refresh from a previous path (harmless no‑op else).
  d.waitRefreshComplete();

  Serial.println(F("[BENCH] === X3 displayWindow bench ==="));

  // ---- Step 1: live full‑panel FAST reference ---------------------------
  fillFullScreenPattern(d);
  {
    const unsigned long t0 = millis();
    d.displayBuffer(FreeInkDisplay::FAST_REFRESH);
    const unsigned long elapsed = millis() - t0;
    Serial.print(F("[BENCH] step1 full-panel FAST reference: "));
    Serial.print(elapsed);
    Serial.println(F(" ms  (windowed results must be materially below this)"));

    // Dual‑buffer builds: copy the just‑displayed frame back to the write
    // buffer so subsequent rect draws rest on the correct baseline.
    // On X3 single‑buffer this is a harmless no‑op.
    d.syncWriteBufferFromActive();
  }
  delay(1500);

  // ---- Step 2: byte‑aligned rect ----------------------------------------
  {
    constexpr uint16_t rx = 200, ry = 100, rw = 200, rh = 120;
    drawAsymmetricBand(d, rx, ry, rw, rh);
    printExpectTop(rx, ry, rw, rh, F("byte-aligned"));
    const unsigned long t0 = millis();
    d.displayWindow(rx, ry, rw, rh);
    const unsigned long elapsed = millis() - t0;
    Serial.print(F("[BENCH] step2 elapsed: "));
    Serial.print(elapsed);
    Serial.println(F(" ms"));
  }
  delay(1500);

  // ---- Step 3: deliberately UNALIGNED rect (exercises outward rounding) ---
  {
    constexpr uint16_t rx = 205, ry = 100, rw = 190, rh = 120;
    drawAsymmetricBand(d, rx, ry, rw, rh);
    printExpectTop(rx, ry, rw, rh, F("UNALIGNED x=205 -- expect rounded x down to 200"));
    const unsigned long t0 = millis();
    d.displayWindow(rx, ry, rw, rh);
    const unsigned long elapsed = millis() - t0;
    Serial.print(F("[BENCH] step3 elapsed: "));
    Serial.print(elapsed);
    Serial.println(F(" ms"));
  }
  delay(1500);

  // ---- Step 4: single‑row rect (descending‑loop off‑by‑one boundary) -----
  {
    constexpr uint16_t rx = 200, ry = 300, rw = 200, rh = 1;
    drawAsymmetricBand(d, rx, ry, rw, rh);
    printExpectTop(rx, ry, rw, rh, F("single-row descending-loop boundary (h=1)"));
    const unsigned long t0 = millis();
    d.displayWindow(rx, ry, rw, rh);
    const unsigned long elapsed = millis() - t0;
    Serial.print(F("[BENCH] step4 elapsed: "));
    Serial.print(elapsed);
    Serial.println(F(" ms"));
  }
  delay(1500);

  // ---- Step 5: top‑edge rect (gate‑space inversion boundary) ------------
  {
    constexpr uint16_t rx = 200, ry = 0, rw = 200, rh = 8;
    drawAsymmetricBand(d, rx, ry, rw, rh);
    printExpectTop(rx, ry, rw, rh, F("y=0 gate-space boundary"));
    const unsigned long t0 = millis();
    d.displayWindow(rx, ry, rw, rh);
    const unsigned long elapsed = millis() - t0;
    Serial.print(F("[BENCH] step5 elapsed: "));
    Serial.print(elapsed);
    Serial.println(F(" ms"));
  }

  Serial.println(F("[BENCH] === bench complete ==="));
  // turnOff=true is NOT exercised here (R12 PTOUT-after-power-off is
  // unvalidated and may leave the controller clipped).  Future work.
}

#endif  // X3_WINDOW_BENCH
