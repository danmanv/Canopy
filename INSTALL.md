# How to install Canopy

Canopy runs on Android TV / Google TV, Android phones, iPhone and iPad. Before you start, set up your Stremio account and add-ons on a phone or computer at [web.stremio.com](https://web.stremio.com). Canopy picks them up when you sign in.

- [Android TV / Google TV](#android-tv--google-tv)
- [Android phone](#android-phone)
- [iPhone and iPad](#iphone-and-ipad)

## Android TV / Google TV

1. Install **Downloader** (by AFTVnews) from the Play Store.
2. Let Downloader install apps: **Settings › Apps › Security & restrictions › Unknown sources › Downloader**.
   If that menu is missing, first go to **Settings › System › About**, select **Android TV OS build** 7 times, then use **Developer options › Install unknown apps**.
3. Open Downloader, type **7470748**, press **Go** and install. The code always gets the latest release.
4. Open Canopy, name your profile and sign in to Stremio with the code on screen.

**Updates:** Canopy offers each new release itself (or Settings › About › Updates). If the install fails, for example on a Chromecast with Google TV, run Downloader with the same code again.

## Android phone

1. Open <https://github.com/danmanv/Canopy/releases/latest/download/Canopy.apk> in Chrome. Downloader with code **7470748** also works.
2. If Chrome warns, tap **Download anyway**. Then tap the file, allow **Install from this source**, and if Play Protect warns, tap **More details › Install anyway**.
3. Open Canopy and sign in with Stremio. It updates itself from each release.

A phone needs Canopy 1.3.3 or later. On 1.3.2 or earlier a phone shows the TV screens and cannot get past **Welcome**: open the link above again to install the latest release over it.

## iPhone and iPad

Each device needs the Mac once. After that it updates over Wi-Fi.

1. Plug the device into the Mac, unlock it, and tap **Trust**.
2. Turn on **Settings › Privacy & Security › Developer Mode**. Restart, then tap **Turn On**.
3. On the Mac, open **iloader**, sign in with the device owner's Apple ID, pick the device, and choose **Install SideStore**.
4. On the device, go to **Settings › General › VPN & Device Management**, tap the Apple ID, and tap **Trust**.
5. Install **LocalDevVPN** from the App Store and turn it on.
6. Open SideStore and sign in with the same Apple ID. Go to **Sources**, tap **+**, and add:
   ```
   https://github.com/danmanv/Canopy/releases/download/ios-nightly/source.json
   ```
   Open the Canopy source and tap **Get**.
7. Open Canopy and sign in with Stremio.

**Updates and renewal:** new builds and the 7-day app renewal happen over Wi-Fi in SideStore. Keep LocalDevVPN on and open SideStore every few days, or Canopy stops opening until it is renewed.

## Developers

To install a test build over ADB, see [Install over ADB](README.md#install-over-adb) in the README.
