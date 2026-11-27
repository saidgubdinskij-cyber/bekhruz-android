# VOLTIX

Android VPN application project.

## Current features in the project
- Dark interface with neon-green accents and Russian labels.
- WireGuard / AmneziaWG configuration import and saved local profiles.
- QR scan to import a configuration and QR export/share for saved profiles.
- Android Quick Settings tile for VPN on/off.
- Connection status and traffic-speed display.
- Cloudflare WARP profile registration and generation attempt.

## Important limitations
- The Cloudflare WARP configuration contains Cloudflare's Reserved routing bytes. A normal WireGuard transport may not route those bytes as Cloudflare expects, so WARP connectivity must be tested on a real device and may require a WARP-compatible transport.
- VPN behavior, especially the Quick Settings tile and disconnect traffic handling, must be tested on the target Android device. A successful APK build alone does not prove that the VPN connects or blocks traffic correctly.
- The application ID remains `com.bekhruz.vpn` to preserve compatibility with existing installs and saved app data; the displayed app name is VOLTIX.

## Build
Open GitHub Actions and run **Build VOLTIX APK**. Download the **voltix-debug-apk** artifact after the workflow completes successfully.
