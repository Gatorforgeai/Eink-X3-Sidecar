#include "SidecarSink.h"
#include "SidecarFrameProtocol.h"
#include "SidecarCollectionsV5Store.h"
#include "SidecarCollectionsV5Transfer.h"

// DESIGN INTENT: Keep BLE callbacks nonblocking, advertise exact protocol capabilities, deliver
// completion notifications from the loop task, and reserve Up/Down for cached-page navigation only
// while Sidecar owns the screen. Malformed traffic or missing storage must leave the prior page valid.

#ifndef SIDECAR_LINK

extern "C" void sidecarLinkBegin() {}
extern "C" void sidecarLinkPoll() {}
extern "C" bool sidecarLinkOwnsScreen() { return false; }
extern "C" bool sidecarLinkConsumeActivity() { return false; }
extern "C" bool sidecarLinkHandleButtons(void*) { return false; }
extern "C" void sidecarLinkResumeScreenOwnership() {}
extern "C" bool sidecarLinkRestoreStoredPage() { return false; }
extern "C" bool sidecarLinkAdvanceStoredPage() { return false; }

#else

#include <Logging.h>
#include <NimBLEDevice.h>
#include <HalPowerManager.h>
#include <Preferences.h>
#include "MappedInputManager.h"
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

// The contract with the phone. These UUIDs are load-bearing; a typo means the
// Android side never finds the device.
constexpr const char* kServiceUuid = "6e6b1a00-9f1d-4b7a-9c2e-1f0a2d3c4b5a";
constexpr const char* kRxCharUuid = "6e6b1a01-9f1d-4b7a-9c2e-1f0a2d3c4b5a";
constexpr const char* kInfoCharUuid = "6e6b1a02-9f1d-4b7a-9c2e-1f0a2d3c4b5a";
constexpr const char* kStatusCharUuid = "6e6b1a03-9f1d-4b7a-9c2e-1f0a2d3c4b5a";
constexpr const char* kInventoryCharUuid = "6e6b1a04-9f1d-4b7a-9c2e-1f0a2d3c4b5a";
constexpr const char* kDeviceName = "EinkSidecar";

// Fixed-capacity staging between the NimBLE host task and the loop task.
// The hot path (onWrite) must not allocate, log, or block.
constexpr uint16_t kMaxPayload = 514;   // ATT MTU 517 minus its three-byte write overhead
constexpr uint16_t kRingSlots = 32;      // Bounded backlog while the panel blocks; background sender paces DATA >=25 ms.
static portMUX_TYPE generationMux_ = portMUX_INITIALIZER_UNLOCKED;
static uint32_t connectionGeneration_ = 0;

uint32_t advanceConnectionGeneration() {
  portENTER_CRITICAL(&generationMux_);
  const uint32_t value = ++connectionGeneration_;
  portEXIT_CRITICAL(&generationMux_);
  return value;
}

uint32_t currentConnectionGeneration() {
  portENTER_CRITICAL(&generationMux_);
  const uint32_t value = connectionGeneration_;
  portEXIT_CRITICAL(&generationMux_);
  return value;
}

struct RingItem {
  uint8_t data[kMaxPayload];
  uint16_t len;          // bytes stored (after truncation)
  uint16_t originalLen;  // bytes the central tried to send
  uint32_t generation;   // changes at every connect/disconnect boundary
  bool truncated;
};

class RingBuffer {
 public:
  bool push(const uint8_t* p, uint16_t n, uint32_t generation) {
    if (p == nullptr || n == 0) {
      portENTER_CRITICAL(&mux_);
      ++overflowCount_;  // Account for malformed callback input on the loop task.
      portEXIT_CRITICAL(&mux_);
      return false;
    }
    const uint16_t originalLen = n;
    if (n > kMaxPayload) {
      n = kMaxPayload;
    }
    const bool wasTruncated = (originalLen > n);

    portENTER_CRITICAL(&mux_);
    const uint16_t next = (head_ + 1) % kRingSlots;
    if (next == tail_) {
      ++overflowCount_;
      portEXIT_CRITICAL(&mux_);
      return false;
    }
    RingItem& slot = slots_[head_];
    std::memcpy(slot.data, p, n);
    slot.len = n;
    slot.originalLen = originalLen;
    slot.generation = generation;
    slot.truncated = wasTruncated;
    head_ = next;
    portEXIT_CRITICAL(&mux_);
    return true;
  }

