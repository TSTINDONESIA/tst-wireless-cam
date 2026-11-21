# TST Wireless Cam — Professional Control UI

Aplikasi Android kamera wireless untuk mengirim video + audio langsung ke **vMix lewat SRT** pada jaringan Wi-Fi lokal.

## Update UI
- Dark premium broadcast-style dashboard.
- Ikon kontrol untuk Exposure, Image, Focus, PTZ, dan Network.
- Status LIVE, bitrate, resolusi, FPS, baterai, suhu, IP HP, dan tujuan vMix.
- Bottom control deck: START, STOP, RECONNECT, SETTINGS, FLIP, LIGHT.
- Zoom slider.
- Settings drawer untuk IP vMix, port SRT, resolusi, FPS, latency/quality, auto reconnect dan auto start.

## Manual Camera Control
Panel **Exposure / Camera** menyediakan:
- Manual / Auto Exposure.
- Exposure Compensation.
- Gain / ISO.
- Shutter Speed.
- Focus Distance / Auto Focus.
- Iris / Aperture control bila hardware Camera2 Android mendukung kontrol aperture manual.

Kontrol yang tidak didukung hardware kamera tidak akan mematikan stream; aplikasi tetap berjalan dengan kemampuan kamera yang tersedia.

## vMix
1. Di PC jalankan `ipconfig` dan ambil IPv4 Address.
2. vMix → Add Input → Stream / SRT → Listener.
3. Gunakan port, misalnya `9001`.
4. Di HP buka SETTINGS → masukkan IP PC dan port → SAVE & APPLY.
5. Tekan START CAMERA.

## Build
Workflow GitHub Actions pada `.github/workflows/build.yml` tetap dipertahankan. Upload project ke GitHub untuk menghasilkan APK dari workflow yang sudah ada.

## Catatan
FPS/resolusi tetap mengikuti kemampuan encoder dan kamera Android yang digunakan. Manual ISO, shutter, focus, exposure, dan aperture bergantung pada dukungan Camera2 masing-masing perangkat.
