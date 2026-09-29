# Experimental release 0.1.0

This is the first public hardware-proven Sidecar snapshot.

Highlights:

- Android 14+ dashboard with per-pane On/Off and Customize controls
- Independent multi-page collections with atomic publication
- Raw, PNG and raw-DEFLATE transfer formats
- Resumable BLE delivery that retransmits only missing pages
- Local X3 rotation while the phone updates another collection
- Weather, news, sports, football leaders, markets, astronomy, email notifications, calendar, travel,
  package notifications, sticky cards, tasks, groceries and fixed recipe pages
- Physical-X3 validation and an independent firmware readback hash

Known limits:

- Tested on one X3 and one Samsung Android phone
- Debug-signed APK; no Play Store distribution or automatic update path
- Experimental custom firmware with no vendor support
- Two-minute X3 wake cadence and approximately 20-second receive window are compile-time firmware values
- Flight status requires a user-supplied Aviationstack API key
- Public feed schemas can change and temporarily break individual panes