  bool pop(RingItem* out) {
    portENTER_CRITICAL(&mux_);
    if (head_ == tail_) {
      portEXIT_CRITICAL(&mux_);
      return false;
    }
    *out = slots_[tail_];
    tail_ = (tail_ + 1) % kRingSlots;
    portEXIT_CRITICAL(&mux_);
    return true;
  }

  uint32_t drainOverflowCount() {
    portENTER_CRITICAL(&mux_);
    const uint32_t c = overflowCount_;
    overflowCount_ = 0;
    portEXIT_CRITICAL(&mux_);
    return c;
  }

  void clear() {
    portENTER_CRITICAL(&mux_);
    tail_ = head_;
    portEXIT_CRITICAL(&mux_);
  }

 private:
  RingItem slots_[kRingSlots];
  volatile uint16_t head_ = 0;
  volatile uint16_t tail_ = 0;
  uint32_t overflowCount_ = 0;
  portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;
};

static RingBuffer ringBuffer;

// Server callbacks also run on the NimBLE host task, so connect/disconnect
// events are queued here and logged (and any failure reported) from poll().
enum class ServerEventType : uint8_t { kConnect, kDisconnect, kMtuChange };

struct ServerEvent {
  ServerEventType type;
  uint16_t mtu;        // valid for kConnect and kMtuChange
  int reason;          // valid for kDisconnect
  bool restartOk;      // valid for kDisconnect
};

constexpr uint16_t kServerEventSlots = 16;

class ServerEventRing {
 public:
  bool push(ServerEventType type, uint16_t mtu, int reason, bool restartOk) {
    portENTER_CRITICAL(&mux_);
    const uint16_t next = (head_ + 1) % kServerEventSlots;
    if (next == tail_) {
      ++overflowCount_;
      portEXIT_CRITICAL(&mux_);
      return false;
    }
    slots_[head_] = {type, mtu, reason, restartOk};
    head_ = next;
    portEXIT_CRITICAL(&mux_);
    return true;
  }

  bool pop(ServerEvent* out) {
    portENTER_CRITICAL(&mux_);
    if (head_ == tail_) {
      portEXIT_CRITICAL(&mux_);
      return false;
    }
    *out = slots_[tail_];
    tail_ = (tail_ + 1) % kServerEventSlots;
    portEXIT_CRITICAL(&mux_);
    return true;
  }

  uint32_t drainOverflowCount() {
    portENTER_CRITICAL(&mux_);
    const uint32_t c = overflowCount_;
    overflowCount_ = 0;
    portEXIT_CRITICAL(&mux_);
    return c;
  }

 private:
  ServerEvent slots_[kServerEventSlots];
  volatile uint16_t head_ = 0;
  volatile uint16_t tail_ = 0;
  uint32_t overflowCount_ = 0;
  portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;
};

static ServerEventRing serverEventRing;

class SidecarRxCallbacks : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo& connInfo) override {
    const NimBLEAttValue& v = ch->getValue();
    const uint8_t* p = v.data();
    const uint16_t n = v.length();
    ringBuffer.push(p, n, currentConnectionGeneration());
  }
};

class SidecarServerCallbacks : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    advanceConnectionGeneration();
    // Defer logging to the loop task; just capture the MTU.
    serverEventRing.push(ServerEventType::kConnect, connInfo.getMTU(), 0, false);
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    advanceConnectionGeneration();
    // Defer stack work and logging to poll(); the host callback only records the event.
    serverEventRing.push(ServerEventType::kDisconnect, 0, reason, false);
  }

  void onMTUChange(uint16_t MTU, NimBLEConnInfo& connInfo) override {
    // Runs on the NimBLE host task after the ATT MTU exchange completes.
    // This is the only place the negotiated MTU is reported; defer the
    // actual logging to the loop task via the event ring.
    serverEventRing.push(ServerEventType::kMtuChange, MTU, 0, false);
  }
};

