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
│   ├── SettingsStore.kt                → DataStore: liste, son kanal, kilit, uyku, boot, oran, ekran saati
│   └── ChannelRepository.kt            → indirme, numaralandırma, yedek sezgisi, favori havuzu, Smart Merge, JSON yedek
├── player/
│   ├── PlaybackController.kt           → ExoPlayer motoru (freeze-frame, yedek URL, auto-reconnect, 50Hz, kalite rozeti)
│   └── StreamResolver.kt               → oynatma anında dinamik akış çözücü (web:// ve yt:// şemaları)
├── server/
│   ├── AppContainer.kt                 → Runtime köprüsü (RemoteCmd akışı), yerel IP, NsdManager servisi, mDNS cevaplayıcı
│   ├── AntenaServer.kt                 → Ktor CIO :8080 — panel HTML + fontlar + JSON API
│   ├── BootReceiver.kt                 → BOOT_COMPLETED: overlay izniyle doğrudan, yoksa ön plan servisi + izin bildirimi
│   ├── BootLaunchService.kt            → overlay izni yokken Activity başlatma köprüsü (specialUse FGS)
│   ├── LocalNameResponder.kt           → 224.0.0.251:5353'te `antena.local` A kaydına cevap (en iyi çaba)
│   └── assets/
│       ├── remote.html                 → "Antena Remote" tek dosyalık iPhone Safari paneli (CDN yok)
│       ├── default_tr.m3u              → çok kaynaklı doğrulanmış gömülü Türk kanal havuzu (Primary+Backup, web:// dahil)
│       └── default_favorites.txt       → varsayılan "Özel Liste" sırası (21 kanal)
└── ui/
    ├── theme/AntenaTheme.kt            → renk tokenları + tipografi
    ├── PlayerScreen.kt                 → tam ekran yayın + OSD (kalite rozeti) + ekran saati + D-Pad yönlendirme
    ├── ChannelDrawer.kt                → sol kanal çekmecesi: "Özel Liste" / "Tüm Kanallar" sekmeleri
    ├── NumPadBar.kt                    → yatay 0-9 rakam şeridi
    ├── ManageScreen.kt                 → panel adresi + zxing QR (Compose Canvas çizimi)
    └── SetupScreen.kt                  → ilk kurulum (M3U adresi)
```

### Geleneksel TV davranışları

- **Doğrudan yayın:** Uygulama açılınca menü yok; en son izlenen kanal anında tam ekranda açılır. Veritabanı boşsa (ilk açılış) APK'ya gömülü doğrulanmış Türk kanal havuzu otomatik içe aktarılır ve 001 kanalından yayına başlanır — SetupScreen sadece kullanıcı açıkça "sıfırdan kurulum" seçerse görünür.
- **Freeze-frame zap:** `PlayerView.setKeepContentOnPlayerReset(true)` sayesinde kanal değişirken siyah ekran flaşı yaşanmaz; yeni yayın ilk karesini verene kadar önceki kanalın son karesi ekranda donuk kalır. Hızlı zap sırasında 250 ms debounce ile her ara akış yüklenmez.
- **Özel Liste zap halkası:** Yukarı/CH+ bir sonraki, Aşağı/CH- bir önceki kanala geçer ve zap varsayılan olarak **Özel Liste** (favoriler, `favoriteOrder` sırasıyla; ilk kurulumda 21 mainstream kanal) içinde döner. Özel Liste boşsa tüm kanallar numaraya göre dolaşılır. Tüm kanallara Sol/OK ile açılan, "Özel Liste / Tüm Kanallar" sekmeli büyük puntolu sol çekmeceden erişilir.
- **Sağlam tuş yakalama:** Kök `onPreviewKeyEvent` + kapatıldığında köke dönen FocusRequester sayesinde DirectionUp/Down ve CH+/CH− hiçbir alt görünüme sızmadan tüketilir; panel komutları `extraBufferCapacity=32` SharedFlow ve `rememberUpdatedState` ile işlenir — eski kompozisyon/kapanan (stale) durum yakalanmaz, peş peşe basılan tuşlar yutulmaz.
- **Yatay NumPad:** Sağ ok, Mi Box kumandasında bulunmayan rakam tuşları için altta ince bir `0-9` şeridi açılır. Geleneksel TV gibi 3 hane dolunca ya da 2 sn sessizlikte otomatik geçiş yapar.
- **Çift Geri koruması:** Geri tuşu uygulamayı kapatmaz; sağ üstte `"Çıkmak için tekrar basın"` uyarısı çıkar, ikinci basışta çıkar.
- **Önceki kanal (recall):** Her kanal değişiminde bir önceki hatırlanır; kumandada yeşil tuş bölgesi/`PREV`, panelde "ÖNCEKİ KANAL" tek tuşla az önce izlenen kanala zıplar.
- **Görüntü oranı:** Yeşil tuş (TV) veya panel seçimi ile `Ekrana Sığdır → Tam Ekran (kırpma) → Germe → Orijinal` döngüsü; seçim DataStore'da kalıcıdır.
- **50 Hz yayın uyumu:** `setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS)` — destekleyen ekranlarda yenileme hızı yayın kare hızına (50 Hz) seamless geçirilir.
- **Açılışta başlatma:** `BOOT_COMPLETED` alıcısı Mi Box yeniden başladığında doğrudan yayını açar; panelin Sistem sekmesinden kapatılabilir (varsayılan açık). Android 10+ arka plandan Activity başlatma kısıtı iki yolla aşılır: (1) `SYSTEM_ALERT_WINDOW` / `Settings.canDrawOverlays()` izni varsa Activity `NEW_TASK | CLEAR_TOP` ile doğrudan başlatılır, (2) izin yoksa `BootLaunchService` (specialUse ön plan servisi) üzerinden denenir ve kullanıcıya tek seferlik izin bildirimi gösterilir. Not: Mi Box ROM'u `appops SYSTEM_ALERT_WINDOW` kaydını her açılışta sıfırladığından kutudaki kalıcı yol ikinci maddedir.
- **Ekran saati:** Sağ üst köşede `HH:mm`, IBM Plex Mono, yarı saydam `#111317` zemin + `#23272F` kenarlık; panelin Sistem sekmesindeki **"Ekran Saati"** anahtarıyla anında açılıp kapanır (DataStore'da kalıcı, varsayılan açık).
- **Kalite rozeti:** OSD bilgi şeridinde kanal adının hemen sağında, aynı satırda aktif çözünürlük (`1080p` / `720p` / `576p`) — `Player.Listener.onVideoSizeChanged` + `onTracksChanged`, 11.sp IBM Plex Mono, ince kenarlık.
- **Uyku zamanlayıcısı:** Panel üzerinden 30/60/90 dk kurulur; süre dolunca uygulama kapanır.
- **Misafir / Yaşlı Kilit Modu:** Panel veya gizli kombinasyonla açılır; aktifken TV'deki "Telefonla Yönet" girişi gizlenir — admin kilidi `▲▲▼▼OK` gizli tuş dizisiyle her zaman açabilir.

### Oynatıcı dayanıklılığı

- **Donanım çözümleme:** `DefaultRenderersFactory` + `setEnableDecoderFallback(true)`; canlı TV arabelleği `DefaultLoadControl` `minBufferMs=15000 / maxBufferMs=30000 / bufferForPlaybackMs=2500 / bufferForPlaybackAfterRebufferMs=5000` — CDN parça gecikmelerinde (ercdn dalgalanmaları) donma olmaz, zap yine hızlıdır. ExoPlayer'ın kare bırakma yardımcısı vsync'e kilitlenir; destekleyen Android sürümlerinde (`Surface.setFrameRate`, API 30+) panel kare hızına otomatik uyum sağlanır.
- **Dynamic akış çözücü (`StreamResolver`):** Kanal adresi `web://` ile başlıyorsa yayıncının canlı izleme sayfası `Mozilla/5.0` UA ile indirilir, içindeki canlı `.m3u8` adresi regex ile ayıklanır ve gerçekten HLS döndüren aday seçilir (ör. Teve2 sayfasındaki ölü `duhnet` adresi atlanır, `demiroren.daioncdn.net` seçilir). `yt://` ile başlıyorsa `/@kanal/live` sayfasındaki `ytInitialPlayerResponse` içinden `hlsManifestUrl` okunur ve **aynı User-Agent** oynatıcıya verilir (googlevideo parçaları UA'ya bağlıdır). Çözüm her zap'ta ve her yeniden bağlanmada tazelenir; başarısızlıkta öneki soyulmuş adres denenir.
- **Primary + Backup URL:** Birincil akış 3 sn içinde READY olmazsa (bağlantı zaman aşımı da 3 sn) otomatik olarak yedek adrese geçilir.
- **Sessiz auto-reconnect:** Akış koparsa kullanıcıya hata gösterilmez; arka planda 500 ms içinde (ilk 5 denemede, sonra 2 sn) yeniden bağlanır ve birincil/yedek adres arasında dönüşümlü dener.
- **Uzantısız URL'ler** bir kez HLS (`application/vnd.apple.mpegurl`) olarak zorlanarak denenir.
- Her kanalın kendi `User-Agent` / `Referer` başlıkları (`#EXTVLCOPT` ve Kodi tarzı `url|Header=deger` sözdizimi) korunur.

### M3U ayrıştırıcı

`#EXTINF` öznitelikleri (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`), `#EXTGRP`, `#EXTVLCOPT` başlıkları, HTML kaçış karakterleri ve çapraz protokol yönlendirmeleri desteklenir. İçe aktarmada **üst üste tekrarlanan kanal adları** ikinci adresin birincinin yedek (backup) adresi olduğu kabul edilir.

### Gömülü Türk kanal havuzu ve Özel Liste

- `assets/default_tr.m3u`: açık kaynak listelerden (iptv-org, dearbulut, zek gist, Free-TV) derlenen ve `Mozilla/5.0` ile canlı doğrulanan (200 OK + geçerli HLS) **39 şifresiz kanal**; kulüp (FB/GS/BJK) ve müzik kanalları bilinçli olarak dışarıda. En hızlı yanıt veren adres **Primary**, çalışan ikinci adres **Backup** olarak ardışık satırlarda. 7 kanal yedekli: ATV, NOW, Kanal 7, TRT Belgesel, Bloomberg HT, TRT Spor, TRT Spor Yıldız.
  - **Ana Akış (12):** TRT 1, ATV, Show TV, Star TV, Kanal D, NOW, TV8, Kanal 7, CNBC-e, 360 TV, A2, TRT 2
  - **Çocuk (5):** Cartoon Network, TRT Çocuk, MinikaGO, Minika Çocuk, Spacetoon Türkiye
  - **Belgesel (3):** TRT Belgesel, National Geographic, Nat Geo Wild
  - **Haber (12):** TRT Haber, NTV, Habertürk, CNN Türk, Sözcü TV, Halk TV, A Haber, Haber Global, TV100, 24 TV, Bloomberg HT, A Para
  - **Spor (3):** TRT Spor, TRT Spor Yıldız, A Spor
  - **Eğlence (4, `web://` dinamik çözücüyle):** DMAX, TLC, Teve2, TV8.5 — doğrudan CDN adresi yerine yayıncının canlı izleme sayfası adreslenir; token'lar her zap'ta taze alınır. Cihazda MediaCodec ile doğrulandı (DMAX 480p, TLC 720p, Teve2 480p, TV8.5 1080p; 0 oynatıcı hatası).
  - **ATV donması düzeltmesi:** `atv.m3u8` master'ı 1080p varyantı işaret ediyordu ama o varyant yanıt vermiyordu (donmanın kaynağı); artık birincil adres doğrudan çalışan `atv_720p.m3u8`, yedek adres master. Daha geniş `LoadControl` arabelleği ile birlikte kutuda takılmasız izlendi (MediaMetrics: 2703 kare render, 0 hata).
  - **Havuzda yer bulunamayan / kaldırılan hedefler:** şifreli veya kaynakları ölü — Beyaz TV, Cartoonito, Discovery, Tarih TV, Ekol TV, Tivibu Spor, beIN Sports Haber. **YouTube canlı kanalları (HT Spor, TGRT Haber) kısa süre denendikten sonra havuzdan çıkarıldı:** manifest çözülsa da `googlevideo.com` video parçaları 403 dönüyor (farklı UA/Referer/Range denemeleri sonuç vermedi); `yt://` çözücü altyapısı Özel Liste için hazır duruyor.
- `assets/default_favorites.txt`: ilk kurulumda otomatik favorilenen **21 kanal** (zap sırası): TRT 1, ATV, Show TV, Star TV, Kanal D, NOW, TV8, Kanal 7, CNBC-e, Cartoon Network, TRT Çocuk, TRT Belgesel, TRT Haber, NTV, Habertürk, CNN Türk, TRT Spor, DMAX, TLC, Teve2, TV8.5.
- `ChannelEntity` alanları `isFavorite` + `favoriteOrder` (DB v2); Smart Merge ve JSON yedek/geri yükleme favori kararlarını **korur** (yedek sürümü 2).

## Antena Remote — telefon kontrol paneli (Faz 3)

Uygulama açık olduğu sürece **Ktor Server CIO** `0.0.0.0:8080` üzerinde kalır (Netty yok). Cihazın yerel IPv4 adresi arayüzlerden otomatik bulunur. Panel, APK içinden tek HTML/CSS/JS dosyası ve APK'ya gömülü TTF fontlarla servis edilir — **harici CDN yok**, yerel ağda çevrimdışı anında açılır. iPhone Safari için dokunmatik hedefler, `env(safe-area-inset)`, Pointer Events sürükle-bırak.

**Adres değişmezliği (`antena.local`):** `AppContainer` iki şey yapar — (1) `NsdManager` ile `_http._tcp.` servisini `antena` adıyla yayınlar (Keşif/üçüncü parti uygulamalar için), (2) `LocalNameResponder` ile `224.0.0.251:5353`'te `antena.local` A sorgularını cihazın IPv4 adresiyle cevaplamayı dener. Android'in kendi `mdnsd` demonu 5353'ü tek başına tuttuğu için Mi Box'ta (Android 14) A kaydı cevaplanamıyor; bu durumda panel IP ile açılır (`http://<ip>:8080`) — "Telefonla Yönet" ekranı ve QR her zaman güncel IP'yi gösterir.

TV tarafında çekmecenin altındaki **"Telefonla Yönet"** girişi (kilit modunda gizli; `▲▲▼▼OK` ile her zaman erişilebilir) adresi ve zxing ile üretilip Compose Canvas ile çizilen QR kodunu gösterir.

| Endpoint | İşlev |
|---|---|
| `GET /` | Panel (tek dosya HTML) |
| `GET /fonts/{ad}` | Panel fontları (aynı Space Grotesk / IBM Plex ailesi) |
| `GET /api/state` | IP, port, kanal sayısı, şu anki kanal, kilit/uyku/boot/oran/**saat** |
| `GET /api/channels` | Tüm kanallar (id, sıra, adresler, görünürlük, `isFavorite`, `favoriteOrder`) |
| `POST /api/channels/order` | `{id, number? , favoriteOrder?}[]` — genel sıra ve/veya Özel Liste sırası → Room → TV anında yenilenir |
| `POST /api/channels/patch` | Yeniden adlandırma, adres/yedek düzenleme, gizle/göster, **★ favoriye ekle/çıkar** |
| `POST /api/playlist` | `merge=false` tam içe aktarma · `merge=true` **Smart Merge** |
| `POST /api/remote` | `chUp · chDown · digit · clear · prev · volUp · volDown · mute · aspect` |
| `POST /api/settings` | `guestMode · sleepMinutes · bootStart · aspect · showClock` |
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
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ilyemir.antena/.MainActivity
```

Açılışta otomatik başlatma izinleri (opsiyonel; overlay izni yoksa `BootLaunchService` devreye girer):

```bash
adb shell appops set com.ilyemir.antena SYSTEM_ALERT_WINDOW allow   # ROM reboot'ta sıfırlayabilir
adb shell pm grant com.ilyemir.antena android.permission.POST_NOTIFICATIONS
# doğrulama (BOOT_COMPLETED korumalı yayındır; kutuyu yeniden başlatmak en güvenilir test)
adb reboot
```

Listeyi sıfırlayıp gömülü havuzu yeniden yüklemek için: `adb shell pm clear com.ilyemir.antena` (veritabanı boşsa havuz otomatik içe aktarılır).

İlk açılışta veritabanı boşsa APK'ya gömülü doğrulanmış Türk kanal havuzu otomatik yüklenir ve 001 kanalı hemen açılır; kendi listeni kullanmak için panelin Liste sekmesinden M3U/M3U8 adresi ver (tam içe aktarma veya Smart Merge). Özel Listeye elle `web://<sayfa>` veya `yt://<youtube-canlı-adresi>` adresi girilebilir; oynatıcı adresi zap anında çözer.

## Yol haritası

- **Faz 1 + 2 (tamam):** M3U parser, Room DB, DataStore, Media3 tam ekran oynatıcı, freeze-frame zap, Ana Liste döngüsü, sol çekmece, yatay NumPad, çift Geri koruması.
- **Faz 3 (tamam):** Ktor CIO `Antena Remote` paneli (:8080) — canlı sanal kumanda, dokunmatik sürükle-bırak kanal düzenleyici, Smart Merge, Misafir/Yaşlı Kilit Modu, uyku zamanlayıcısı, JSON yedek/geri yükleme; TV'de QR kodlu "Telefonla Yönet" ekranı ve `▲▲▼▼OK` gizli girişi.
- **Faz 4 (tamam):** Önceki kanal (recall), görüntü oranı döngüsü (yeşil tuş), 50 Hz kare hızı tercihi, `BOOT_COMPLETED` açılışta başlatma (panel üzerinden açılıp kapanır).
- **Faz 5 (tamam):** Çok kaynaklı doğrulanmış gömülü Türk kanal havuzu + ilk açılışta otomatik içe aktarma; `isFavorite`/`favoriteOrder` ile **Özel Liste** zap halkası (15 kanal sınırı kaldırıldı), çekmecede Özel Liste/Tüm Kanallar sekmeleri, panelde ★ favori düzenleme ve sekme bazlı sıralama; `onPreviewKeyEvent` + `rememberUpdatedState` ile kesin tuş/komut dayanıklılığı.
- **Faz 6 (tamam):** `StreamResolver` ile oynatma anında dinamik akış çözümü (`web://` sayfa→m3u8, `yt://` hlsManifestUrl) ve havuza eklenen `web://` tabanlı DMAX/TLC/Teve2/TV8.5; ATV için çalışan 720p birincil adres + genişletilmiş `LoadControl` arabelleği (donma düzeltmesi); açılışta başlatmanın `SYSTEM_ALERT_WINDOW` + ön plan servisi ile Android 14'e dayanıklı hale getirilmesi (`BootReceiver`/`BootLaunchService`); OSD'de kanal adının yanında kalite rozeti; panel ayarlarına **"Ekran Saati"** anahtarı (`/api/state` + `/api/settings` → `showClock`); `NsdManager` `_http._tcp` kaydı ve `antena.local` mDNS cevaplayıcı denemesi.
