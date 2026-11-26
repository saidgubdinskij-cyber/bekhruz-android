# bekhruz

Black-and-neon Android app interface starter.

## Included
- Android project structure
- Black interface with neon-green accent
- WireGuard / AmneziaWG protocol selector UI
- Configuration file picker
- GitHub Actions workflow to build a debug APK

## Important
This is an interface starter only. It does not establish a VPN connection yet. Real WireGuard and AmneziaWG tunnel engines, Android VPN permission handling, secure configuration parsing, and device testing are still required before a VPN connection can work.

## Build
Open the Actions tab and run **Build bekhruz APK**. When the workflow succeeds, download the **bekhruz-debug-apk** artifact.


## Cloudflare WARP profile generation

The app can register a device with Cloudflare's consumer WARP registration API and save a WireGuard-format profile locally. The registration routing ID (the three reserved bytes) is stored with the profile.

**Important:** the current embedded WireGuard GoBackend does not inject/strip Cloudflare's reserved routing bytes in UDP packets. Therefore generated WARP profiles are saved but WARP connection is deliberately blocked until a WARP-compatible packet transport is integrated. Ordinary WireGuard profiles continue to use the standard backend.
