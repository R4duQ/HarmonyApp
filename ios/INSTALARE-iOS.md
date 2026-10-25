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

## 3. Aplicația

Jos e o bară de navigare plutitoare, din sticlă mată, cu cinci secțiuni:
**Home**, **Library**, **Playlists**, **Discover** și **Downloads** (cu
numărul de descărcări active). Deasupra ei stă mini-player-ul: vinilul
copertei iese din card și se învârte cât cântă muzica. Atinge-l sau trage-l
în sus ca să deschizi **Now Playing**. Trage-l stânga/dreapta pentru melodia
următoare/anterioară.

- **Home**: melodia curentă, Smart Mix, biblioteca pe scurt, ce ai adăugat
  recent, Equalizer și Engine diagnostics.
- **Library**: căutare, Songs / Albums / Artists, pagini de album și artist.
  Apăsare lungă pe o melodie: Play next, Add to queue, Add to playlist,
  Smart Mix from here.
- **Playlists**: playlisturile tale. Le creezi, le redenumești, le reordonezi
  și scoți melodii din ele. Playlisturile importate în Downloads apar aici
  singure, după ce s-au găsit melodiile.
- **Discover**: melodii care sună ca cea care cântă (după vectorul 24D),
  ce asculți cel mai des, favorite uitate, ce n-ai ascultat niciodată, hi-res.
- **Now Playing**: shuffle (Off, Random, Smart Shuffle), repeat, coada
  (istoric, reordonare, ștergere, de ce a fost aleasă fiecare melodie),
  Regenerate, egalizatorul și **HUD-ul Diagnostic Brain** (iconița cu
  creierul). HUD-ul arată vectorul de vibe S_t în 24 de bare, cele 5
  auto-verificări, motivele alegerilor și un buton de self-test.
- **Egalizatorul** (60 fps): spectru de 32 de bare cu puncte de vârf și 10
  slidere. Fiecare slider are degetul tău și un deget „fantomă”, care arată
  ce aplică motorul acum (cu Auto și atenuarea pe lovituri). EQ ON, AUTO LIVE,
  FLAT, preamp și valorile live.

## 4. Diagnostic: motoarele C++

Din Home › Engine diagnostics rulează pe iPhone exact motoarele C++ din
aplicația Android (`core/eq-engine` și `core/dsp-native`), fără copii:

- **Equalizer (MobileEqEngine)**: fiecare ieșire (difuzor, căști, Bluetooth,
  USB DAC) la 44,1 / 48 / 96 / 192 kHz, toate benzile +12 dB, Auto pornit,
  semnal peste 0 dBFS cu NaN/Inf injectate. Trece dacă nimic nu depășește
  plafonul ieșirii și nu se pierde niciun eșantion.
- **Smart Shuffle (Diagnostic Brain)**: auto-testul cu 100 de piese virtuale.

Următoarele module adaugă playerul complet peste acest nucleu.

## Muzica ta

Tab-ul **Library** arată toate melodiile, cu căutare și un player jos.

- **Files › On My iPhone › Harmony**: copiază muzica acolo. Harmony o găsește
  singur la pornire.
- **Orice alt folder din Files** (iCloud Drive, un stick USB, folderul altei
  aplicații): butonul de foldere din Library › **Add a folder from Files**.
  Harmony citește folderul pe loc, fără să copieze fișierele, și îl păstrează
  accesibil și după repornire. Melodiile din iCloud care nu sunt încă pe
  telefon sunt cerute de la iCloud și apar la următoarea citire.
- Atinge o melodie ca s-o pornești. Apăsare lungă: **Play next**,
  **Add to queue** sau **Smart Mix from here**.
- Butonul de shuffle are trei moduri: Off, Random și Smart Shuffle. Coada
  rămâne cum o vezi: Next, Previous sau atingerea unei melodii din coadă doar
  se mută în ea, iar Smart Shuffle adaugă melodii noi doar la capăt.
- Pe un DAC USB, Harmony cere rata melodiei (până la 192 kHz), ca fișierele
  hi-res să ajungă la DAC fără conversie de rată.

Smart Shuffle măsoară fiecare melodie (vectorul 24D). Cu aplicația deschisă,
o face pe loc. Ce rămâne nemăsurat termină iOS singur, cu Harmony închis, de
obicei noaptea, când telefonul e la încărcare. Pentru asta, lasă pornit
**Settings → General → Background App Refresh** pentru Harmony.

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

### Coada de descărcări

- Bara de sus: un link pornește descărcarea, orice alt text caută pe Soulseek.
- **Active Queue**: ce rulează, ce așteaptă, ce e pe pauză și ce a eșuat, cu
  Pause, Resume, Retry și Cancel. Rulează cel mult 3 transferuri Soulseek
  deodată, iar celelalte își așteaptă rândul. Pauza și o descărcare întreruptă
  continuă de la octeții deja primiți.
- **Completed**: melodiile terminate, etichetate, în bibliotecă și cu
  insigna „24D Brain Indexed ✔” după ce au fost măsurate pentru Smart Shuffle.
- **Import Folder**: adaugi un folder din Files (citit pe loc) sau imporți
  fișiere în Harmony › Imported.
- **Ecran blocat**: cât timp rulează transferuri apare insigna
  „BACKGROUND GUARD: ACTIVE • SCREEN-LOCK SAFE”. Dacă nu cânți muzică,
  Harmony ține deschisă o sesiune audio tăcută, ca iOS să nu înghețe
  transferurile, și o închide imediat ce s-a terminat ultimul. O poți opri din
  Active Queue › „Keep downloads running when locked”.
- La trecerea de pe Wi-Fi pe date mobile, Harmony se reconectează la Soulseek
  și reîncearcă o dată ce a eșuat între timp.
