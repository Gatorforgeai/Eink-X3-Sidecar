#include <Arduino.h>
// DESIGN INTENT (Sidecar integration): Sidecar traffic and cached-page buttons reset inactivity,
// retain screen ownership through scheduled sleep, and never allow the reader loop to repaint an
// owned page. Non-Sidecar builds and normal reader button behavior remain unchanged.
#include <BoardConfig.h>
#include <Epub.h>
#include <FontCacheManager.h>
#include <FontDecompressor.h>
#include <GfxRenderer.h>
#include <HalClock.h>
#include <HalDisplay.h>
#include <HalGPIO.h>
#include <HalPowerManager.h>
#include <HalStorage.h>
#include <HalSystem.h>
#include <HalTiltSensor.h>
#include <I18n.h>
#include <Logging.h>
#include <SPI.h>
#include <WiFi.h>
#include <builtinFonts/all.h>

#include <cstring>

#include "CrossPointSettings.h"
#include "CrossPointState.h"
#include "KOReaderCredentialStore.h"
#include "MappedInputManager.h"
#include "OpdsServerStore.h"
#include "RecentBooksStore.h"
#include "SdCardFontSystem.h"
#include "activities/Activity.h"
#include "activities/ActivityManager.h"
#include "activities/settings/SdFirmwareUpdateActivity.h"
#include "components/UITheme.h"
#include "fontIds.h"
#include "images/LoadingIcon.h"
#include "util/ButtonNavigator.h"
#include "util/ScreenshotUtil.h"

#ifdef X3_WINDOW_BENCH
namespace freeink {
class FreeInkDisplay;
}
extern "C" void x3WindowBenchRun(freeink::FreeInkDisplay&);
#endif

#ifdef SIDECAR_LINK
// Sidecar BLE link, stage 1. Defined in src/SidecarLink.cpp; compiles to empty
// stubs without -DSIDECAR_LINK, so no NimBLE code enters the binary.
extern "C" void sidecarLinkBegin();
extern "C" void sidecarLinkPoll();
// True once the sidecar has painted a frame. While true the reader UI must NOT repaint:
// activityManager.loop() is what erased the window bench 3 ms after it finished.
extern "C" bool sidecarLinkOwnsScreen();
// Edge-triggered: true if a sidecar write landed since the last call, then clears.
// sidecarLinkOwnsScreen() is a latch and cannot answer "recently".
extern "C" bool sidecarLinkConsumeActivity();
// Consume X3 Up/Down as previous/next while a stored Sidecar bundle owns the screen.
extern "C" bool sidecarLinkHandleButtons(void* input);
extern "C" void sidecarLinkResumeScreenOwnership();
extern "C" bool sidecarLinkRestoreStoredPage();
extern "C" bool sidecarLinkAdvanceStoredPage();
#endif

GfxRenderer renderer(display);
MappedInputManager mappedInputManager(gpio, renderer);
ActivityManager activityManager(renderer, mappedInputManager);
FontDecompressor fontDecompressor;
SdCardFontSystem sdFontSystem;
FontCacheManager fontCacheManager(renderer.getFontMap(), renderer.getSdCardFonts());
static unsigned long allowSleepAt = 0;

// ── TIMER WAKE (R2, 2026-09-02) ─────────────────────────────────────────────────
// BLE cannot wake an ESP32-C3 from deep sleep: the wireless peripherals are unpowered and
// CONFIG_PM_ENABLE / CONFIG_BT_CTRL_MODEM_SLEEP are both off in the Arduino prebuilt libs, so
// esp_sleep_enable_bt_wakeup() does not exist for us. The wake input is a PIN sampled by the
// always-on RTC domain - there is no register for the phone to poke. So the slate wakes ITSELF
// instead: sleep, come up on a timer, advertise, and if the phone does not push anything inside
// the check-in window, go straight back down.
constexpr uint32_t kSidecarWakeIntervalSec = 120;         // field-tested cadence: every two minutes
constexpr unsigned long kSidecarCheckInWindowMs = 20000;  // how long to wait for the phone

// RTC-backed so it survives deep sleep. This is the ONLY evidence that the schedule is running
// rather than stuck - without it a dead cycle and a healthy one look identical.
RTC_DATA_ATTR static uint32_t sidecarWakeCycles = 0;
static bool sidecarTimerWake = false;

// MEASURED BUG, 2026-09-07: the first version armed the timer on `sidecarOwns` alone. Exactly one
// cycle ran and then the slate slept forever - COM5 dropped 16:20:33, came back 16:22:30, dropped
// again 16:22:56, and never returned. Cause: a timer wake is a full chip RESET, so SidecarLink's
// `screenOwned_` (a plain RAM static) comes back FALSE. With no phone pushing during the check-in
// window it stays false, so the next enterDeepSleep() did not re-arm and the schedule died after
// one hop. The schedule has to outlive the reset, so it lives in RTC memory like the counter.
// Cleared on physical input, so a reader session never inherits it.
RTC_DATA_ATTR static bool sidecarScheduleArmed = false;

// ── BATTERY SOAK LOG (2026-09-07) ───────────────────────────────────────────────
// Two facts make a plain serial log useless for measuring drain:
//   1. The existing [MEM] line is gated on `if (Serial && ...)`, so it prints NOTHING when the
//      slate is on battery with no USB - which is the only condition worth measuring.
//   2. The sleep path cannot be captured over USB at all: the serial port IS the ESP32's own USB
//      peripheral, so it dies with the chip. Measured twice - the capture stops at X3_POF every
//      time, mid-sentence.
// So samples go to RTC memory, which survives deep sleep, and are dumped on the next boot when a
// cable is back. Owner asked specifically for a reading taken before it goes to sleep; that is
// the ATSLEEP sample.
struct BattSample {
  uint32_t uptimeMs;
  uint16_t mv;
  uint8_t pct;
  uint8_t flags;
};
constexpr uint8_t kBattFlagValid = 0x80;
constexpr uint8_t kBattFlagAtSleep = 0x01;
constexpr uint8_t kBattFlagCharging = 0x02;
constexpr uint8_t kBattFlagExtPower = 0x04;
constexpr uint16_t kBattRing = 64;              // 64 x 8 bytes = 512 B of the C3's 8 KB RTC slow RAM
constexpr unsigned long kBattSampleMs = 60000;  // one sample a minute
RTC_DATA_ATTR static BattSample battRing[kBattRing];
RTC_DATA_ATTR static uint16_t battRingCount = 0;  // total ever written; slot = count % kBattRing
// The ring intentionally favors recent cadence detail and wraps during an overnight run. Keep the
// first valid unplugged point separately so the dump still has a true start-to-finish endpoint.
RTC_DATA_ATTR static BattSample battFirstUnplugged;

