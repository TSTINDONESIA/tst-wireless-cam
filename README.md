# TST Wireless Cam (Android)

Kamera HP Android yang mengirim video + suara langsung ke **vMix** lewat **SRT**
di jaringan Wi-Fi lokal (tanpa internet, tanpa aplikasi receiver, gratis).

## Cara pakai
1. Di PC: buka cmd, ketik `ipconfig`, catat **IPv4 Address**.
2. Di vMix: **Add Input > Stream / SRT**, mode **Listener**, port **9001** (kamera ke-2 pakai 9002, dst.).
3. Di HP: pasang APK, buka aplikasi, **SETTINGS** > isi IP PC dan port > **SIMPAN**.
4. Tekan **START CAMERA**. Gambar muncul di input vMix.

## Membuat APK
Setiap kali kode di-upload ke GitHub, tab **Actions** membuat APK otomatis.
Unduh dari hasil run terbaru di bagian **Artifacts** (`TST-Wireless-Cam-APK`).

## Catatan
- Versi 0.1: Android saja. FPS dan resolusi adalah nilai yang diatur, latency belum diukur.
- Setiap HP butuh port berbeda di vMix agar menjadi input terpisah.