static bool linkStarted_ = false;
static bool linkFailed_ = false;
static bool screenOwned_ = false;
static volatile bool activitySinceLastPoll_ = false;
static NimBLEServer* server_ = nullptr;
static NimBLECharacteristic* rxChar_ = nullptr;
static NimBLECharacteristic* infoChar_ = nullptr;
static NimBLECharacteristic* statusChar_ = nullptr;
static NimBLECharacteristic* inventoryChar_ = nullptr;
static uint32_t completionCounter_ = 0;
static uint32_t parserGeneration_ = 0;
static bool completionNotifyPending_ = false;
static uint32_t nextNotifyAttemptMs_ = 0;
static uint8_t completionNotifyAttempts_ = 0;
static uint32_t nextLocalPageTurnMs_ = 0;
static uint32_t nextAdvertisingCheckMs_ = 0;
static uint32_t localPageDwellMs_ = 30000;  // Legacy phones retain their established cadence.
static bool cadenceLoaded_ = false;
static bool pendingCadence_ = false;
static uint8_t completedCycles_ = 0;
static uint32_t pendingCadenceMs_ = 30000;

void loadCadence() {
  if (cadenceLoaded_) return;
  cadenceLoaded_ = true;
  Preferences prefs;
  if (prefs.begin("sidecar", true)) {
    const uint32_t saved = prefs.getUInt("pageDwell", 30000);
    if (saved == 0 || (saved >= 1000 && saved <= 300000)) localPageDwellMs_ = saved;
    prefs.end();
  }
}

void scheduleLocalPage() {
  loadCadence();
  nextLocalPageTurnMs_ = localPageDwellMs_ == 0 ? 0 : millis() + localPageDwellMs_;
}
constexpr uint32_t kAdvertisingCheckMs = 10000;

static SidecarFrameProtocol& frameProtocol() {
  static SidecarFrameProtocol instance(sidecarSinkInstance());
  return instance;
}
static sidecar::v5::Transfer& collectionTransfer() {
  SidecarSink* sink = sidecarSinkInstance();
  static sidecar::v5::Transfer instance(sink ? sink->width() : 0, sink ? sink->height() : 0);
  return instance;
}

void setStatusValue(uint8_t state) {
  if (statusChar_ == nullptr) return;
  uint8_t value[5] = {
      state,
      static_cast<uint8_t>(completionCounter_ & 0xff),
      static_cast<uint8_t>((completionCounter_ >> 8) & 0xff),
      static_cast<uint8_t>((completionCounter_ >> 16) & 0xff),
      static_cast<uint8_t>((completionCounter_ >> 24) & 0xff),
  };
  statusChar_->setValue(value, sizeof(value));
}

bool buildInventoryValue(uint8_t* value, size_t capacity, size_t& length, bool& hasRoot) {
  sidecar::v5::Store store;
  sidecar::v5::RootIndex root{};
  sidecar::v5::CollectionManifest manifests[sidecar::v5::kMaxCollections]{};
  sidecar::v5::Selection selection{};
  sidecar::v5::SessionJournal session{};
  hasRoot = store.loadNewestRoot(root);
  if (hasRoot) {
    for (uint8_t i = 0; i < root.entryCount; ++i) {
      if (!store.loadCollectionManifest(root.entries[i].collectionId,
                                        root.entries[i].expectedGeneration, manifests[i])) {
        hasRoot = false;
        break;
      }
    }
  }
  const bool hasSelection = hasRoot && store.loadSelection(selection);
  const bool hasSession = store.loadNewestSession(session);
  return sidecar::v5::encodeInventory(hasRoot ? &root : nullptr,
                                     hasRoot ? manifests : nullptr,
                                     hasSelection ? &selection : nullptr,
                                     hasSession ? &session : nullptr,
                                     value, capacity, length);
}

}  // namespace