// RTC memory is lost if reconnecting USB produces a cold boot on the X3. Keep an append-only copy
// of unplugged samples on the SD card as the authoritative soak record. A partial final record from
// power loss is ignored; earlier complete records remain usable.
constexpr char BATT_SOAK_FILE[] = "/.crosspoint/sidecar_batt_soak.bin";
constexpr uint32_t kBattDiskMagic = 0x42545431;  // "BTT1"
struct BattDiskRecord {
  uint32_t magic;
  BattSample sample;
};

static void battPersist(const BattSample& sample) {
  HalFile file = Storage.open(BATT_SOAK_FILE, O_WRITE | O_CREAT);
  if (!file) {
    LOG_ERR("BATT", "could not open persistent soak record");
    return;
  }
  file.seekSet(file.fileSize());
  const BattDiskRecord record{kBattDiskMagic, sample};
  const size_t written = file.write(&record, sizeof(record));
  file.flush();
  file.close();
  if (written != sizeof(record)) {
    LOG_ERR("BATT", "persistent soak write incomplete (%u/%u)", static_cast<unsigned>(written),
            static_cast<unsigned>(sizeof(record)));
  }
}

static bool battDumpPersistent() {
  HalFile file;
  if (!Storage.openFileForRead("BATT", BATT_SOAK_FILE, file)) return false;
  BattDiskRecord record{};
  BattSample first{};
  BattSample latest{};
  uint32_t count = 0;
  while (file.read(&record, sizeof(record)) == sizeof(record)) {
    if (record.magic != kBattDiskMagic || !(record.sample.flags & kBattFlagValid)) continue;
    if (count == 0) first = record.sample;
    latest = record.sample;
    count++;
  }
  file.close();
  if (count == 0) return false;
  LOG_INF("BATT", "persistent unplugged samples=%lu first=%umV/%u%% latest=%umV/%u%%",
          static_cast<unsigned long>(count), first.mv, first.pct, latest.mv, latest.pct);
  return true;
}

static void battStartPersistentSoak() {
  if (Storage.exists(BATT_SOAK_FILE)) Storage.remove(BATT_SOAK_FILE);
  memset(battRing, 0, sizeof(battRing));
  battRingCount = 0;
  battFirstUnplugged = {};
  LOG_INF("BATT", "persistent soak reset; unplug when ready");
}

// Function-local static: BoardConfig::ACTIVE must be resolved before the monitor is built, so it
// must NOT be a file-scope object running at static-init time.
static BatteryMonitor& batteryMonitor() {
  static BatteryMonitor m;
  return m;
}

static void battRecord(bool atSleep) {
  const BatteryMonitor::Status s = batteryMonitor().readStatus();
  if (!s.supported) return;
  BattSample& e = battRing[battRingCount % kBattRing];
  e.uptimeMs = millis();
  e.mv = s.millivoltsKnown ? s.millivolts : 0;
  e.pct = s.percentageKnown ? static_cast<uint8_t>(s.percentage) : 0xFF;
  e.flags = kBattFlagValid | (atSleep ? kBattFlagAtSleep : 0) |
            ((s.chargingKnown && s.charging) ? kBattFlagCharging : 0) |
            ((s.externalPowerKnown && s.externalPower) ? kBattFlagExtPower : 0);
  if (!(battFirstUnplugged.flags & kBattFlagValid) &&
      !(e.flags & (kBattFlagCharging | kBattFlagExtPower))) {
    battFirstUnplugged = e;
  }
  // Persist only the pre-sleep checkpoints. Charge-state detection can read false when a full
  // battery draws no current, while a scheduled deep sleep occurs only after the owner has
  // unplugged the unit for the soak. Boot and ordinary awake samples remain in the RTC detail ring.
  if (atSleep && !(e.flags & (kBattFlagCharging | kBattFlagExtPower))) battPersist(e);
  battRingCount++;
}

// CHG/EXT matter: a soak run taken on USB is not a battery measurement, and this is how we tell.
static void battDump() {
  const bool persistentFound = battDumpPersistent();
  if (battRingCount == 0) {
    LOG_INF("BATT", persistentFound ? "no RTC samples recorded" : "no samples recorded yet");
    return;
  }
  if (battFirstUnplugged.flags & kBattFlagValid) {
    const BattSample& e = battFirstUnplugged;
    LOG_INF("BATT", "first unplugged up=%lums mv=%u pct=%u%s", static_cast<unsigned long>(e.uptimeMs), e.mv,
            e.pct, (e.flags & kBattFlagAtSleep) ? " ATSLEEP" : "");
  }
  const uint16_t n = (battRingCount < kBattRing) ? battRingCount : kBattRing;
  const uint16_t start = (battRingCount < kBattRing) ? 0 : (battRingCount % kBattRing);
  LOG_INF("BATT", "dump %u samples (total recorded %u, oldest first)", n, battRingCount);
  for (uint16_t i = 0; i < n; i++) {
    const BattSample& e = battRing[(start + i) % kBattRing];
    if (!(e.flags & kBattFlagValid)) continue;
    LOG_INF("BATT", "  up=%lums mv=%u pct=%u%s%s%s", static_cast<unsigned long>(e.uptimeMs), e.mv, e.pct,
            (e.flags & kBattFlagAtSleep) ? " ATSLEEP" : "", (e.flags & kBattFlagCharging) ? " CHG" : "",
            (e.flags & kBattFlagExtPower) ? " EXT" : "");
  }
}

