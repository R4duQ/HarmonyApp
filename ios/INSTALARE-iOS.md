# Harmony pentru iOS: build în cloud și instalare de pe Windows

Nu ai nevoie de Mac. GitHub Actions construiește aplicația pe un runner macOS,
iar tu o instalezi de pe Windows cu Sideloadly sau AltStore.

## 1. Obține IPA-ul

1. Pe GitHub: **Actions → Harmony for iOS → Run workflow** (sau orice push în `ios/`).
2. Când rularea se termină (verde), deschide-o și descarcă artefactul
   **Harmony-iOS-Unsigned** (un zip care conține `Harmony-iOS-Unsigned.ipa`).

## 2. Instalează pe iPhone (Sideloadly, Windows)

1. Instalează iTunes și iCloud de pe site-ul Apple (nu din Microsoft Store), apoi Sideloadly.
2. Conectează iPhone-ul prin cablu și apasă **Trust** pe telefon.
3. Trage `Harmony-iOS-Unsigned.ipa` în Sideloadly, introdu Apple ID-ul și apasă **Start**.
4. Pe iPhone: **Settings → General → VPN & Device Management** → aprobă profilul tău.
5. Pe iOS 16+: **Settings → Privacy & Security → Developer Mode** → pornește-l (cere restart).

Cu un Apple ID gratuit, aplicația trebuie re-semnată la 7 zile (Sideloadly sau
AltStore o pot reîmprospăta automat).

## 3. Ce face acest prim build

La pornire rulează pe iPhone exact motoarele C++ din aplicația Android
(`core/eq-engine` și `core/dsp-native`), fără copii:

- **Equalizer (MobileEqEngine)**: fiecare ieșire (difuzor, căști, Bluetooth,
  USB DAC) la 44,1 / 48 / 96 / 192 kHz, toate benzile +12 dB, Auto pornit,
  semnal peste 0 dBFS cu NaN/Inf injectate. Trece dacă nimic nu depășește
  plafonul ieșirii și nu se pierde niciun eșantion.
- **Smart Shuffle (Diagnostic Brain)**: auto-testul cu 100 de piese virtuale.

Următoarele module adaugă playerul complet peste acest nucleu.

## Muzica ta

Aplicația apare în **Files → On My iPhone → Harmony**: copiază muzica acolo,
sau (din modulele următoare) alege orice folder din Files, iar Harmony îl
păstrează accesibil și după repornire.

## Downloads pe iOS

Tab-ul **Downloads** are patru surse. Ce se descarcă ajunge în
**Files › On My iPhone › Harmony › Downloads**, intră în bibliotecă și i se
măsoară imediat vectorul 24D pentru Smart Shuffle.

- **Soulseek P2P**: te conectezi cu contul Soulseek. Parola se păstrează în
  Keychain, doar pe acest iPhone. Cauți „Artist - Melodie”, iar rezultatele
  sunt ordonate ca pe Android (potrivire, FLAC, 24-bit/hi-res, slot liber,
  coadă). Un fișier e păstrat doar dacă e cu adevărat FLAC/MP3 valid; o
  descărcare întreruptă se reia de unde a rămas. Soulseek permite o singură
  sesiune pe cont, deci conectarea aici deconectează celelalte clienturi.
- **Links**: un link direct către un fișier audio (FLAC, ALAC, WAV, AIFF, MP3,
  M4A), de exemplu un download Bandcamp, archive.org sau serverul tău. Descărcarea
  continuă și cu aplicația închisă. WAV/AIFF se comprimă lossless în FLAC (sau
  ALAC). Un link care deschide o pagină web e refuzat. Din alte aplicații: un
  Shortcut care deschide `harmony://import?url=<link>` apare în meniul Share.
- **Playlist Import**: Spotify (playlisturile tale, cu Client ID-ul aplicației
  tale de dezvoltator, același redirect ca pe Android:
  `com.harmony.app://spotify-playlist-callback`), orice playlist public Deezer
  sau o listă lipită „Artist - Titlu”. „Find lossless on Soulseek” caută fiecare
  melodie după durată (±2 s), încearcă cele mai bune 4 surse și o etichetează
  cu titlul, artistul, albumul și coperta din playlist.
- **Recognize**: ShazamKit ascultă până la 15 secunde. Recunoașterea are nevoie
  de serviciul ShazamKit activat pe bundle ID, ceea ce cere un cont Apple
  Developer plătit. Cu un Apple ID gratuit, aplicația îți spune asta clar.

Un fișier lossy (MP3/AAC) rămâne așa cum e: nu e transformat în FLAC, pentru
că asta l-ar mări fără nicio îmbunătățire de calitate.
