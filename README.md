# ANTENA — Yayın Konsolu

Xiaomi Mi Box 3rd Gen başta olmak üzere Android TV cihazları için, **geleneksel televizyon hissiyatı** veren ultra hafif IPTV uygulaması.

| | |
|---|---|
| Paket ID | `com.ilyemir.antena` |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |
| Dil | Kotlin |
| UI | Jetpack Compose for TV (`androidx.tv:tv-material`) |
| Oynatıcı | AndroidX Media3 / ExoPlayer (donanım öncelikli) |
| Depolama | Room (kanallar) + DataStore (ayarlar) |
| Panel | Ktor Server CIO, port 8080 (Faz 3) |

## Mimari

```
com.ilyemir.antena
├── MainActivity / AntenaViewModel      → tek Activity, Compose oynaması
├── data/
│   ├── M3uParser.kt                    → genişletilmiş M3U/M3U8 ayrıştırıcı
│   ├── db/Channels.kt                  → Room: kanal tablosu + DAO
│   ├── SettingsStore.kt                → DataStore: liste adresi, son kanal
│   └── ChannelRepository.kt            → indirme + numaralandırma + yedek adres sezgisi
├── player/PlaybackController.kt        → ExoPlayer motoru (freeze-frame, yedek URL, auto-reconnect)
└── ui/
    ├── theme/AntenaTheme.kt            → renk tokenları + tipografi
    ├── PlayerScreen.kt                 → tam ekran yayın + OSD + D-Pad yönlendirme
    ├── ChannelDrawer.kt                → sol kanal çekmecesi
    ├── NumPadBar.kt                    → yatay 0-9 rakam şeridi
    └── SetupScreen.kt                  → ilk kurulum (M3U adresi)
```

### Geleneksel TV davranışları

- **Doğrudan yayın:** Uygulama açılınca menü yok; en son izlenen kanal anında tam ekranda açılır.
- **Freeze-frame zap:** `PlayerView.setKeepContentOnPlayerReset(true)` sayesinde kanal değişirken siyah ekran flaşı yaşanmaz; yeni yayın ilk karesini verene kadar önceki kanalın son karesi ekranda donuk kalır. Hızlı zap sırasında 250 ms debounce ile her ara akış yüklenmez.
- **Ana Zap Döngüsü:** Yukarı/Aşağı tuşları yalnızca "Ana Liste" (ilk 15 kanal) içinde döner; yüzlerce kanallık listede kaybolmazsınız. Tüm kanallara Sol/OK ile açılan büyük puntolu sol çekmeceden erişilir.
- **Yatay NumPad:** Sağ ok, Mi Box kumandasında bulunmayan rakam tuşları için altta ince bir `0-9` şeridi açılır. Geleneksel TV gibi 3 hane dolunca ya da 2 sn sessizlikte otomatik geçiş yapar.
- **Çift Geri koruması:** Geri tuşu uygulamayı kapatmaz; sağ üstte `"Çıkmak için tekrar basın"` uyarısı çıkar, ikinci basışta çıkar.

### Oynatıcı dayanıklılığı

- **Donanım çözümleme:** `DefaultRenderersFactory` + `setEnableDecoderFallback(true)`; canlı TV için küçük arabellekli `DefaultLoadControl` (hızlı zap, düşük gecikme). ExoPlayer'ın kare bırakma yardımcısı vsync'e kilitlenir; destekleyen Android sürümlerinde (`Surface.setFrameRate`, API 30+) panel kare hızına otomatik uyum sağlanır.
- **Primary + Backup URL:** Birincil akış 3 sn içinde READY olmazsa (bağlantı zaman aşımı da 3 sn) otomatik olarak yedek adrese geçilir.
- **Sessiz auto-reconnect:** Akış koparsa kullanıcıya hata gösterilmez; arka planda 500 ms içinde (ilk 5 denemede, sonra 2 sn) yeniden bağlanır ve birincil/yedek adres arasında dönüşümlü dener.
- **Uzantısız URL'ler** bir kez HLS (`application/vnd.apple.mpegurl`) olarak zorlanarak denenir.
- Her kanalın kendi `User-Agent` / `Referer` başlıkları (`#EXTVLCOPT` ve Kodi tarzı `url|Header=deger` sözdizimi) korunur.

### M3U ayrıştırıcı

`#EXTINF` öznitelikleri (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`), `#EXTGRP`, `#EXTVLCOPT` başlıkları, HTML kaçış karakterleri ve çapraz protokol yönlendirmeleri desteklenir. İçe aktarmada **üst üste tekrarlanan kanal adları** ikinci adresin birincinin yedek (backup) adresi olduğu kabul edilir.

## Açık kaynak sentezi

Tekerlek sıfırdan icat edilmedi; aşağıdaki açık kaynak Android TV IPTV projeleri referans alındı, ilgili yapılar ayıklanıp temizlendi ve bu projeye uyarlandı:

- **[LiteTV](https://github.com/hertn/LiteTV)** (MIT) — M3U ayrıştırma yaklaşımı, indirme/yeniden deneme desenleri, TV tuş eşleme pratiği.
- **[onair-tv](https://github.com/jasmedia/onair-tv)** (AGPL referans; yalnızca fikir düzeyinde sentez) — Compose odak yönetimi, D-Pad `onKeyEvent` yönlendirme deseni, ExoPlayer kanal başlatma yapısı (`playChannel`, HLS zorlama, debounced zap).

## Tasarım — Monolithic Broadcast Console

- Arka plan `#08090A`, yüzeyler `#111317`, ana metin `#F4F4F5`, ikincil metin `#8E95A2`, odak/aktif vurgu `#E5A93C` (Broadcast Amber), kenarlık `#23272F`.
- Başlık ve kanal adları **Space Grotesk**, gövde **IBM Plex Sans**, kanal numaraları (`001`) ve saatler **IBM Plex Mono**.
- Gradyan, neon, glassmorphism, zıplayan animasyon ve emoji yok; geçişler kısa fade/slide.

## Kurulum ve derleme

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ilyemir.antena/.MainActivity
```

İlk açılışta M3U/M3U8 liste adresi girilir; kanallar Room'a numaralandırılarak kaydedilir ve yayın başlar.

## Yol haritası

- **Faz 1 + 2 (tamam):** M3U parser, Room DB, DataStore, Media3 tam ekran oynatıcı, freeze-frame zap, Ana Liste döngüsü, sol çekmece, yatay NumPad, çift Geri koruması.
- **Faz 3:** Ktor CIO üzerinde `Antena Remote` web paneli (port 8080) — M3U içe aktarma, Smart Merge, sürükle-bırak sıralama, kanal düzenleme/gizleme, Misafir/Yaşlı Kilit Modu, canlı sanal kumanda, JSON yedekleme; TV'de QR kodlu "Telefonla Yönet" ekranı.
