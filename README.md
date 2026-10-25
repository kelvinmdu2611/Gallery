# Super Gallery

A private, offline photo & video library for Android tablets, in the spirit of the iPhone Photos app.

- **Library** — Years, Months and All Photos, pinch to resize the grid
- **Viewer** — swipe, pinch/double-tap zoom, swipe down to close, filmstrip, slideshow, info
- **Albums** — your own albums plus Favorites, Videos, Screenshots, Recently Added, On This Day
- **Recently Deleted** — 30-day safety net (originals on the device are never touched)
- **Search** — by month, year, file name or album

**Android app:** shows every photo and video on the tablet automatically, straight from the system photo library — nothing is copied. Folders (Camera, Screenshots, WhatsApp…) appear under *On This Tablet*, deleting uses the tablet's own 30-day trash, and it registers as the device gallery so “Open with” and the camera's last-photo button open in Super Gallery.

**Web version:** photos are imported into the browser (IndexedDB). Nothing is uploaded.

## Get it

- **Android app:** download `SuperGallery.apk` from the [latest release](../../releases/latest) and open it on the tablet.
- **Web:** open the GitHub Pages site in Chrome and choose *Install app*.

## Project layout

```
docs/      the web app (index.html is the whole app) — also served by GitHub Pages
android/   native shell: WebView + file/folder picking, share, save to gallery
.github/   workflow that builds the APK and publishes it as a release on every push
```