// Fonts
EpdFont notoserif14RegularFont(&notoserif_14_regular);
EpdFont notoserif14BoldFont(&notoserif_14_bold);
EpdFont notoserif14ItalicFont(&notoserif_14_italic);
EpdFont notoserif14BoldItalicFont(&notoserif_14_bolditalic);
EpdFontFamily notoserif14FontFamily(&notoserif14RegularFont, &notoserif14BoldFont, &notoserif14ItalicFont,
                                    &notoserif14BoldItalicFont);
#ifndef OMIT_FONTS
EpdFont notoserif12RegularFont(&notoserif_12_regular);
EpdFont notoserif12BoldFont(&notoserif_12_bold);
EpdFont notoserif12ItalicFont(&notoserif_12_italic);
EpdFont notoserif12BoldItalicFont(&notoserif_12_bolditalic);
EpdFontFamily notoserif12FontFamily(&notoserif12RegularFont, &notoserif12BoldFont, &notoserif12ItalicFont,
                                    &notoserif12BoldItalicFont);
EpdFont notoserif16RegularFont(&notoserif_16_regular);
EpdFont notoserif16BoldFont(&notoserif_16_bold);
EpdFont notoserif16ItalicFont(&notoserif_16_italic);
EpdFont notoserif16BoldItalicFont(&notoserif_16_bolditalic);
EpdFontFamily notoserif16FontFamily(&notoserif16RegularFont, &notoserif16BoldFont, &notoserif16ItalicFont,
                                    &notoserif16BoldItalicFont);
EpdFont notoserif18RegularFont(&notoserif_18_regular);
EpdFont notoserif18BoldFont(&notoserif_18_bold);
EpdFont notoserif18ItalicFont(&notoserif_18_italic);
EpdFont notoserif18BoldItalicFont(&notoserif_18_bolditalic);
EpdFontFamily notoserif18FontFamily(&notoserif18RegularFont, &notoserif18BoldFont, &notoserif18ItalicFont,
                                    &notoserif18BoldItalicFont);

EpdFont notosans12RegularFont(&notosans_12_regular);
EpdFont notosans12BoldFont(&notosans_12_bold);
EpdFont notosans12ItalicFont(&notosans_12_italic);
EpdFont notosans12BoldItalicFont(&notosans_12_bolditalic);
EpdFontFamily notosans12FontFamily(&notosans12RegularFont, &notosans12BoldFont, &notosans12ItalicFont,
                                   &notosans12BoldItalicFont);
EpdFont notosans14RegularFont(&notosans_14_regular);
EpdFont notosans14BoldFont(&notosans_14_bold);
EpdFont notosans14ItalicFont(&notosans_14_italic);
EpdFont notosans14BoldItalicFont(&notosans_14_bolditalic);
EpdFontFamily notosans14FontFamily(&notosans14RegularFont, &notosans14BoldFont, &notosans14ItalicFont,
                                   &notosans14BoldItalicFont);
EpdFont notosans16RegularFont(&notosans_16_regular);
EpdFont notosans16BoldFont(&notosans_16_bold);
EpdFont notosans16ItalicFont(&notosans_16_italic);
EpdFont notosans16BoldItalicFont(&notosans_16_bolditalic);
EpdFontFamily notosans16FontFamily(&notosans16RegularFont, &notosans16BoldFont, &notosans16ItalicFont,
                                   &notosans16BoldItalicFont);
EpdFont notosans18RegularFont(&notosans_18_regular);
EpdFont notosans18BoldFont(&notosans_18_bold);
EpdFont notosans18ItalicFont(&notosans_18_italic);
EpdFont notosans18BoldItalicFont(&notosans_18_bolditalic);
EpdFontFamily notosans18FontFamily(&notosans18RegularFont, &notosans18BoldFont, &notosans18ItalicFont,
                                   &notosans18BoldItalicFont);

#endif  // OMIT_FONTS

EpdFont smallFont(&notosans_8_regular);
EpdFontFamily smallFontFamily(&smallFont);

EpdFont ui10RegularFont(&ubuntu_10_regular);
EpdFont ui10BoldFont(&ubuntu_10_bold);
EpdFontFamily ui10FontFamily(&ui10RegularFont, &ui10BoldFont);

EpdFont ui12RegularFont(&ubuntu_12_regular);
EpdFont ui12BoldFont(&ubuntu_12_bold);
EpdFontFamily ui12FontFamily(&ui12RegularFont, &ui12BoldFont);

// measurement of power button press duration calibration value
unsigned long t1 = 0;
unsigned long t2 = 0;

// Definitions for SilentRestart.h. RTC_NOINIT survives ESP.restart() but not power loss.
RTC_NOINIT_ATTR uint32_t silentRebootMagic;
RTC_NOINIT_ATTR uint32_t silentRebootTarget;
constexpr uint32_t SILENT_REBOOT_MAGIC = 0xC1EAB007;
constexpr uint32_t SILENT_REBOOT_TARGET_HOME = 0;
constexpr uint32_t SILENT_REBOOT_TARGET_READER = 1;

// How the device is coming back to life, resolved once at boot. Both resume
// flows suppress the splash and leave the panel holding its pre-boot frame; a
// plain boot shows the splash. See setup() for the resolution.
enum class BootResume : uint8_t {
  Splash,       // cold boot, flash, panic, or plain reboot
  Silent,       // heap-defrag ESP.restart() (RTC flag; lost on power loss)
  QuickResume,  // wake from a quick-resume deep sleep (SD flag; survives power loss)
};

// Latched true once enterDeepSleep() commits to sleeping, before it tears down
// the current activity. WiFi activities call silentRestart() in onExit() to
// clear heap fragmentation on the way out, but deep sleep is a full chip reset
// on wake and already clears the heap, so rebooting here would just power the
// device back up against the user's sleep gesture. Never cleared:
// startDeepSleep() does not return, so a set latch only ends at the wakeup reset.
static bool deepSleepInProgress = false;

