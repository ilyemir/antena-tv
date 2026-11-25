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
| Panel | Ktor Server CIO, port 8080 |

## Mimari

```
com.ilyemir.antena
├── MainActivity / AntenaViewModel      → tek Activity, Compose oynaması
├── AntenaApplication                   → AppContainer (repository + ayarlar + sunucu) yaşam döngüsü
├── data/
│   ├── M3uParser.kt                    → genişletilmiş M3U/M3U8 ayrıştırıcı
│   ├── db/Channels.kt                  → Room: kanal tablosu + DAO
│   ├── SettingsStore.kt                → DataStore: liste, son kanal, kilit, uyku, boot, oran
│   └── ChannelRepository.kt            → indirme, numaralandırma, yedek sezgisi, favori havuzu, Smart Merge, JSON yedek
├── player/PlaybackController.kt        → ExoPlayer motoru (freeze-frame, yedek URL, auto-reconnect, 50Hz)
├── server/
│   ├── AppContainer.kt                 → Runtime köprüsü (RemoteCmd akışı), yerel IP, BootReceiver
│   ├── AntenaServer.kt                 → Ktor CIO :8080 — panel HTML + fontlar + JSON API
│   └── assets/
│       ├── remote.html                 → "Antena Remote" tek dosyalık iPhone Safari paneli (CDN yok)
│       ├── default_tr.m3u              → çok kaynaklı doğrulanmış gömülü Türk kanal havuzu (Primary+Backup)
│       └── default_favorites.txt       → varsayılan "Özel Liste" sırası (13 ulusal kanal)
└── ui/
    ├── theme/AntenaTheme.kt            → renk tokenları + tipografi
    ├── PlayerScreen.kt                 → tam ekran yayın + OSD + D-Pad yönlendirme + uzaktan komutlar
    ├── ChannelDrawer.kt                → sol kanal çekmecesi: "Özel Liste" / "Tüm Kanallar" sekmeleri
    ├── NumPadBar.kt                    → yatay 0-9 rakam şeridi
    ├── ManageScreen.kt                 → panel adresi + zxing QR (Compose Canvas çizimi)
    └── SetupScreen.kt                  → ilk kurulum (M3U adresi)
```

### Geleneksel TV davranışları

- **Doğrudan yayın:** Uygulama açılınca menü yok; en son izlenen kanal anında tam ekranda açılır. Veritabanı boşsa (ilk açılış) APK'ya gömülü doğrulanmış Türk kanal havuzu otomatik içe aktarılır ve 001 kanalından yayına başlanır — SetupScreen sadece kullanıcı açıkça "sıfırdan kurulum" seçerse görünür.
- **Freeze-frame zap:** `PlayerView.setKeepContentOnPlayerReset(true)` sayesinde kanal değişirken siyah ekran flaşı yaşanmaz; yeni yayın ilk karesini verene kadar önceki kanalın son karesi ekranda donuk kalır. Hızlı zap sırasında 250 ms debounce ile her ara akış yüklenmez.
- **Özel Liste zap halkası:** Yukarı/CH+ bir sonraki, Aşağı/CH- bir önceki kanala geçer ve zap varsayılan olarak **Özel Liste** (favoriler, `favoriteOrder` sırasıyla; ilk kurulumda 13 ulusal kanal) içinde döner. Özel Liste boşsa tüm kanallar numaraya göre dolaşılır. Tüm kanallara Sol/OK ile açılan, "Özel Liste / Tüm Kanallar" sekmeli büyük puntolu sol çekmeceden erişilir.
- **Sağlam tuş yakalama:** Kök `onPreviewKeyEvent` + kapatıldığında köke dönen FocusRequester sayesinde DirectionUp/Down ve CH+/CH− hiçbir alt görünüme sızmadan tüketilir; panel komutları `extraBufferCapacity=32` SharedFlow ve `rememberUpdatedState` ile işlenir — eski kompozisyon/kapanan (stale) durum yakalanmaz, peş peşe basılan tuşlar yutulmaz.
- **Yatay NumPad:** Sağ ok, Mi Box kumandasında bulunmayan rakam tuşları için altta ince bir `0-9` şeridi açılır. Geleneksel TV gibi 3 hane dolunca ya da 2 sn sessizlikte otomatik geçiş yapar.
- **Çift Geri koruması:** Geri tuşu uygulamayı kapatmaz; sağ üstte `"Çıkmak için tekrar basın"` uyarısı çıkar, ikinci basışta çıkar.
- **Önceki kanal (recall):** Her kanal değişiminde bir önceki hatırlanır; kumandada yeşil tuş bölgesi/`PREV`, panelde "ÖNCEKİ KANAL" tek tuşla az önce izlenen kanala zıplar.
- **Görüntü oranı:** Yeşil tuş (TV) veya panel seçimi ile `Ekrana Sığdır → Tam Ekran (kırpma) → Germe → Orijinal` döngüsü; seçim DataStore'da kalıcıdır.
- **50 Hz yayın uyumu:** `setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS)` — destekleyen ekranlarda yenileme hızı yayın kare hızına (50 Hz) seamless geçirilir.
- **Açılışta başlatma:** `BOOT_COMPLETED` alıcısı Mi Box yeniden başladığında doğrudan yayını açar; panelin Sistem sekmesinden kapatılabilir (varsayılan açık).
- **Uyku zamanlayıcısı:** Panel üzerinden 30/60/90 dk kurulur; süre dolunca uygulama kapanır.
- **Misafir / Yaşlı Kilit Modu:** Panel veya gizli kombinasyonla açılır; aktifken TV'deki "Telefonla Yönet" girişi gizlenir — admin kilidi `▲▲▼▼OK` gizli tuş dizisiyle her zaman açabilir.