extern "C" void sidecarLinkBegin() {
  if (linkStarted_ || linkFailed_) {
    return;
  }

  const uint32_t heapBefore = ESP.getFreeHeap();

  if (sidecarSinkInstance() == nullptr) {
    LOG_ERR("SIDECAR", "display sink unavailable; BLE link not started");
    linkFailed_ = true;
    return;
  }

  NimBLEDevice::init(kDeviceName);

  // Ask for the biggest ATT MTU the stack will offer. The central still decides - this only
  // sets what we advertise as acceptable. Measured 256 on 2026-08-13 and 23 on 2026-08-21 with
  // no request at all, which is why stage 3 cannot size its chunks until this is deliberate.
  constexpr uint16_t kPreferredMtu = 517;
  const bool mtuSet = NimBLEDevice::setMTU(kPreferredMtu);
  LOG_INF("SIDECAR", "preferred mtu request %u accepted=%d",
          static_cast<unsigned>(kPreferredMtu), mtuSet ? 1 : 0);

  server_ = NimBLEDevice::createServer();
  if (!server_) {
    LOG_ERR("SIDECAR", "createServer failed");
    linkFailed_ = true;
    return;
  }
  server_->setCallbacks(new SidecarServerCallbacks());

  NimBLEService* service = server_->createService(kServiceUuid);
  if (!service) {
    LOG_ERR("SIDECAR", "createService failed");
    linkFailed_ = true;
    return;
  }

  rxChar_ = service->createCharacteristic(
      kRxCharUuid,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  if (!rxChar_) {
    LOG_ERR("SIDECAR", "createCharacteristic failed");
    linkFailed_ = true;
    return;
  }
  rxChar_->setCallbacks(new SidecarRxCallbacks());

  // INFO characteristic: publishes the slate geometry so the phone renders at the right size.
  // Set once from the sink; reads run on the host task so the value must already be there.
  infoChar_ = service->createCharacteristic(kInfoCharUuid, NIMBLE_PROPERTY::READ);
  if (!infoChar_) {
    LOG_ERR("SIDECAR", "createCharacteristic (info) failed");
    linkFailed_ = true;
    return;
  }

  SidecarSink* sink = sidecarSinkInstance();
  const uint16_t w = sink ? sink->width() : 0;
  const uint16_t h = sink ? sink->height() : 0;
  const uint16_t maxChunk = kMaxPayload - 3;  // DATA opcode + u16 sequence header

  uint8_t buf[18];
  buf[0] = static_cast<uint8_t>(w & 0xFF);
  buf[1] = static_cast<uint8_t>((w >> 8) & 0xFF);
  buf[2] = static_cast<uint8_t>(h & 0xFF);
  buf[3] = static_cast<uint8_t>((h >> 8) & 0xFF);
  buf[4] = 1;      // bpp
  buf[5] = 0;      // rotation
  buf[6] = static_cast<uint8_t>(maxChunk & 0xFF);
  buf[7] = static_cast<uint8_t>((maxChunk >> 8) & 0xFF);
  buf[8] = 5;      // protocolVer low: read-only collection discovery plus v4 writes
  buf[9] = 0;      // protocolVer high
  buf[10] = 63;  // cadence, deferred handoff, grouped updates, collections, DEFLATE, and 1-bit PNG
  buf[11] = SidecarSink::kMaxStoredPages;
  buf[12] = SidecarSink::kMaxGroupPages;  // Keep replacement inside measured BLE connection window.
  buf[13] = sidecar::v5::kMaxCollections;
  buf[14] = sidecar::v5::kMaxPagesPerCollection;
  buf[15] = static_cast<uint8_t>(sidecar::v5::kMaxPublishedPages & 0xff);
  buf[16] = static_cast<uint8_t>(sidecar::v5::kMaxPublishedPages >> 8);
  buf[17] = sidecar::v5::kMaxNameBytes;
  infoChar_->setValue(buf, sizeof(buf));

  inventoryChar_ = service->createCharacteristic(kInventoryCharUuid, NIMBLE_PROPERTY::READ);
  if (!inventoryChar_) {
    LOG_ERR("SIDECAR", "createCharacteristic (inventory) failed");
    linkFailed_ = true;
    return;
  }
  uint8_t inventory[sidecar::v5::kInventoryMaxSize];
  size_t inventoryLength = 0;
  bool hasV5Root = false;
  if (!buildInventoryValue(inventory, sizeof(inventory), inventoryLength, hasV5Root)) {
    LOG_ERR("SIDECAR", "inventory snapshot failed");
    linkFailed_ = true;
    return;
  }
  inventoryChar_->setValue(inventory, inventoryLength);

  statusChar_ = service->createCharacteristic(
      kStatusCharUuid,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  if (!statusChar_) {
    LOG_ERR("SIDECAR", "createCharacteristic (status) failed");
    linkFailed_ = true;
    return;
  }
  setStatusValue(0);

  // NimBLE-Arduino 2.x registers all configured services when the server starts.
  if (!server_->start()) {
    LOG_ERR("SIDECAR", "GATT server start failed");
    linkFailed_ = true;
    return;
  }

  LOG_INF("SIDECAR", "info w=%u h=%u bpp=1 maxChunk=%u ver=5 maxPages=%u groupPages=%u v5Root=%u",
          static_cast<unsigned>(w),
          static_cast<unsigned>(h),
          static_cast<unsigned>(maxChunk),
          static_cast<unsigned>(SidecarSink::kMaxStoredPages),
          static_cast<unsigned>(SidecarSink::kMaxGroupPages), hasV5Root ? 1u : 0u);

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  if (!adv) {
    LOG_ERR("SIDECAR", "getAdvertising failed");
    linkFailed_ = true;
    return;
  }

  // The 128-bit service UUID must stay in the primary advert so the phone's
  // service-filtered scan can match it.
  if (!adv->addServiceUUID(kServiceUuid)) {
    LOG_ERR("SIDECAR", "addServiceUUID failed");
    linkFailed_ = true;
    return;
  }

  // The legacy advert is only 31 bytes and cannot hold both the UUID and the
  // name. Put the name in the scan response instead; Android still reports it.
  adv->enableScanResponse(true);

  NimBLEAdvertisementData scanResponse;
  if (!scanResponse.setName(kDeviceName)) {
    LOG_ERR("SIDECAR", "setName failed");
    linkFailed_ = true;
    return;
  }
  if (!adv->setScanResponseData(scanResponse)) {
    LOG_ERR("SIDECAR", "setScanResponseData failed");
    linkFailed_ = true;
    return;
  }

  if (!adv->start()) {
    LOG_ERR("SIDECAR", "advertising start failed");
    linkFailed_ = true;
    return;
  }

  // Hold the CPU at its normal frequency for as long as the link is up.
  // Without this, CrossPoint drops to 10 MHz after 3 s idle and the BLE
  // controller stops advertising (measured: advert disappears at ~6.7 s).
  // Lock is non-copyable/non-movable, so a function-local static is the only
  // shape that outlives this function.
  static HalPowerManager::Lock sidecarPowerLock;
  LOG_INF("SIDECAR", "power-saving lock held");

  linkStarted_ = true;
  const uint32_t heapAfter = ESP.getFreeHeap();
  LOG_INF("SIDECAR",
          "advertising started name=EinkSidecar uuid=%s heapBefore=%u heapAfter=%u",
          kServiceUuid,
          static_cast<unsigned>(heapBefore),
          static_cast<unsigned>(heapAfter));
}

extern "C" void sidecarLinkPoll() {
  if (!linkStarted_) {
    return;
  }

  if (static_cast<int32_t>(millis() - nextAdvertisingCheckMs_) >= 0) {
    nextAdvertisingCheckMs_ = millis() + kAdvertisingCheckMs;
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    if (server_ != nullptr && server_->getConnectedCount() == 0 && advertising != nullptr &&
        !advertising->isAdvertising()) {
      const bool restarted = advertising->start();
      LOG_INF("SIDECAR", "advertising watchdog restart accepted=%d", restarted ? 1 : 0);
    }
  }

  const uint32_t dropped = ringBuffer.drainOverflowCount();
  if (dropped > 0) {
    LOG_INF("SIDECAR", "dropped %u writes", static_cast<unsigned>(dropped));
    ringBuffer.clear();
    frameProtocol().reset();
    collectionTransfer().onDisconnect();
    completionNotifyPending_ = false;
    completionNotifyAttempts_ = 0;
  }

  const uint32_t droppedEvents = serverEventRing.drainOverflowCount();
  if (droppedEvents > 0) {
    LOG_INF("SIDECAR", "dropped %u connection events", static_cast<unsigned>(droppedEvents));
    ringBuffer.clear();
    frameProtocol().reset();
    collectionTransfer().onDisconnect();
    completionNotifyPending_ = false;
    completionNotifyAttempts_ = 0;
    // Reconcile the observable state directly with NimBLE if the callback queue saturated.
    // Connection generation already advances in the callback even when its event cannot queue.
    setStatusValue(0);
    pendingCadence_ = false;
    activitySinceLastPoll_ = false;
    // The stored catalog remains valid. Re-establish local ownership/timing from that durable
    // state, independent of which connect/disconnect event was lost.
    SidecarSink* overflowSink = sidecarSinkInstance();
    screenOwned_ = overflowSink != nullptr && overflowSink->hasStoredPages();
    if (screenOwned_) scheduleLocalPage();
    else nextLocalPageTurnMs_ = 0;
    // A disconnected server must resume advertising even if its event was the one lost.
    if (server_ != nullptr && server_->getConnectedCount() == 0) {
      if (!NimBLEDevice::startAdvertising()) {
        LOG_ERR("SIDECAR", "advertising recovery after event overflow failed");
      }
    }
  }

  ServerEvent evt;
  while (serverEventRing.pop(&evt)) {
    if (evt.type == ServerEventType::kConnect) {
      setStatusValue(0);
      const uint16_t mtu = evt.mtu;
      char line[256];
      std::snprintf(line, sizeof(line),
                    "central connected mtu=%u (pre-negotiation default; see mtu negotiated=)",
                    static_cast<unsigned>(mtu));
      LOG_INF("SIDECAR", "%s", line);
    } else if (evt.type == ServerEventType::kMtuChange) {
      const uint16_t mtu = evt.mtu;
      const uint16_t preferred = NimBLEDevice::getMTU();
      const uint16_t usable = (mtu > 3) ? (mtu - 3) : 0;
      char line[256];
      std::snprintf(line, sizeof(line),
                    "mtu negotiated=%u preferred=%u usable=%u",
                    static_cast<unsigned>(mtu),
                    static_cast<unsigned>(preferred),
                    static_cast<unsigned>(usable));
      LOG_INF("SIDECAR", "%s", line);
    } else {
      LOG_INF("SIDECAR", "central disconnected reason=%d", evt.reason);
      setStatusValue(0);
      // Drop every queued write at the connection boundary. A newly connected phone may need
      // to retry, but its bytes can never complete the disconnected client's partial transfer.
      ringBuffer.clear();
      frameProtocol().reset();
      collectionTransfer().onDisconnect();
      completionNotifyPending_ = false;
      completionNotifyAttempts_ = 0;
      const bool restarted = NimBLEDevice::startAdvertising();
      if (restarted) {
        LOG_INF("SIDECAR", "advertising restarted");
      } else {
        LOG_ERR("SIDECAR", "advertising restart failed");
      }
    }
  }

  const uint32_t activeGeneration = currentConnectionGeneration();
  if (activeGeneration != parserGeneration_) {
    frameProtocol().reset();
    collectionTransfer().onDisconnect();
    parserGeneration_ = activeGeneration;
    pendingCadence_ = false;
  }

  auto finishPresentation = []() {
      if (pendingCadence_) {
        loadCadence();
        if (localPageDwellMs_ != pendingCadenceMs_) {
          Preferences prefs;
          if (prefs.begin("sidecar", false)) {
            if (prefs.putUInt("pageDwell", pendingCadenceMs_) != sizeof(uint32_t))
              LOG_ERR("SIDECAR", "page cadence persistence failed");
            prefs.end();
          } else LOG_ERR("SIDECAR", "page cadence storage unavailable");
        }
        localPageDwellMs_ = pendingCadenceMs_;
        pendingCadence_ = false;
        LOG_INF("SIDECAR", "page dwell applied ms=%lu", static_cast<unsigned long>(localPageDwellMs_));
      }
      ++completionCounter_;
      setStatusValue(1);
      completionNotifyPending_ = true;
      nextNotifyAttemptMs_ = 0;
      completionNotifyAttempts_ = 0;
      screenOwned_ = true;
      activitySinceLastPoll_ = true;
      completedCycles_ = 0;
      scheduleLocalPage();
  };

  RingItem item;
  while (ringBuffer.pop(&item)) {
    if (item.generation != activeGeneration) continue;
    if (item.truncated) {
      LOG_INF("SIDECAR",
              "write truncated from %u to %u bytes",
              static_cast<unsigned>(item.originalLen),
              static_cast<unsigned>(item.len));
      frameProtocol().reset();
      continue;
    }
    if (item.len > 0 && item.data[0] == 0x07) {
      SidecarSink* sink = sidecarSinkInstance();
      if (sink != nullptr && item.len == 2 && item.data[1] == 2 && !frameProtocol().inTransfer()) {
        sink->deferBundleUntilCycle(true);
      } else {
        frameProtocol().reset();
        pendingCadence_ = false;
        LOG_ERR("SIDECAR", "invalid background bundle control");
      }
      continue;
    }
    if (item.len > 0 && item.data[0] == 0x06) {
      // A dedicated ATT control packet, never part of the streaming frame parser.
      if (item.len != 5 || frameProtocol().inTransfer()) {
        frameProtocol().reset();
        pendingCadence_ = false;
        LOG_ERR("SIDECAR", "invalid page cadence control");
        continue;
      }
      const uint32_t ms = static_cast<uint32_t>(item.data[1]) |
          (static_cast<uint32_t>(item.data[2]) << 8) |
          (static_cast<uint32_t>(item.data[3]) << 16) |
          (static_cast<uint32_t>(item.data[4]) << 24);
      pendingCadence_ = ms == 0 || (ms >= 1000 && ms <= 300000);
      if (pendingCadence_) pendingCadenceMs_ = ms;
      else {
        frameProtocol().reset();
        LOG_ERR("SIDECAR", "page cadence out of range");
      }
      continue;
    }
    if (item.len > 0 && collectionTransfer().handles(item.data[0])) {
      if (frameProtocol().inTransfer() || !collectionTransfer().onPacket(item.data, item.len)) {
        LOG_ERR("SIDECAR", "v5 collection packet rejected opcode=0x%02X", item.data[0]);
      } else {
        activitySinceLastPoll_ = true;
        // DATA and BEGIN_PAGE do not change the durable inventory. Rebuilding it here performs
        // several FAT reads for every bitmap chunk, stalls the loop, and can overflow the BLE
        // receive ring. Refresh only after operations that change observable session/root state.
        const uint8_t opcode = item.data[0];
        if (opcode == 0x03 || opcode == 0x0B || opcode == 0x0C || opcode == 0x0D || opcode == 0x0E) {
          uint8_t inventory[sidecar::v5::kInventoryMaxSize]; size_t inventoryLength = 0; bool hasRoot = false;
          if (inventoryChar_ != nullptr &&
              buildInventoryValue(inventory, sizeof(inventory), inventoryLength, hasRoot))
            inventoryChar_->setValue(inventory, inventoryLength);
        }
        if (opcode == 0x0C) {
          SidecarSink* sink = sidecarSinkInstance();
          if (sink != nullptr && sink->showStoredPage(0)) {
            screenOwned_ = true;
            completedCycles_ = 0;
            scheduleLocalPage();
            LOG_INF("SIDECAR", "v5 commit restored screen ownership");
          } else {
            LOG_ERR("SIDECAR", "v5 commit could not restore screen ownership");
          }
        }
      }
      continue;
    }
    frameProtocol().onWrite(item.data, item.len);
    const bool complete = frameProtocol().consumeFrameComplete();
    if (complete) {
      SidecarSink* sink = sidecarSinkInstance();
      if (sink == nullptr || !sink->hasPendingBundle()) finishPresentation();
    } else if (frameProtocol().inTransfer()) {
      // Keep the device awake only for a packet that advanced a valid in-progress transfer.
      activitySinceLastPoll_ = true;
    }
  }

  if (sidecarSinkInstance() != nullptr && sidecarSinkInstance()->hasPendingBundle())
    activitySinceLastPoll_ = true;

  if (completionNotifyPending_ &&
      (nextNotifyAttemptMs_ == 0 || static_cast<int32_t>(millis() - nextNotifyAttemptMs_) >= 0)) {
    const bool notified = statusChar_->notify();
    if (notified || completionNotifyAttempts_ == 0) {
      LOG_INF("SIDECAR", "frame completion notify accepted=%d", notified ? 1 : 0);
    }
    if (notified) {
      completionNotifyPending_ = false;
    } else if (++completionNotifyAttempts_ >= 8) {
      // The panel commit succeeded, but delivery did not. Publish a terminal failure state and
      // let the phone time out/retry instead of leaving a readable success value behind.
      setStatusValue(2);
      completionNotifyPending_ = false;
      LOG_ERR("SIDECAR", "frame completion notification failed after 8 attempts");
    } else {
      nextNotifyAttemptMs_ = millis() + 250;
    }
  }

  if (screenOwned_ && nextLocalPageTurnMs_ != 0 &&
      static_cast<int32_t>(millis() - nextLocalPageTurnMs_) >= 0 &&
      !collectionTransfer().inTransfer() &&
      (server_ == nullptr || server_->getConnectedCount() == 0 ||
       !frameProtocol().inTransfer() ||
       (sidecarSinkInstance() != nullptr && sidecarSinkInstance()->backgroundReceptionSafe()))) {
    SidecarSink* sink = sidecarSinkInstance();
    const bool boundary = sink != nullptr && sink->atLastStoredPage();
    if (boundary && completedCycles_ >= 2 && sink->hasPendingBundle()) {
      if (sink->activatePendingBundle()) {
        LOG_INF("SIDECAR", "background handoff after two or more cycles");
        finishPresentation();
      } else {
        LOG_ERR("SIDECAR", "background handoff failed; retaining previous bundle");
        frameProtocol().reset();
        pendingCadence_ = false;
        setStatusValue(2);
        scheduleLocalPage();
      }
    } else if (sink != nullptr && sink->showStoredPage(1)) {
      if (boundary && completedCycles_ < 2) ++completedCycles_;
      scheduleLocalPage();
    } else {
      nextLocalPageTurnMs_ = 0;
    }
  }
}

extern "C" bool sidecarLinkOwnsScreen() {
  return screenOwned_;
}

extern "C" bool sidecarLinkConsumeActivity() {
  const bool had = activitySinceLastPoll_;
  activitySinceLastPoll_ = false;
  return had;
}

extern "C" bool sidecarLinkHandleButtons(void* inputPtr) {
  if (!screenOwned_ || inputPtr == nullptr) return false;
  auto* input = static_cast<MappedInputManager*>(inputPtr);
  int8_t delta = 0;
  if (input->wasPressed(MappedInputManager::Button::Up)) delta = -1;
  else if (input->wasPressed(MappedInputManager::Button::Down)) delta = 1;
  if (delta == 0) return false;
  SidecarSink* sink = sidecarSinkInstance();
  if (sink == nullptr) { screenOwned_ = false; return false; }
  const bool shown = sink->showStoredPage(delta);
  if (shown) {
    activitySinceLastPoll_ = true;
    scheduleLocalPage();
  }
  else screenOwned_ = false;
  return shown;
}

extern "C" void sidecarLinkResumeScreenOwnership() {
  SidecarSink* sink = sidecarSinkInstance();
  if (sink != nullptr && sink->hasStoredPages()) {
    screenOwned_ = true;
    scheduleLocalPage();
  }
}

extern "C" bool sidecarLinkRestoreStoredPage() {
  SidecarSink* sink = sidecarSinkInstance();
  if (sink == nullptr || !sink->showStoredPage(0)) return false;
  screenOwned_ = true;
  activitySinceLastPoll_ = true;
  scheduleLocalPage();
  return true;
}

extern "C" bool sidecarLinkAdvanceStoredPage() {
  loadCadence();
  if (localPageDwellMs_ == 0) return false;  // Fixed recipes remain pinned on timer wake too.
  SidecarSink* sink = sidecarSinkInstance();
  if (sink == nullptr || !sink->showStoredPage(1)) return false;
  screenOwned_ = true;
  scheduleLocalPage();
  return true;
}

#endif  // SIDECAR_LINK
