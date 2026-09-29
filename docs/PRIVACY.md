# Privacy and network behavior

The Sidecar has no GatorForge-hosted backend. Page rendering, recipe-photo OCR, collection assembly and
BLE delivery run locally on the Android phone.

Modules that fetch public data connect directly from the phone to their configured or built-in public
providers. Flight status requires an Aviationstack API key supplied by the user and stored in the app's
private preferences. Do not publish an APK or configuration export containing a real key.

Notification access is optional. When enabled, Android exposes visible notification fields to the app.
The Sidecar filters supported email and delivery applications to build recent-email and package panes;
it does not authenticate to email, Amazon, UPS, FedEx, USPS or Shop accounts. Calendar access is also
optional and reads events through Android's calendar provider.

Rendered content is stored on the X3 so it can continue paging without the phone. Anyone with physical
access to the device should be assumed able to see those pages. Avoid displaying sensitive email,
calendar, travel or delivery details on an unattended Sidecar.
