# ZentraIntro 2.0

Intro sinematik untuk **ZentraSMP**: begitu pemain join dan loading selesai, pemain langsung
dipindah ke lobby (`/spawn`), kamera terbang mengelilingi lobby dengan judul, bossbar loading,
timelapse malam ke siang, partikel sihir dan kembang api. Di akhir layar **menggelap perlahan**,
lalu **terang perlahan** dan pemain bisa bermain.

- Server: **Purpur / Paper 1.21+** (kode tidak memakai NMS atau versi tertentu, `api-version: 1.21`)
- Bedrock: **Geyser + Floodgate** didukung (otomatis terdeteksi)
- Tanpa resource pack, tanpa dependency wajib

## Cara membuat file .jar

Pilih salah satu:

1. **Tanpa install apa pun (GitHub Actions)**
   Buat repo GitHub, upload semua isi folder ini, buka tab **Actions** -> **Build ZentraIntro**,
   lalu unduh artifact `ZentraIntro` (berisi `ZentraIntro.jar`).
2. **Maven** (Java 21 + Maven terpasang): jalankan `mvn package`, hasilnya `target/ZentraIntro.jar`.
3. **IntelliJ IDEA**: buka folder ini sebagai project Maven, lalu Build -> `package`.

## Instalasi

1. Taruh `ZentraIntro.jar` di folder `plugins/`.
2. Pastikan ada plugin `/spawn` (EssentialsX + EssentialsSpawn) dan lobby sudah di-`/setspawn`.
   Kalau tidak ada, plugin otomatis memakai spawn world (lihat `spawn.fallback-to-world-spawn`).
3. Restart server. Edit `plugins/ZentraIntro/config.yml` lalu `/zentraintro reload`.

## Perintah dan permission

| Perintah | Fungsi |
|---|---|
| `/zentraintro play [pemain]` | Jalankan intro (untuk tes tanpa relog) |
| `/zentraintro stop [pemain]` | Hentikan intro dan kembalikan pemain |
| `/zentraintro reload` | Muat ulang config |

- `zentraintro.admin` (default op): memakai perintah di atas.
- `zentraintro.bypass`: melewati intro saat join. Staff yang punya permission `*` juga ikut melewati.

## Yang dilakukan plugin demi keamanan pemain

- Kebal sejak detik pertama join (aman dari lava/void/mob selama loading).
- Selama intro: tidak bisa bergerak, tidak bisa memakai perintah, tidak bisa lewat portal, tidak terlihat pemain lain.
- Di akhir (atau jika pemain keluar/ada error): mode game, terbang, kebal, collision, waktu, dan efek
  dikembalikan seperti semula, pemain kembali ke titik spawn.
- Jika ada error saat intro, intro dihentikan dan pemain dipulihkan (tidak akan stuck).

## Menyesuaikan dengan lobby kamu

- `camera.orbit-distance`: jarak dari titik spawn ke pusat lobby (searah hadap spawn).
- `camera.keyframes`: jalur kamera. Kolom: `tick, radius, sudut, tinggi mata, tinggi tatapan`.
  Kamera tidak punya tabrakan, jadi kalau menembus bangunan, perbesar radius atau tinggi.
  Keyframe terakhir menatap langit (tinggi tatapan besar) karena itulah yang membuat layar bisa gelap penuh.
- `sequence.steps`: teks judul dan durasinya (20 tick = 1 detik).
- `effects.*`: matikan timelapse, kembang api, partikel, atau fade bila perlu.

## Catatan Bedrock (Geyser)

- Kamera memakai teleport tiap tick. Jika terasa patah di Bedrock, set `camera.bedrock-update-interval: 2`.
- Pemain Bedrock diberi jeda join tambahan (`join.bedrock-extra-delay-ticks`) karena loading lebih lama.

## Soal efek gelap

Fade ke hitam memakai efek vanilla (Darkness lalu Blindness) sambil kamera menatap langit, sehingga
tidak butuh resource pack. Tampilannya bisa sedikit berbeda antara Java dan Bedrock. Jika terasa
kurang pas, matikan `effects.darkness-effect`.

## Status pengujian

Kode sudah dikompilasi bersih terhadap stub API untuk memeriksa sintaks dan tipe, tetapi belum
dijalankan di server Purpur/Paper sungguhan (terutama 26.x). Setelah dipasang, tes dengan
`/zentraintro play <nama>`. Jika ada pesan error di console, kirim log-nya agar bisa diperbaiki.
