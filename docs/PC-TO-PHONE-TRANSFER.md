# Transfer permanent PC → telefon („Trimite pe telefon”)

Funcție separată de Cast/Harmony Connect (redare temporară) și de Universal Queue. Aici fișierul original ajunge permanent pe telefon și intră în biblioteca lui.

## 1. Ce există (verificat în cod)

| Zonă | Fișiere | Ce face |
|---|---|---|
| Descoperire | `core/remote/Discovery.kt` | UDP broadcast; PC-ul răspunde cu `PcInfo` (id, nume, port) |
| Asociere | `core/remote/ConnectReceiver.kt` (`Pairing`) | cod de 4 cifre → token per telefon. PC-ul păstrează `token → nume telefon` (`settings.phones`), telefonul păstrează `id PC → host, port, token` (`ConnectSession`, prefs `harmony_connect`) |
| Autentificare | `ConnectReceiver.handle` | header `X-Harmony-Token`; fără token valid → 403. Sesiunea de redare (`X-Harmony-Session`, `PhoneSessions`) e separată |
| Transport | `MiniHttp.kt`, `ConnectClient.kt`, `cache/Fetcher.kt` | HTTP/1.1 pe socket-uri simple (Android refuză HTTP necriptat prin clienții obișnuiți); un fir per conexiune pe PC; `HttpResponse.ranged` cu `Range`, `If-Range`, `ETag`, `If-None-Match` |
| Fișiere servite | `/media/<id>` (Universal Queue, etapa 6), `PhoneFileServer` | redare temporară; nu salvează nimic în bibliotecă |
| Reluare | `cache/MediaCache.kt` | fișier `.part`, reluare cu `If-Range`, backoff; fără hash complet (e cache, nu arhivă) |
| Publicare pe telefon | `feature/downloads/MusicDownloadRepository.kt` | fișier temporar privat → MediaStore (`Music/Harmony/Downloads`, `IS_PENDING`) sau folderul ales de utilizator (SAF) |
| Import în bibliotecă | `core/media/watcher/MediaChangeWatcher.kt` → `LibraryScanner` → `ScanLibraryUseCase` | `ContentObserver` pe MediaStore, cu debounce de 2 s; scanare incrementală (citește doar fișierele noi); `LibraryAutoUpdater` programează apoi analiza 24D prin WorkManager |
| Identitate fișier | `core/media/scanner/FileHasher.kt`, coloana `songs.fileHash` | SHA-256 peste primii 64 KiB + mărimea; `ScanIdentity` nu identifică după nume |
| Fundal | `AlbumDownloadWorker` | `CoroutineWorker` + `setForeground` de tip `dataSync`, notificare cu Pauză |
| iOS | `ios/` | nu are Harmony Connect deloc: transferul e doar pentru Android |

**Presupuneri, nu verificate:** că MediaStore redenumește singur un nume care se repetă în același folder („nume (1).flac”). Codul nu se bazează pe asta: verifică întâi dacă o copie identică există deja.

## 2. Opțiuni comparate

| Opțiune | Pentru | Contra |
|---|---|---|
| **A. Telefonul trage fișierele de la PC prin serverul Connect existent** (ales) | Folosește asocierea, tokenul, serverul, reluarea și validatorii existenți. Telefonul nu are nevoie de port deschis. Funcționează și în fundal (WorkManager). Ruta e separată de comenzile de redare | Telefonul trebuie să afle de ofertă: o întreabă cât aplicația e deschisă (sau cât redă pe PC) |
| B. PC-ul împinge fișierele spre un server pe telefon | PC-ul controlează direct | Telefonul ar trebui să asculte permanent: Android oprește procesele din fundal, iar un port deschis lărgește suprafața de atac. `PhoneFileServer` rulează doar în sesiune |
| C. Un protocol nou (WebSocket, gRPC, QUIC, Nearby) | Notificare instant | Dependențe noi și a doua cale de autentificare. Nimic din ce cere funcția nu lipsește din A |

Concluzie: **A**, peste HTTP-ul existent. Fără dependențe noi.

## 3. Arhitectura aleasă

```
PC (Harmony pentru Windows)                         Telefon (Android)
 Songs/Album → „Send to phone” → alegi telefonul
 TransferOutbox: oferta (id) cu elemente (id),
   SHA-256 complet + hash-ul bibliotecii + mărime
   GET  /transfer/offers          <── întreabă la ~8 s, cât aplicația e deschisă
   GET  /transfer/offer/<id>      <── manifestul
                                      [Accept? după preferința pentru acest PC]
                                      WorkManager (dataSync, notificare):
   GET  /transfer/file/<o>/<i>    <── Range + If-Range: <ETag>, câte un fișier
        200/206 în flux, 503 dacă sursa e re-calculată,
        404 dacă a dispărut, 410 dacă oferta e anulată
                                      .part în flux + SHA-256 → mărime și hash OK →
                                      publicare (MediaStore IS_PENDING) → registru
   POST /transfer/report/<o>      <── stările elementelor (cu numărul încercării)
 UI: progres, Anulează, Reîncearcă eșuatele
```

- **Canalul de date e separat de cel de control.** Fiecare fișier merge pe propria conexiune HTTP. Rutele `/transfer/*` sunt servite în afara lacătului de sesiune și nu ating `PhoneSessions`, deci nu pot ține în loc un Play, un Pause sau un `/status` și nu pot marca sesiunea ca „Reconnecting”.
- **Autorizare:**
  - doar tokenurile asociate;
  - o ofertă aparține tokenului telefonului ales: alt telefon primește 404, la fel ca un id inexistent;
  - PC-ul servește doar fișierele puse în ofertă, după id, niciodată după cale;
  - tokenul merge în header, nu în URL, deci nu ajunge în loguri.