void silentRestart() {
  if (deepSleepInProgress) return;  // sleeping supersedes the heap-defrag reboot
  silentRebootTarget = SILENT_REBOOT_TARGET_HOME;
  silentRebootMagic = SILENT_REBOOT_MAGIC;
  LOG_DBG("MAIN", "Silent restart (target=home)");
  // E-ink retains the previous frame until Home's first paint lands (~2-3s).
  // Without an overlay, users don't see the reboot and fire input through to
  // Home. Select on the default selectorIndex=0 then opens the most-recent
  // book, looking like a trampoline back to the reader they just exited.
  GUI.drawPopup(renderer, tr(STR_LOADING_POPUP));
  delay(50);
  ESP.restart();
}

void silentRestartToReader() {
  if (deepSleepInProgress) return;  // sleeping supersedes the heap-defrag reboot
  silentRebootTarget = SILENT_REBOOT_TARGET_READER;
  silentRebootMagic = SILENT_REBOOT_MAGIC;
  LOG_DBG("MAIN", "Silent restart (target=reader)");
  GUI.drawPopup(renderer, tr(STR_LOADING_POPUP));
  delay(50);
  ESP.restart();
}

void waitForPowerRelease() {
  gpio.update();
  while (gpio.isPressed(HalGPIO::BTN_POWER)) {
    delay(50);
    gpio.update();
  }
}

constexpr char SLEEP_FRAME_FILE[] = "/.crosspoint/sleep_frame.bin";

static void saveSleepFrameBuffer() {
  HalFile file;
  if (!Storage.openFileForWrite("SLP", SLEEP_FRAME_FILE, file)) return;
  file.write(renderer.getFrameBuffer(), renderer.getBufferSize());
  file.close();
}

static bool loadSleepFrameBuffer() {
  HalFile file;
  if (!Storage.openFileForRead("SLP", SLEEP_FRAME_FILE, file)) return false;
  const size_t bufferSize = display.getBufferSize();
  const size_t bytesRead = file.read(display.getFrameBuffer(), bufferSize);
  file.close();
  if (bytesRead != bufferSize) {
    Storage.remove(SLEEP_FRAME_FILE);
    return false;
  }
  Storage.remove(SLEEP_FRAME_FILE);
  return true;
}

// Enter deep sleep mode
void enterDeepSleep(bool fromTimeout = false) {
  HalPowerManager::Lock powerLock;  // Ensure we are at normal CPU frequency for sleep preparation
  bool sidecarOwns = false;
#ifdef SIDECAR_LINK
  sidecarOwns = sidecarLinkOwnsScreen();
#endif
  // Borrow the QUICK_RESUME sleep screen when preserving the Sidecar page: it is the only branch
  // that does not clearScreen(), and it makes the framebuffer save/restore path run. In-RAM only -
  // this must never be persisted, so SETTINGS is restored below and never saved here.
  const bool preserveSidecarPage = sidecarOwns || sidecarScheduleArmed;
  const uint8_t savedSleepScreen = SETTINGS.sleepScreen;
  if (preserveSidecarPage) {
    SETTINGS.sleepScreen = CrossPointSettings::SLEEP_SCREEN_MODE::QUICK_RESUME;
  }
  APP_STATE.lastSleepFromReader = activityManager.isReaderActivity();

  const bool isQuickResumeSleep =
      SETTINGS.sleepScreen == CrossPointSettings::SLEEP_SCREEN_MODE::QUICK_RESUME ||
      (fromTimeout &&
       SETTINGS.quickResumeSleepScreen == CrossPointSettings::QUICK_RESUME_SLEEP_SCREEN::QUICK_RESUME_AFTER_TIMEOUT);
  APP_STATE.showBootScreen = !isQuickResumeSleep;

  APP_STATE.saveToFile();

  // Commit to sleeping before goToSleep() runs the outgoing activity's onExit():
  // a WiFi activity would otherwise silentRestart() here and reboot instead.
  deepSleepInProgress = true;
  if (preserveSidecarPage) {
    LOG_DBG("MAIN", "Sidecar page preserved; skipping sleep screen render (owns=%d armed=%d)",
            sidecarOwns ? 1 : 0, sidecarScheduleArmed ? 1 : 0);
  } else {
    activityManager.goToSleep(fromTimeout);
  }
  SETTINGS.sleepScreen = savedSleepScreen;

  if (isQuickResumeSleep) {
    saveSleepFrameBuffer();
  }

  // Tear down WiFi so the modem power domain isn't held alive across deep sleep.
  // Wake from deep sleep is effectively a chip reset, so no state needs to survive.
  if (WiFi.getMode() != WIFI_MODE_NULL) {
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
  }

  // Take the reading BEFORE the panel and sensors go down, so it reflects the running state
  // rather than a half-powered one. This is the sample that pairs with the first one after wake.
  battRecord(true);

  halTiltSensor.deepSleep();
  display.deepSleep();
  // Owning the screen STARTS the schedule; RTC memory KEEPS it. Checking sidecarOwns alone died
  // after one cycle (see the note at sidecarScheduleArmed) because the wake reset clears it.
  if (sidecarOwns) {
    sidecarScheduleArmed = true;
  }
  if (sidecarScheduleArmed) {
    // Wake sources are additive: startDeepSleep() arms the power-button GPIO, this adds the timer.
    // Both stay live, so the button still works exactly as before.
    esp_sleep_enable_timer_wakeup(static_cast<uint64_t>(kSidecarWakeIntervalSec) * 1000000ULL);
    sidecarWakeCycles++;
  }
  LOG_DBG("MAIN", "Entering deep sleep (sidecarOwns=%d, armed=%d, fromTimeout=%d, timerWakeIn=%us, cycle=%u)",
          sidecarOwns ? 1 : 0, sidecarScheduleArmed ? 1 : 0, fromTimeout ? 1 : 0,
          sidecarScheduleArmed ? kSidecarWakeIntervalSec : 0, sidecarWakeCycles);

  powerManager.startDeepSleep(gpio);
}

