# HK Music Player

Ứng dụng nghe nhạc Android (Jetpack Compose) đọc nhạc thật từ thư viện máy, phát nhạc nền
bằng `ExoPlayer` + `MediaSessionService`, có equalizer thật, visualizer thật, điều khiển từ xa
qua Wi-Fi và phát lên Chromecast / DLNA TV.

## Tính năng

| Nhóm | Chi tiết |
| --- | --- |
| Thư viện | Quét `MediaStore` (bài hát, album, thời lượng, ảnh bìa, thư mục), tìm kiếm, duyệt theo thư mục, 3 kiểu danh sách 3D |
| Phát nhạc | `ExoPlayer` chạy trong `MusicService` (`MediaSessionService`): phát nền, thông báo media, khoá màn hình, tai nghe/Bluetooth, tua, next/prev, shuffle, repeat |
| Âm thanh | `Equalizer` (đúng số băng tần của thiết bị) + 3 núm vặn BASS / VOL / TREB, preset (Bass/Normal/Treble/Vocal/Rock/Pop) |
| Visualizer | `Visualizer` thật lấy dữ liệu từ audio session (cần quyền `RECORD_AUDIO`), **5 kiểu** — Bars / Wave / Radial / Mirror / Constellation, chạm thanh để đổi, lưu lựa chọn |
| Concert Mode | Chế độ light show toàn màn hình: particle orbit, aurora glow theo artwork, spectrum mountain 48 band, beat detection (vòng tỏa khi bass drop), UI tự ẩn, nút LIVE/✕, vào từ Full Player ✨ |
| Ambient glow | Ảnh bìa phóng to + blur toàn màn hình Full Player, alpha "thở" theo nhịp, scrim gradient giữ chữ dễ đọc |
| Tìm kiếm / YouTube | Tìm video YouTube trong app, phát trực tiếp hoặc tải về nghe offline, chọn chất lượng tải |
| Lời bài hát | LRCLIB + file `.lrc` cùng tên + offset ±0,5 s, đồng bộ theo thời gian |
| Web remote | HTTP server thật (mặc định cổng 8080): trang điều khiển cho PC/điện thoại cùng Wi-Fi, playlist, tua, volume, EQ web, phím tắt, upload nhạc, chia sẻ playlist qua QR |
| Party mode | Host (điện thoại) bật trong tab Share; ai cũng thêm bài được, **mỗi người chỉ xoá được bài mình thêm**; host xoá tất cả. Upload gắn tên người thêm, hiển thị trên web |
| Chromecast / DLNA | `MediaRouter` + `CastPlayer` cho Chromecast; DLNA tự viết qua SSDP + SOAP (AVTransport / RenderingControl): quét thiết bị, kết nối, phát/dừng/âm lượng TV, tự phát lại khi TV tự resume |
| Ảnh bìa & màu | Trích xuất bảng màu từ ảnh bìa, nền gradient "thở" theo màu artwork |
| PiP | Picture-in-Picture tự động khi rời app |
| Chia sẻ | Mã QR trỏ tới remote / playlist, copy link; **Chia sẻ ứng dụng** — gửi bản APK hiện tại cho người khác qua Wi-Fi / Bluetooth / Nearby Share / MXH |

## Kiến trúc

```
app/src/main/kotlin/com/hk/app/
├── MainActivity.kt        # entry point Compose, PiP
├── App.kt                 # UI chính, MediaController, điều hướng
├── MusicService.kt        # ExoPlayer + MediaSession (nguồn phát duy nhất)
├── AudioFx.kt             # Equalizer / BassBoost / Virtualizer / Visualizer
├── LibraryRepository.kt   # MediaStore, ảnh bìa, .lrc, YouTube, IP nội bộ
├── WebRemoteServer.kt     # HTTP server: /api/status, /audio, /upload, trang remote
├── CastManager.kt         # MediaRouter + CastPlayer
├── DlnaManager.kt         # SSDP discovery + SOAP control cho DLNA renderer
├── Components.kt          # danh sách 3D, player dashboard, folder browser
├── Equalizer.kt           # UI equalizer + visualizer
├── ShareHub.kt            # UI chia sẻ / remote / party / cast / chia sẻ app (APK)
├── Model.kt               # Song, LRC parser, format thời gian
└── QrCode.kt              # sinh mã QR
```

Luồng phát: UI → `MediaController` → `MusicService` (ExoPlayer). UI **không** tự tạo player,
nhờ vậy nhạc tiếp tục phát khi thoát app và điều khiển được từ thông báo / Bluetooth.

## Quyền

- `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE`: đọc thư viện nhạc.
- `POST_NOTIFICATIONS`: thông báo điều khiển nhạc.
- `RECORD_AUDIO`: chỉ dùng cho `Visualizer` (Android bắt buộc).
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`: web remote và cast.
- `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, `WAKE_LOCK`: phát nhạc nền.

## Web remote

Bật trong tab **Share → Web remote → Bật server**, rồi mở `http://<IP-điện-thoại>:8080`
trên máy tính cùng Wi-Fi (lưu ý: điện thoại và máy tính phải cùng một mạng LAN/Wi-Fi).

Các endpoint: xem bảng trong mã `WebRemoteServer.kt` (`/`, `/api/status`, `/audio?index=N`,
`/play`, `/pause`, `/seek`, `/volume`, `/upload`, `/remove?pos=N[&who=tên]`, `/party` …).

### Ứng dụng APK hiện tại

File APK release đã ký được phát hành *qua GitHub Releases* (không commit vào git).

## Build

Yêu cầu: JDK 17+, Android SDK 35+, Gradle wrapper đi kèm, Android 7.0+ (API 24).

```bash
./gradlew :app:assembleDebug     # build APK debug
./gradlew :app:assembleRelease   # build release
./gradlew :app:installDebug      # cài lên thiết bị đang kết nối
```

### Ký APK release (tùy chọn)

Repo không chứa keystore. Nếu muốn tự build release có chữ ký:

1. Tạo keystore: `keytool -genkey -v -keystore app/release.keystore -alias musicplayer -keyalg RSA -keysize 2048 -validity 10000`
2. Tạo `keystore.properties` ở thư mục gốc repo:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=musicplayer
keyPassword=...
```

(`.gitignore` đã loại trừ `keystore.properties` và `*.keystore`.)

Không có `keystore.properties` thì `assembleRelease` vẫn build được nhưng APK không ký.

## Ghi chú

- Ảnh bìa được giải mã tối đa 320 px để thư viện lớn không làm tràn heap.
- Lời bài hát: ưu tiên file `.lrc` cùng tên cạnh file nhạc, sau đó tới file đã nạp trong app
  (LRCLIB tự tìm lời đồng bộ nếu cần).
- Audio session được `MusicService` tạo sẵn và ghim vào ExoPlayer, nên EQ và visualizer dùng
  đúng một session cho cả nhạc local lẫn luồng YouTube.
- Party mode: chỉ điện thoại (host) bật/tắt được; khách trên web chỉ có quyền xoá bài mình thêm.
