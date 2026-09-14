# SpotiFLAC: calitate selectabila pana la 24-bit / 96 kHz

In Downloads > SpotiFLAC, la **Audio format & maximum quality**, poti selecta:

- **FLAC CD**: pana la 16-bit / 44,1 kHz.
- **FLAC max 24/96**: solicita nivelul Hi-Res recunoscut de furnizor si limiteaza fisierul final la 24-bit / 96 kHz.
- **MP3 320 kbps**: optiunea existenta de conversie dintr-o sursa lossless.

Aceeasi selectie este disponibila pentru transferul playlisturilor Spotify si
descarcarea albumelor prin SpotiFLAC. Selectia din Downloads/Spotify se salveaza
in preferintele existente; albumele pastreaza selectia in jobul de descarcare,
inclusiv la pauza si reluare. Selectia existenta ramane valabila dupa actualizare.
Nu este necesara o migrare a bazei de date.

## Comportamentul optiunii FLAC max 24/96

| Sursa primita | Fisier salvat |
| --- | --- |
| 16-bit / 44,1 kHz | 16-bit / 44,1 kHz |
| 24-bit / 48 kHz | 24-bit / 48 kHz |
| 24-bit / 88,2 kHz | 24-bit / 88,2 kHz |
| 24-bit / 96 kHz | 24-bit / 96 kHz |
| 24-bit / 192 kHz | 24-bit / 96 kHz |
| 16-bit / 192 kHz | 16-bit / 96 kHz |

Rezolutia se citeste din STREAMINFO al fisierului FLAC. Fisierele sub plafon
nu sunt recodate audio. Fisierele peste plafon sunt reduse cu FFmpeg, apoi sunt
verificate rezolutia, numarul canalelor si durata. Daca reducerea nu reuseste,
fisierul peste limita nu este importat. Metadatele si coperta sunt pastrate.
Istoricul foloseste rezolutia reala a fisierului final.

Calitatea depinde de editia si sursa returnate de furnizor. Optiunea nu adauga
detalii inexistente unei inregistrari si nu modifica melodiile deja descarcate.
Resampling-ul reduce rezolutia; FLAC comprima fara pierderi rezultatul acestei
conversii. Rutarea si verificarea furnizorilor raman cele ale proiectului primit.

## Verificari efectuate

- Compilare izolata cu Kotlin 2.1.0 / Compose, Android 35 si bibliotecile reale:
  Sursele Kotlin de productie, plus un substitut pentru ID-urile R generate.
- Testele JVM includ politica de plafonare si compatibilitatea calitatii cu
  optiunile declarate de fiecare furnizor. Rezultatul verificarii este in
  `SPOTIFLAC-PREPARING-FIX.md`.
- Teste pentru pastrarea rezolutiilor native, plafonare, citirea STREAMINFO,
  respingerea antetelor invalide, durata, canale si serializarea selectiei.

Compilarea izolata nu genereaza APK si nu valideaza KSP/Hilt/Room, resursele si
impachetarea Android. Conversia FFmpeg pe Android si descarcarea de la furnizorii
live trebuie verificate pe telefon. Nu este inclus un APK compilat.

Pentru un build Android cu mediul proiectului configurat:

```powershell
.\gradlew.bat :feature:downloads:testDebugUnitTest :app:assembleDebug -PharmonyAbi=arm64-v8a
```

Verificare pe telefon: selecteaza FLAC max 24/96 pentru o melodie, un playlist
si un album; confirma rezolutia reala dupa descarcare. Verifica si o sursa peste
96 kHz, precum si persistenta selectiei dupa redeschidere/pauza/reluare.