void setupDisplayAndFonts(bool seamless = false) {
  display.begin(seamless);
  renderer.begin();
  activityManager.begin();
  LOG_DBG("MAIN", "Display initialized");

  // Initialize font decompressor for compressed reader fonts
  if (!fontDecompressor.init()) {
    LOG_ERR("MAIN", "Font decompressor init failed");
  }
  fontCacheManager.setFontDecompressor(&fontDecompressor);
  renderer.setFontCacheManager(&fontCacheManager);
  renderer.insertFont(NOTOSERIF_14_FONT_ID, notoserif14FontFamily);
#ifndef OMIT_FONTS
  renderer.insertFont(NOTOSERIF_12_FONT_ID, notoserif12FontFamily);
  renderer.insertFont(NOTOSERIF_16_FONT_ID, notoserif16FontFamily);
  renderer.insertFont(NOTOSERIF_18_FONT_ID, notoserif18FontFamily);

  renderer.insertFont(NOTOSANS_12_FONT_ID, notosans12FontFamily);
  renderer.insertFont(NOTOSANS_14_FONT_ID, notosans14FontFamily);
  renderer.insertFont(NOTOSANS_16_FONT_ID, notosans16FontFamily);
  renderer.insertFont(NOTOSANS_18_FONT_ID, notosans18FontFamily);
#endif  // OMIT_FONTS
  renderer.insertFont(UI_10_FONT_ID, ui10FontFamily);
  renderer.insertFont(UI_12_FONT_ID, ui12FontFamily);
  renderer.insertFont(SMALL_FONT_ID, smallFontFamily);

  // Discover and load SD card fonts
  sdFontSystem.begin(renderer);

  LOG_DBG("MAIN", "Fonts setup");
}