- **Integritate:**
  - SHA-256 al întregului fișier e calculat pe PC înainte de ofertă;
  - telefonul îl recalculează în timp ce primește (iar la reluare citește din nou partea existentă);
  - mărimea și hash-ul trebuie să fie identice, altfel nimic nu e publicat.
- **ETag-ul** = hash + versiunea elementului.
  - Dacă fișierul se schimbă pe PC (mărime sau dată), PC-ul îl recalculează și crește versiunea; până atunci răspunde 503.
  - Telefonul vede alt ETag, cere din nou manifestul și reîncepe elementul de la zero, controlat.
- **Reluare:**
  - pe telefon, `.part` și meta-datele lui stau în `filesDir/transfers` (nu în cache, pe care Android îl poate goli);
  - după o întrerupere sau o repornire, transferul continuă de la octetul unde a rămas, dacă ETag-ul e același.
- **Idempotență:**
  - **Registrul** (`transfers/ledger.json`) ține: oferta și elementul → URI publicat, plus hash → URI.
  - Un element deja publicat (inclusiv după restart) nu e publicat a doua oară.
  - Publicarea verifică întâi dacă există deja în folder un fișier cu același nume, aceeași mărime și același hash parțial.
  - Rapoartele cu o încercare veche sau pentru o ofertă anulată sunt refuzate (409/410).
- **Duplicate (niciodată după titlu sau nume):**
  - același SHA-256 deja primit → „deja pe telefon”;
  - același hash de bibliotecă (primii 64 KiB + mărimea, aceeași rețetă ca `FileHasher`) → „deja în bibliotecă”;
  - alt hash înseamnă altă versiune, masterizare sau calitate: se importă, chiar dacă titlul e la fel.
- **Formate:**
  - fișierul original, fără transcodare;
  - PC-ul marchează ce nu poate reda telefonul (APE, WavPack, WMA, DSD, ALAC, AIFF), iar dialogul le arată;
  - implicit nu sunt trimise; o bifă le trimite totuși, iar telefonul le raportează ca incompatibile.
- **Spațiu:**
  - înainte de fiecare element, telefonul cere spațiu pentru `.part` + copia publicată + 50 MB;
  - o scriere care umple stocarea → eșec „no space”, iar `.part` e șters.
- **Resurse:**
  - un singur fișier pe rând;
  - buffer de 64 KiB, fără fișier întreg în RAM;
  - viteză limitată (8 MB/s, 2 MB/s cât telefonul redă pe un PC);
  - reîncercări limitate (6 în aceeași rulare, apoi WorkManager cu backoff, maximum 10 rulări);
  - anularea închide socket-ul și șterge fișierele temporare.
- **Import:**
  - publicarea în MediaStore declanșează fluxul existent: observatorul (debounce 2 s) → scanare incrementală → analiza 24D programată de WorkManager;
  - nicio scanare completă per fișier.
- **Curățenie:** fișierele `.part` ale ofertelor terminate, anulate sau mai vechi de 7 zile sunt șterse la pornirea worker-ului. Rândurile MediaStore `IS_PENDING` rămase după o oprire bruscă expiră singure.

### Stări

`PENDING → TRANSFERRING ⇄ INTERRUPTED → VERIFYING → IMPORTING → DONE`, plus `CANCELLED` și `FAILED` (cu motiv: `NO_SPACE`, `DISCONNECTED`, `PERMISSION`, `SOURCE_MISSING`, `CHECKSUM`, `SOURCE_CHANGED`, `DECLINED`, `UNSUPPORTED`, `OTHER`). `DONE` poate avea notă: `ALREADY_ON_PHONE`, `IN_LIBRARY`.

## 4. Limitele platformei (din documentația oficială)

- **Fundal pe Android:**
  - transferul rulează ca worker WorkManager cu serviciu foreground de tip `dataSync`, pornit doar din acțiunea utilizatorului (acceptarea);
  - Android 15 limitează `dataSync` la 6 ore pe 24 de ore pentru aplicațiile care țintesc Android 15 (Harmony: targetSdk 35); deschiderea aplicației resetează limita ([timeouts], [A15]).
- **Ofertele noi** sunt văzute doar cât Harmony e deschis pe telefon (sau cât redă pe PC). Telefonul nu poate fi „trezit” de PC: un serviciu care ascultă permanent ar consuma baterie și Android îl oprește.
- **Telefon blocat:** transferul deja pornit continuă în worker; Doze poate încetini rețeaua. Nu promitem viteză cu ecranul stins.
- **MediaStore:** `IS_PENDING` dă acces exclusiv cât se scrie; un rând nepublicat expiră automat ([media]).
- **iOS:** fără Harmony Connect, deci în afara acestei versiuni.

[timeouts]: https://developer.android.com/develop/background-work/services/fgs/timeout
[A15]: https://developer.android.com/about/versions/15/behavior-changes-15
[media]: https://developer.android.com/training/data-storage/shared/media
[long-running]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
RFC 9110 (Range, If-Range, ETag): https://www.rfc-editor.org/rfc/rfc9110

## 5. Plan

1. Protocol și logica pură în `core/remote/transfer` (PC: `TransferOutbox`; telefon: `TransferRunner`), testate pe socket-uri reale: un fișier, verificare, import (simulat).
2. Progres, anulare, erori.
3. Reluare după întrerupere și repornire.
4. Loturi, duplicate, spațiu, limită de viteză.
5. Conviețuirea cu redarea: rute în afara lacătului de sesiune, limită de viteză cât telefonul redă pe PC.
6. Interfața PC (Send to phone, alegerea telefonului, panoul de transferuri) și cea de pe telefon (acceptare, prima explicație, notificări), plus documentarea limitelor.
