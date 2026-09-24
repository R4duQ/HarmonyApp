# SpotiFLAC: compatibilitate FLAC — inclus in Harmony 1.0

Aceasta arhiva contine surse modificate, nu un APK testat pe telefoane.
Pastreaza modificarile anterioare FLAC CD / FLAC max 24/96 si corectia Preparing.

## Ce s-a schimbat

- FFmpeg foloseste un set separat de biblioteci din arhivele incluse in APK.
  Nu mai depinde de initializarea yt-dlp sau de continutul extras anterior de acesta.
  Sunt necesare biblioteci comune din ambele arhive FFmpeg/Python, nu executarea Python.
- Pachetul este identificat prin SHA-256; fiecare biblioteca extrasa este verificata
  prin marime si CRC. O extragere incompleta/corupta este refacuta la pregatire.
  Aliasurile sunt materializate ca fisiere; nu este necesara crearea de symlinkuri.
  Sunt extrase numai bibliotecile partajate, nu modulele Python sau alte executabile.
- FFmpeg cauta intai bibliotecile sale, apoi dependintele comune si directorul nativ
  al APK-ului. Mediile istorice raman disponibile ca reincercari limitate.
- Construirea modulelor native foloseste explicit NDK 29.0.14206865 si
  `-DANDROID_STL=c++_shared`. FFmpeg cere `libc++_shared.so`; configuratia CMake
  statica implicita nu asigura includerea acesteia. Scriptul de impachetare release
  verifica acum prezenta/integritatea componentelor FLAC obligatorii in fiecare APK.
- Daca finalizarea M4A/MP4 sau aplicarea limitei de calitate esueaza, se poate incerca
  alta sursa lossless. Limita este verificata inainte de acceptarea sursei, astfel
  incat o sursa FLAC nativa sub limita poate functiona fara conversie locala.
- Erorile conversiei raman in Details. Verificarea furnizorului, anularea si
  erorile care necesita actiunea utilizatorului nu sunt ascunse de acest mecanism.
- Progresul nativ este citit numai in timpul transferului, nu peste finalizarea locala.

FLAC max 24/96 inseamna un plafon, nu transformarea tuturor pieselor in 24/96.
Sursele cu rezolutie mai mica raman la rezolutia lor. Fisierele peste limita nu
sunt importate daca reducerea nu poate fi finalizata. AAC/Opus/MP3 nu sunt promovate
la FLAC lossless. Redarea prin Bluetooth nu este acelasi lucru cu rezolutia fisierului.

## Verificari

- Compilare Kotlin/Compose pentru toate sursele aplicatiei, folosind API-urile reale
  din dependintele proiectului (clasa R de verificare are doar cele doua ID-uri necesare).
- 193 teste JVM ale proiectului: inclusiv extragere, aliasuri, cache corupt/lipsa,
  schimbare versiune, medii FFmpeg, fallback si politica plafonului audio.
- Inca doua teste locale cu arhivele reale FFmpeg/Python 0.18.1, ARM64 si x86_64:
  231 fisiere extrase per ABI, aliasuri materializate si CRC-uri verificate.
  Total: 195 teste trecute. Extragerea privata ocupa aproximativ 178 MiB pe ARM64
  si 211 MiB pe x86_64; se reutilizeaza pentru descarcarile urmatoare.
- Modulele C++ harmonydsp si harmonyshuffle compilate cu NDK 29 pentru ARM64 si
  x86_64; dependinta lor de libc++_shared.so a fost verificata in ELF.
- Auditul dependintelor FFmpeg a urmarit 79 nume de executabile/biblioteci per ABI.
  In afara bibliotecilor Android si libc++_shared.so furnizata de NDK, dependintele
  necesare sunt prezente in cele doua arhive incluse.
- Sintaxa scriptului release verificata cu bash -n.

Compilarea APK completa nu a putut fi validata local: Gradle a esuat in generarea
accesorilor cu AccessDeniedException pentru gradle-core-8.11.1.jar din cache.
Compilarea Kotlin si testele JVM nu substituie construirea APK, KSP/R8 si testele Android.
Nu s-a instalat/publicat nicio versiune si nu s-a testat o descarcare pe telefon.

## Limite importante

Proiectul ramane Android 10+ (API 29), ARM64 / x86_64; nu adauga suport pentru
telefoane Android pe 32 de biti sau iPhone. Nu este garantata compatibilitatea
cu toate telefoanele: cauza exacta raportata necesita textul complet din Details.

Auditul a identificat in dependinta externa FFmpeg 0.18.1 trei biblioteci cu
aliniere ELF de 4 KB: libwebp.so, libsharpyuv.so, libwebpmux.so. Pe dispozitivele
cu pagini de memorie de 16 KB, conversia poate necesita reconstruirea/inlocuirea
acestor biblioteci. Aceasta arhiva nu modifica binarele externe respective si
nu declara suport 16 KB complet. Fallback-ul la FLAC nativ poate evita conversia,
dar depinde de disponibilitatea melodiei la ceilalti furnizori.

## Construire si test pe telefoane

1. Deschide proiectul in Android Studio cu JDK 17, SDK 35, CMake 3.22.1 si NDK
   29.0.14206865. Construieste un APK nou; modificarea surselor nu actualizeaza APK-ul vechi.
2. Ruleaza `./gradlew :feature:downloads:testDebugUnitTest :app:assembleDebug`.
   Pentru actualizarea unei instalari existente trebuie folosita cheia de semnare
   corespunzatoare; nu dezinstala aplicatia doar pentru a ocoli o nepotrivire de semnatura.
3. Pe un telefon afectat, testeaza aceeasi piesa in FLAC CD si FLAC max 24/96,
   apoi o sursa care necesita reducere de la peste 96 kHz. Verifica fisierul final,
   rezolutia, durata si redarea completa. Testeaza si anularea unei conversii.
4. Daca eroarea continua, trimite textul complet din Details, modelul telefonului,
   versiunea Android si modul FLAC ales. Nu sunt necesare parole sau tokenuri.

Referinte pentru configuratia nativa:
[CMake / ANDROID_STL](https://developer.android.com/ndk/guides/cmake),
[cerintele Android 16 KB](https://developer.android.com/guide/practices/page-sizes).
