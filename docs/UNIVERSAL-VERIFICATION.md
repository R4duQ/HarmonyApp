# Verificare SpotiFLAC universal — 2026-09-08

Baza: `Harmony-v1_0_1-audiofix.zip`. Versiunea livrată: `1.0.1-universal`,
versionCode 85. Arhiva include sursele și componentele native precompilate.

| Verificare | Rezultat |
| --- | --- |
| Backend Go real, SpotiFLAC v4.9.5, Go 1.26.6, NDK 29 | Compilat pentru ARM64 și x86_64; contractul Java verificat cu javap |
| Convertor FFmpeg 7.1.5 + LAME 3.100, API 29 | Compilat pentru ARM64 și x86_64 |
| Cele patru componente native SpotiFLAC | ELF pe 64 biți, ABI corect, aliniere LOAD de 16 KB, dependențe Android de sistem |
| FLAC în MP4: 16 biți/44,1 kHz, 24 biți/96 kHz, MP4 fragmentat | PCM identic după extragerea în FLAC |
| ALAC 24 biți/96 kHz → FLAC | PCM identic după conversie |
| MP3 320 kbps: fără copertă / PNG / JPEG | Fișier redabil, bitrate, artist, titlu și copertă verificate |
| AAC și container trunchiat | Respinse de convertorul portabil |
| Validarea pachetelor native | 10 teste trecute: ABI greșit/lipsă, cod 32 biți, ELF trunchiat, aliniere incompatibilă |
| Motor SpotiFLAC și ajutoare | 7 fișiere Kotlin compilate cu Kotlin 2.1.0, Android 35 și API-urile reale ale dependențelor |
| Regresii pentru ABI și anularea proceselor | 6 teste JUnit trecute |
| Scripturi, workflow YAML, versiune/tag, hash-uri | Verificate; tag-ul greșit este respins |
| Procesoarele ReplayGain și Equalizer | Identice byte-for-byte cu arhiva audiofix |

Total: **25 de teste focalizate** — 9 scenarii de conversie, 10 teste pentru
pachetele native și 6 teste JUnit. Nu reprezintă 25 de telefoane testate.
Testele au fost reluate după reconstruirea fișierelor temporare.

Rapoartele sunt în [verification/universal](verification/universal/).
Testele audio folosesc sunet sintetic și un convertor Linux compilat cu aceleași
componente ca cel Android; ieșirile sunt decodate independent pentru comparația
PCM. Nu contactează servicii muzicale.

## Limitele verificării

Nu a fost finalizat un build Android complet în acest mediu. Încercarea Gradle
a întâlnit componente SDK lipsă; reluarea nu s-a încheiat înainte ca fișierele
temporare să fie eliminate. Nu există un rezultat Gradle complet de tip
BUILD SUCCESSFUL sau un APK semnat inclus în această livrare.

Compilarea izolată nu verifică generarea KSP/Hilt/Room, UI-ul Compose complet,
resursele, manifestul sau împachetarea unui APK. Verificarea ELF nu înlocuiește
lansarea codului pe Android. Nu au fost executate teste pe telefoane fizice
sau emulatoare în această sesiune.

Workflow-ul de release construiește și verifică APK-ul universal și cele două
variante pe arhitecturi. Rezultatul lui trebuie verificat înainte de distribuire.
Bibliotecile imbricate ale vechiului runtime yt-dlp, păstrat pentru celelalte
descărcări și fallback, nu sunt certificate de verificarea convertorului portabil.

## Pe telefon, înainte de publicare

1. Instalează ca actualizare cu aceeași cheie, păstrând biblioteca.
2. Pe telefonul care avea eroarea, verifică descărcarea FLAC și MP3 cu copertă,
   apoi redarea fișierelor. Repetă pe un dispozitiv de altă marcă.
3. Verifică un album, descărcarea cu ecranul stins și anularea în timpul conversiei.
4. Verifică redarea cu EQ/ReplayGain și că piesele nu mai sunt sărite în buclă.
5. Pe un sistem Android cu pagini de 16 KB, verifică lansarea efectivă a ambelor
   componente native SpotiFLAC.

Suport: Android 10+ pe un sistem Android ARM64 sau x86_64. Nu se extinde la
Android pe 32 biți. Internetul, disponibilitatea sursei și autentificarea
furnizorului rămân condiții externe pentru descărcări.