void setup() {
  BoardConfig::holdPowerRails();

  t1 = millis();

#ifdef ENABLE_SERIAL_LOG
  // Earliest possible Serial setup. The 250 ms stall before begin() lets the
  // USB Serial/JTAG peripheral finish power-on and lets the host complete USB
  // enumeration before we touch the CDC state — otherwise cold boot races
  // and the host has to be physically replugged for logs to flow. Warm reboot
  // worked without the delay because USB was already enumerated.
  delay(250);
  Serial.begin(115200);
#if LOG_SERIAL_HAS_TX_TIMEOUT
  logSerial.setTxTimeoutMs(1);  // This is a load-bearing 1. Do not modify.
#endif
#endif

  HalSystem::begin();

  // Read-and-clear so a panic later in setup() doesn't loop into silent reboot.
  // Bound the target range too — RTC_NOINIT memory is uninitialized on cold boot.
  const bool isSilentReboot = (silentRebootMagic == SILENT_REBOOT_MAGIC);
  const uint32_t snapshotTarget =
      (isSilentReboot && silentRebootTarget <= SILENT_REBOOT_TARGET_READER) ? silentRebootTarget : 0;
  silentRebootMagic = 0;
  silentRebootTarget = 0;

  gpio.begin();
  powerManager.begin();
  halTiltSensor.begin();
  halClock.begin();

  LOG_INF("MAIN", "Hardware detect: %s", gpio.deviceIsX3() ? "X3" : "X4");

  // SD Card Initialization
  // We need 6 open files concurrently when parsing a new chapter
  if (!Storage.begin()) {
    LOG_ERR("MAIN", "SD card initialization failed");
    setupDisplayAndFonts(isSilentReboot);
    activityManager.goToFullScreenMessage("SD card error", EpdFontFamily::BOLD);
    return;
  }

  HalSystem::checkPanic();

  SETTINGS.loadFromFile();
  APP_STATE.loadFromFile();
  RECENT_BOOKS.loadFromFile();
  I18N.setLanguage(static_cast<Language>(SETTINGS.language));
  KOREADER_STORE.loadFromFile();
  OPDS_STORE.loadFromFile();
  UITheme::getInstance().reload();
  ButtonNavigator::setMappedInputManager(mappedInputManager);

  const auto wakeupReason = gpio.getWakeupReason();
  switch (wakeupReason) {
    case HalGPIO::WakeupReason::PowerButton:
      LOG_DBG("MAIN", "Verifying power button press duration");
      if (!gpio.verifyPowerButtonWakeup(SETTINGS.getPowerButtonDuration(),
                                        SETTINGS.shortPwrBtn == CrossPointSettings::SHORT_PWRBTN::SLEEP)) {
        powerManager.startDeepSleep(gpio);
      }
      break;
    case HalGPIO::WakeupReason::AfterUSBPower:
      // If USB power caused a cold boot, go back to sleep
      LOG_DBG("MAIN", "Wakeup reason: After USB Power");
      powerManager.startDeepSleep(gpio);
      break;
    case HalGPIO::WakeupReason::AfterFlash:
      // After flashing, just proceed to boot
    case HalGPIO::WakeupReason::Other:
    default:
      break;
  }

  // A timer wake is ours. getWakeupReason() has no Timer case and maps it to Other, so ask the
  // IDF directly rather than widening the HAL enum. Deliberately AFTER the switch: the
  // AfterUSBPower branch must keep its existing behaviour untouched.
  sidecarTimerWake = (esp_sleep_get_wakeup_cause() == ESP_SLEEP_WAKEUP_TIMER);
  if (sidecarTimerWake) {
    LOG_INF("MAIN", "Timer wake (cycle %u): checking in for %lu ms", sidecarWakeCycles,
            kSidecarCheckInWindowMs);
  }


  // Recovery firmware mode: hold left side button (BTN_UP) together with the power button at
  // boot to skip directly to the SD-card firmware update screen. Useful on devices where USB
  // flashing has been locked down (e.g. recent X3 firmware).
  bool recoveryFirmwareMode = false;
  if (wakeupReason == HalGPIO::WakeupReason::PowerButton) {
    // Refresh the cached button state a few times — isPressed() needs ~half a second to settle
    // after boot per the HalGPIO contract. Use a millis-based deadline so we always wait the full
    // settle window even if the loop body takes longer than expected on slow boots.
    const unsigned long settleStart = millis();
    while (millis() - settleStart < 500) {
      gpio.update();
      delay(10);
    }
    if (gpio.isPressed(HalGPIO::BTN_UP)) {
      recoveryFirmwareMode = true;
      LOG_INF("MAIN", "Recovery firmware mode (UP + POWER held at boot)");
    }
  }

  // First serial output only here to avoid timing inconsistencies for power button press duration verification
  LOG_DBG("MAIN", "Starting CrossPoint version " CROSSPOINT_VERSION);

  // MEASURED 2026-09-10: these two were up beside the wake-reason switch and printed NOTHING on a
  // real wake. The capture's first line was uptime [921] - the version line above - because the
  // comment right there says it: nothing before this point reaches the host, the USB CDC is not
  // up yet. So the dump has to live AFTER the first intentional serial output, not before it.
  // Fresh sample first, then replay what RTC memory kept across the sleep.
  battRecord(false);
  battDump();

  // Resolve the single boot-presentation decision. Skipping the splash also
  // skips the panel-clearing pass and the X3 initial-full-sync arming (see
  // HalDisplay::begin), so the first paint is FAST_REFRESH (~500ms) over the
  // retained frame and input dispatches against a visible UI.
  const BootResume resume = isSilentReboot              ? BootResume::Silent
                            : !APP_STATE.showBootScreen ? BootResume::QuickResume
                                                        : BootResume::Splash;
  bool allowFastInitialReaderRefresh = false;
  bool sidecarRestored = false;

  setupDisplayAndFonts(resume != BootResume::Splash);

#ifdef SIDECAR_LINK
  // A hard reset clears the panel during display initialization. If an atomic Sidecar bundle is
  // still valid on SD, repaint its selected page instead of leaving an apparently dead blank page.
  if (resume == BootResume::Splash && sidecarLinkRestoreStoredPage()) {
    sidecarRestored = true;
    LOG_INF("MAIN", "Cold boot: restored selected Sidecar page");
  }
#endif

  switch (resume) {
    case BootResume::Silent:
      // Splash skipped: the routing block below picks the target activity; the
      // panel keeps showing the pre-reboot popup until that first paint lands.
      break;
    case BootResume::QuickResume:
      // One-shot flag: re-arm the splash for the next non-quick-resume boot. Save
      // before any painting so a hang in the blocking paint path can't strand
      // us in a quick-resume-with-no-frame loop on the next boot.
      APP_STATE.showBootScreen = true;
      APP_STATE.saveToFile();
      if (loadSleepFrameBuffer()) {
#ifdef SIDECAR_LINK
        if (sidecarScheduleArmed) {
          sidecarLinkResumeScreenOwnership();
          sidecarRestored = sidecarLinkOwnsScreen();
          if (sidecarRestored && sidecarTimerWake) {
            sidecarLinkAdvanceStoredPage();
          }
          if (!sidecarRestored) {
            // A timer schedule without a valid stored bundle has nothing to display or refresh.
            // Cancel it so normal Home/Reader routing and input processing resume on this wake.
            sidecarScheduleArmed = false;
            sidecarTimerWake = false;
          }
        }
#endif
        if (sidecarRestored) {
          LOG_DBG("MAIN", "Wake: restored Sidecar framebuffer without refresh");
        } else if (sidecarTimerWake) {
          LOG_DBG("MAIN", "Timer wake: restored Sidecar framebuffer without refresh");
        } else {
          const bool useDifferentialRefresh = gpio.deviceIsX3();
          if (useDifferentialRefresh) {
            // begin() clears the X3 controller RAM, so restore the saved frame as
            // the baseline before replacing the moon with the loading icon.
            renderer.cleanupGrayscaleWithFrameBuffer();
          }

          const auto pageHeight = renderer.getScreenHeight();
          renderer.drawImage(LoadingIcon, 0, pageHeight - LOADINGICON_HEIGHT, LOADINGICON_WIDTH, LOADINGICON_HEIGHT);
          if (useDifferentialRefresh) {
            renderer.displayGrayscaleBase(HalDisplay::FAST_REFRESH);
            allowFastInitialReaderRefresh = true;
          } else {
            renderer.displayBuffer(HalDisplay::HALF_REFRESH);
          }
        }
      } else {
        if (sidecarScheduleArmed) {
          sidecarScheduleArmed = false;
          sidecarTimerWake = false;
          LOG_ERR("MAIN", "Sidecar wake frame missing; recurring schedule cancelled");
        }
        activityManager.goToBoot();  // frame file missing, fall back to the splash
      }
      break;
    case BootResume::Splash:
      if (!sidecarRestored) activityManager.goToBoot();
      break;
  }

  if (recoveryFirmwareMode) {
    // Skip normal home/reader routing: jump straight into the SD firmware picker.
    activityManager.replaceActivity(
        std::make_unique<SdFirmwareUpdateActivity>(renderer, mappedInputManager, /*recoveryMode=*/true));
  } else if (HalSystem::isRebootFromPanic()) {
    // If we rebooted from a panic, go to crash report screen to show the panic info
    activityManager.goToCrashReport();
  } else if (sidecarRestored) {
    // The sleep framebuffer is already the visible Sidecar page. Do not route to Home or Reader,
    // because either activity would immediately repaint over the restored page.
    LOG_DBG("MAIN", "Sidecar screen ownership restored; normal activity routing deferred");
  } else if (resume == BootResume::Silent && snapshotTarget == SILENT_REBOOT_TARGET_READER &&
             !APP_STATE.openEpubPath.empty()) {
    activityManager.goToReader(APP_STATE.openEpubPath);
  } else if (resume == BootResume::Silent) {
    // target == home (or reader with no open book): land on home — don't fall
    // through to the sleep-wake "resume reader" logic, which fires on stale
    // openEpubPath + lastSleepFromReader from a prior session.
    activityManager.goHome();
  } else if (APP_STATE.openEpubPath.empty() || !APP_STATE.lastSleepFromReader ||
             mappedInputManager.isPressed(MappedInputManager::Button::Back) || APP_STATE.readerActivityLoadCount > 0) {
    // Boot to home screen if no book is open, last sleep was not from reader, back button is held, or reader activity
    // crashed (indicated by readerActivityLoadCount > 0)
    activityManager.goHome();
  } else {
    // Clear app state to avoid getting into a boot loop if the epub doesn't load
    const auto path = APP_STATE.openEpubPath;
    APP_STATE.openEpubPath = "";
    APP_STATE.readerActivityLoadCount++;
    APP_STATE.saveToFile();
    activityManager.goToReader(path, allowFastInitialReaderRefresh);
  }

  if (resume == BootResume::Silent) {
    // Block until the first paint physically completes. refreshDisplay()
    // waits on the panel BUSY pin so when this returns the user can see the
    // new activity. Without the wait, an edge captured by gpio.update()
    // during boot dispatches against an invisible Home and the default
    // selectorIndex=0 opens the most-recent book.
    activityManager.requestUpdateAndWait();
    // Absorb any button held at this point into currentState as a non-edge:
    // two gpio.update() calls separated by > InputManager's 5ms debounce
    // transition the held bit through lastDebounceTime into currentState
    // without setting pressedEvents, so the first loop()'s own gpio.update()
    // sees state == currentState and emits nothing.
    gpio.update();
    delay(10);
    gpio.update();
  }

  // Ensure we're not still holding the power button before leaving setup
  waitForPowerRelease();
  allowSleepAt = millis() + 2000;

}