### Oynatıcı dayanıklılığı

- **Donanım çözümleme:** `DefaultRenderersFactory` + `setEnableDecoderFallback(true)`; canlı TV için küçük arabellekli `DefaultLoadControl` (hızlı zap, düşük gecikme). ExoPlayer'ın kare bırakma yardımcısı vsync'e kilitlenir; destekleyen Android sürümlerinde (`Surface.setFrameRate`, API 30+) panel kare hızına otomatik uyum sağlanır.
- **Primary + Backup URL:** Birincil akış 3 sn içinde READY olmazsa (bağlantı zaman aşımı da 3 sn) otomatik olarak yedek adrese geçilir.
- **Sessiz auto-reconnect:** Akış koparsa kullanıcıya hata gösterilmez; arka planda 500 ms içinde (ilk 5 denemede, sonra 2 sn) yeniden bağlanır ve birincil/yedek adres arasında dönüşümlü dener.
- **Uzantısız URL'ler** bir kez HLS (`application/vnd.apple.mpegurl`) olarak zorlanarak denenir.
- Her kanalın kendi `User-Agent` / `Referer` başlıkları (`#EXTVLCOPT` ve Kodi tarzı `url|Header=deger` sözdizimi) korunur.

### M3U ayrıştırıcı

`#EXTINF` öznitelikleri (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`), `#EXTGRP`, `#EXTVLCOPT` başlıkları, HTML kaçış karakterleri ve çapraz protokol yönlendirmeleri desteklenir. İçe aktarmada **üst üste tekrarlanan kanal adları** ikinci adresin birincinin yedek (backup) adresi olduğu kabul edilir.

### Gömülü Türk kanal havuzu ve Özel Liste

- `assets/default_tr.m3u`: iptv-org, Free-TV ve diğer açık GitHub M3U kaynaklarından derlenen 24 ulusal kanal (TRT 1 … TRT Çocuk); her aday akış HEAD/GET ile canlı doğrulanmış, en hızlı 200 OK yanıt veren adres **Primary**, diğerleri **Backup** olarak yazılmıştır. ATV, NOW ve TRT Spor çift adresli (Backup'lı) girilir.
- `assets/default_favorites.txt`: ilk kurulumda otomatik favorilenen 13 kanalı zap sırasıyla listeler (TRT 1, ATV, Show TV, Star TV, Kanal D, NOW, TV8, Kanal 7, TRT Haber, NTV, Habertürk, CNN Türk, TRT Spor).
- `ChannelEntity` alanları `isFavorite` + `favoriteOrder` (DB v2); Smart Merge ve JSON yedek/geri yükleme favori kararlarını **korur** (yedek sürümü 2).

## Antena Remote — telefon kontrol paneli (Faz 3)

Uygulama açık olduğu sürece **Ktor Server CIO** `0.0.0.0:8080` üzerinde kalır (Netty yok). Cihazın yerel IPv4 adresi arayüzlerden otomatik bulunur. Panel, APK içinden tek HTML/CSS/JS dosyası ve APK'ya gömülü TTF fontlarla servis edilir — **harici CDN yok**, yerel ağda çevrimdışı anında açılır. iPhone Safari için dokunmatik hedefler, `env(safe-area-inset)`, Pointer Events sürükle-bırak.

TV tarafında çekmecenin altındaki **"Telefonla Yönet"** girişi (kilit modunda gizli; `▲▲▼▼OK` ile her zaman erişilebilir) adresi ve zxing ile üretilip Compose Canvas ile çizilen QR kodunu gösterir.

| Endpoint | İşlev |
|---|---|
| `GET /` | Panel (tek dosya HTML) |
| `GET /fonts/{ad}` | Panel fontları (aynı Space Grotesk / IBM Plex ailesi) |
| `GET /api/state` | IP, port, kanal sayısı, şu anki kanal, kilit/uyku/boot/oran |
| `GET /api/channels` | Tüm kanallar (id, sıra, adresler, görünürlük, `isFavorite`, `favoriteOrder`) |
| `POST /api/channels/order` | `{id, number? , favoriteOrder?}[]` — genel sıra ve/veya Özel Liste sırası → Room → TV anında yenilenir |
| `POST /api/channels/patch` | Yeniden adlandırma, adres/yedek düzenleme, gizle/göster, **★ favoriye ekle/çıkar** |
| `POST /api/playlist` | `merge=false` tam içe aktarma · `merge=true` **Smart Merge** |
| `POST /api/remote` | `chUp · chDown · digit · clear · prev · volUp · volDown · mute · aspect` |
| `POST /api/settings` | `guestMode · sleepMinutes · bootStart · aspect` |
| `GET /api/backup` · `POST /api/restore` | `antena_backup.json` indir / geri yükle |

**Smart Merge:** Mevcut sıra, özel isimler, gizleme ve **favori (Özel Liste) kararları bozulmaz**; yalnızca adı eşleşen kanalların yayın (ve yedek) adresleri yenilenir, listede yeni olanlar sona eklenir, eskileri silinmez. Kumanda komutları `AudioManager` (ses) ve TV arayüzüne iletildiği bir SharedFlow üzerinden anında uygulanır.

**Kanallar sekmesi:** "Özel Liste / Tüm Kanallar" filtreli düzenleyici — her satırda tek dokunuşla **★ EKLE / ★ ÇIKAR**, sürükle-bırak veya oklarla sıralama (Özel Liste görünümünde `favoriteOrder`, genel görünümde numara kaydedilir).

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

İlk açılışta veritabanı boşsa APK'ya gömülü doğrulanmış Türk kanal havuzu otomatik yüklenir ve 001 kanalı hemen açılır; kendi listeni kullanmak için panelin Liste sekmesinden M3U/M3U8 adresi ver (tam içe aktarma veya Smart Merge).

## Yol haritası

- **Faz 1 + 2 (tamam):** M3U parser, Room DB, DataStore, Media3 tam ekran oynatıcı, freeze-frame zap, Ana Liste döngüsü, sol çekmece, yatay NumPad, çift Geri koruması.
- **Faz 3 (tamam):** Ktor CIO `Antena Remote` paneli (:8080) — canlı sanal kumanda, dokunmatik sürükle-bırak kanal düzenleyici, Smart Merge, Misafir/Yaşlı Kilit Modu, uyku zamanlayıcısı, JSON yedek/geri yükleme; TV'de QR kodlu "Telefonla Yönet" ekranı ve `▲▲▼▼OK` gizli girişi.
- **Faz 4 (tamam):** Önceki kanal (recall), görüntü oranı döngüsü (yeşil tuş), 50 Hz kare hızı tercihi, `BOOT_COMPLETED` açılışta başlatma (panel üzerinden açılıp kapanır).
- **Faz 5 (tamam):** Çok kaynaklı doğrulanmış gömülü Türk kanal havuzu + ilk açılışta otomatik içe aktarma; `isFavorite`/`favoriteOrder` ile **Özel Liste** zap halkası (15 kanal sınırı kaldırıldı), çekmecede Özel Liste/Tüm Kanallar sekmeleri, panelde ★ favori düzenleme ve sekme bazlı sıralama; `onPreviewKeyEvent` + `rememberUpdatedState` ile kesin tuş/komut dayanıklılığı.