void loop() {
#ifdef X3_WINDOW_BENCH
  // Bench harness for Uc8253X3Driver::displayWindow, run ONCE on the first loop.
  // NOT at the end of setup(): setup() has an early `return` in the quick-resume
  // branch (line ~311), which skipped the harness entirely on a normal boot.
  // loop() is reached on every boot path and the display is ready by then.
  static bool benchDone = false;
  if (!benchDone) {
    benchDone = true;
    LOG_INF("BENCH", "reached call site in loop(); entering x3WindowBenchRun");
    x3WindowBenchRun(display.benchRawDisplay());
    LOG_INF("BENCH", "returned from x3WindowBenchRun");
    // HOLD THE RESULT ON GLASS. Without this the Boot activity exits ~3 ms later and
    // repaints the whole reader UI over the bench rectangles - they were on screen for
    // about five seconds and nobody could ever see them. Power-cycle to resume normal UI.
    LOG_INF("BENCH", "holding bench image on screen INDEFINITELY - photograph any time, power-cycle to resume");
    for (;;) {
      delay(30000);
      LOG_INF("BENCH", "still holding bench image - power-cycle to resume normal UI");
    }
  }
#endif

#ifdef SIDECAR_LINK
  // Start advertising on the first loop so timer wakes keep the entire 20-second check-in window.
  // Android waits after capability discovery before transmitting, covering an ordinary boot's
  // blocking Home presentation.
  static bool sidecarStarted = false;
  if (!sidecarStarted) {
    sidecarStarted = true;
    sidecarLinkBegin();
  }
  if (sidecarStarted) sidecarLinkPoll();
#endif

  static unsigned long maxLoopDuration = 0;
  const unsigned long loopStartTime = millis();
  static unsigned long lastMemPrint = 0;

  gpio.setSharedConfirmPowerShortPressEmitsPower(SETTINGS.shortPwrBtn == CrossPointSettings::SHORT_PWRBTN::SLEEP);
  gpio.update();
  halTiltSensor.update(SETTINGS.tiltPageTurn, SETTINGS.orientation, activityManager.isReaderActivity());

  renderer.setFadingFix(SETTINGS.fadingFix);

  if (Serial && millis() - lastMemPrint >= 10000) {
    LOG_INF("MEM", "Free: %d bytes, Total: %d bytes, Min Free: %d bytes, MaxAlloc: %d bytes", ESP.getFreeHeap(),
            ESP.getHeapSize(), ESP.getMinFreeHeap(), ESP.getMaxAllocHeap());
    lastMemPrint = millis();
  }

  // Deliberately NOT gated on Serial - the whole point is to record while running on battery with
  // no cable attached. Goes to RTC memory; read it back with battDump() on the next boot.
  static unsigned long lastBattSample = 0;
  if (millis() - lastBattSample >= kBattSampleMs) {
    battRecord(false);
    lastBattSample = millis();
  }

  // Handle incoming serial commands,
  // nb: we use logSerial from logging to avoid deprecation warnings
  if (logSerial.available() > 0) {
    String line = logSerial.readStringUntil('\n');
    if (line.startsWith("CMD:")) {
      String cmd = line.substring(4);
      cmd.trim();
      if (cmd == "SCREENSHOT") {
        const uint32_t bufferSize = display.getBufferSize();
        logSerial.printf("SCREENSHOT_START:%d\n", bufferSize);
        uint8_t* buf = display.getFrameBuffer();
        logSerial.write(buf, bufferSize);
        logSerial.printf("SCREENSHOT_END\n");
      } else if (cmd == "BATT") {
        // Read both the RTC detail ring and the cold-boot-safe SD-card endpoints.
        battDump();
      } else if (cmd == "BATTSTART") {
        battStartPersistentSoak();
      } else if (cmd == "STORAGE") {
        // Read-only bench diagnostic for sizing Sidecar collections. The backend caches total at
        // mount and caches the slower FAT free-cluster scan for 20 seconds. Its legacy API uses
        // used==0 for both an empty volume and a failed scan, so report that case as ambiguous
        // instead of presenting a misleading free-space value.
        const bool ready = Storage.ready();
        const uint64_t total = ready ? Storage.totalBytes() : 0;
        const uint64_t used = ready ? Storage.usedBytes() : 0;
        const bool measured = ready && total > 0 && used > 0 && used <= total;
        const uint64_t free = measured ? total - used : 0;
        logSerial.printf("STORAGE_STATUS:ready=%u,measured=%u,total=%llu,used=%llu,free=%llu\n",
                         ready ? 1u : 0u, measured ? 1u : 0u,
                         static_cast<unsigned long long>(total),
                         static_cast<unsigned long long>(used),
                         static_cast<unsigned long long>(free));
      }
    }
  }

  // Check for any user activity (button press or release) or active background work
  static unsigned long lastActivityTime = millis();
  // Consumed unconditionally and BEFORE the || chain: inside it, short-circuiting would leave the
  // flag set and the next loop would see stale activity. A frame arriving over BLE is activity -
  // without this the slate auto-slept mid-transfer no matter how much the phone was sending.
  bool sidecarActivity = false;
#ifdef SIDECAR_LINK
  sidecarActivity = sidecarLinkHandleButtons(&mappedInputManager);
  sidecarActivity = sidecarLinkConsumeActivity() || sidecarActivity;
#endif
  if (sidecarActivity || gpio.wasAnyPressed() || gpio.wasAnyReleased() || gpio.wasTouchActivity() ||
      halTiltSensor.hadActivity() || activityManager.preventAutoSleep()) {
    lastActivityTime = millis();         // Reset inactivity timer
    powerManager.setPowerSaving(false);  // Restore normal CPU frequency on user activity
    if (!sidecarActivity) {
      // A human touched it. Hand the device back to the user's own sleep timeout and cancel the
      // wake schedule - otherwise a reader session inherits it and wakes itself all night.
      // Deliberately asymmetric: sidecar traffic must NOT clear these, or every successful
      // check-in would cost a full 10-minute awake window and the battery win evaporates.
      sidecarTimerWake = false;
      sidecarScheduleArmed = false;
    }
  }

  static bool screenshotButtonsReleased = true;
  static bool screenshotComboActive = false;
  if (gpio.isPressed(HalGPIO::BTN_POWER) && gpio.isPressed(HalGPIO::BTN_DOWN)) {
    screenshotComboActive = true;
    if (screenshotButtonsReleased) {
      screenshotButtonsReleased = false;
      {
        RenderLock lock;
        ScreenshotUtil::takeScreenshot(renderer);
      }
    }
    return;
  }
  if (screenshotComboActive) {
    if (gpio.isPressed(HalGPIO::BTN_POWER)) return;
    if (gpio.wasReleased(HalGPIO::BTN_POWER)) {
      screenshotButtonsReleased = true;
      screenshotComboActive = false;
      return;
    }
    screenshotButtonsReleased = true;
    screenshotComboActive = false;
  }

  // On a timer wake the phone gets a short window instead of the user's full timeout. R1 already
  // made BLE traffic reset lastActivityTime, so a phone that shows up and starts pushing keeps the
  // slate awake by the normal path - the success branch needs no special case.
  const unsigned long sleepTimeoutMs =
      sidecarTimerWake ? kSidecarCheckInWindowMs : SETTINGS.getSleepTimeoutMs();
  if (sleepTimeoutMs > 0 && millis() - lastActivityTime >= sleepTimeoutMs) {
    LOG_DBG("SLP", "Auto-sleep triggered after %lu ms of inactivity", sleepTimeoutMs);
    enterDeepSleep(true);
    // This should never be hit as `enterDeepSleep` calls esp_deep_sleep_start
    return;
  }

  if (millis() >= allowSleepAt && gpio.isPressed(HalGPIO::BTN_POWER) &&
      gpio.getPowerButtonHeldTime() > SETTINGS.getPowerButtonDuration()) {
    // If the screenshot combination is potentially being pressed, don't sleep
    if (gpio.isPressed(HalGPIO::BTN_DOWN)) {
      return;
    }
    enterDeepSleep();
    // This should never be hit as `enterDeepSleep` calls esp_deep_sleep_start
    return;
  }

  // Refresh screen when power button is short-pressed with FORCE_REFRESH setting.
  if (SETTINGS.shortPwrBtn == CrossPointSettings::SHORT_PWRBTN::FORCE_REFRESH &&
      mappedInputManager.wasReleased(MappedInputManager::Button::Power)) {
    LOG_DBG("MAIN", "Manual screen refresh triggered");
    if (!activityManager.handleForcedRefresh()) {
      RenderLock lock;
      renderer.displayBuffer(HalDisplay::HALF_REFRESH);
    }
  }

  // Refresh the battery icon when USB is plugged or unplugged.
  // Placed after sleep guards so we never queue a render that won't be processed.
  if (gpio.wasUsbStateChanged()) {
    activityManager.requestUpdate();
  }

  const unsigned long activityStartTime = millis();
#ifdef SIDECAR_LINK
  // Stage 2: once the sidecar has painted, the reader UI must not repaint over it.
  // Also suppress the normal UI during an untouched timer check-in window.
  if (!sidecarLinkOwnsScreen() && !sidecarTimerWake) {
    activityManager.loop();
  }
#else
  activityManager.loop();
#endif
  const unsigned long activityDuration = millis() - activityStartTime;

  const unsigned long loopDuration = millis() - loopStartTime;
  if (loopDuration > maxLoopDuration) {
    maxLoopDuration = loopDuration;
    if (maxLoopDuration > 50) {
      LOG_DBG("LOOP", "New max loop duration: %lu ms (activity: %lu ms)", maxLoopDuration, activityDuration);
    }
  }

  // Add delay at the end of the loop to prevent tight spinning
  // When an activity requests skip loop delay (e.g., webserver running), use yield() for faster response
  // Otherwise, use longer delay to save power
  if (activityManager.skipLoopDelay()) {
    powerManager.setPowerSaving(false);  // Make sure we're at full performance when skipLoopDelay is requested
    yield();                             // Give FreeRTOS a chance to run tasks, but return immediately
  } else {
    if (millis() - lastActivityTime >= HalPowerManager::IDLE_POWER_SAVING_MS) {
      // If we've been inactive for a while, increase the delay to save power
      powerManager.setPowerSaving(true);  // Lower CPU frequency after extended inactivity
      delay(50);
    } else {
      // Short delay to prevent tight loop while still being responsive
      delay(10);
    }
  }
}
