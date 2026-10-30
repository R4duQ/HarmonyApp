# Universal Queue: arhitectură și plan

Telefonul și PC-ul folosesc aceeași coadă. Poți combina cozile și muta redarea între dispozitive, păstrând piesa curentă, poziția, ordinea, Shuffle și Repeat. Un singur dispozitiv redă la un moment dat.

Prima versiune funcționează în aceeași rețea locală, iar PC-ul coordonează coada cât timp dispozitivele sunt conectate.

Pentru fiecare afirmație de mai jos am notat ce fel de bază are:
- **[Dovadă]**: confirmat de cod, de teste sau de documentație oficială;
- **[Recomandare]**: alegerea mea, cu motivul ei;
- **[Ipoteză]**: plauzibil, dar trebuie verificat pe dispozitive reale.

---

## 1. Ce există azi (Harmony Connect)

| Piesă | Unde | Ce face | Pentru Universal Queue |
|---|---|---|---|
| Protocol | `core/remote/ConnectProtocol.kt` | HTTP + JSON: `/play`, `/control`, `/status`, `/disconnect`; PC găsit prin descoperire UDP; asociere cu cod din 4 cifre și token | **Reutilizăm**: descoperirea, asocierea, token-ul și sesiunea. Adăugăm rute noi pentru coadă |
| Sesiuni | `core/remote/PhoneSessions.kt` | id de sesiune, 409 pentru sesiuni vechi, „Reconnecting” după 6 s, „lost” după 35 s | **Reutilizăm**: detectarea pierderii și respingerea sesiunilor vechi au trecut deja prin testele de rezistență |
| Server HTTP | `core/remote/MiniHttp.kt` | un fir pe conexiune, fiecare răspuns închide conexiunea; `Range` pentru fișiere | **Reutilizăm**. Comenzile și audio merg deja pe conexiuni diferite (servere diferite) |
| Telefonul | `playback/service/connect/ConnectSession.kt` | deține coada (Media3), trimite piesa curentă + 10 următoare, citește `/status` la 0,5 s | **Se schimbă**: telefonul nu mai e singurul stăpân al cozii |
| Fișierele telefonului | `PhoneFileServer.kt` | servește melodia cu `Range`, protejată de un secret pe sesiune | **Reutilizăm**; adăugăm ETag pentru reluarea transferurilor |
| PC-ul | `desktop/player/PlayerController.kt` | redă melodia telefonului sau biblioteca proprie; are coada lui separată | **Se schimbă**: o singură coadă |
| Cache PC | `desktop/connect/RemoteCache.kt` | doar piesa curentă + următoarea, într-un folder temporar golit la pornire, fără reluare | **Se rescrie**: cache persistent, cu limită, chei de conținut și reluare |
| Motor PC | `desktop/engine/AudioEngine.kt` | ffmpeg → EQ → placa de sunet; pregătește următoarea piesă (gapless) | **Reutilizăm**. Nu are crossfade, care e o etapă separată |
| Crossfade telefon | `playback/service/player/TwinPlayer.kt` | două ExoPlayer-e; anulează fade-ul dacă „următoarea” s-a schimbat | **Reutilizăm**: are deja regula „pregătit sau deloc” |
| Coada telefonului | `SmartQueueManager`, `QueueMoves` | Smart Shuffle reordonează fizic; secțiunea „Next up” e o lungime după piesa curentă | **Se schimbă** în etapa 5: proveniența devine o proprietate a fiecărei intrări |
| iOS | `ios/` | n-are deloc Harmony Connect | În afara primei versiuni |

---

## 2. Opinia mea tehnică

**Ce e bine** [Recomandare]:
- **Un singur dispozitiv activ**, care decodează, egalizează și face crossfade local. Asta evită sincronizarea audio între dispozitive, care e cea mai grea problemă în audio distribuit. Așa funcționează și Spotify Connect: un „cluster” cu un singur dispozitiv activ și transfer explicit [Dovadă: librespot].
- **Coordonator unic cât timp sunteți conectați.** Ordonarea operațiilor e simplă, iar conflictele se rezolvă într-un singur loc.
- **Fișierul original, nu un stream re-encodat.** Păstrează calitatea, iar redarea nu mai depinde de rețea după ce fișierul a ajuns.
- **„Crossfade doar dacă următoarea e pregătită”** e regula corectă; TwinPlayer o aplică deja.

**Ce e fragil sau prea complicat:**
1. **Dacă PC-ul decide și „Next” pentru telefonul care redă.** Atunci fiecare trecere la piesa următoare depinde de rețea, iar o pierdere de Wi-Fi la final de piesă devine liniște.
   - **Schimbarea mea:** separ două autorități.
     - Coordonatorul (PC) este autoritatea pentru **structura cozii**: inserări, mutări, ștergeri, shuffle.
     - Dispozitivul activ este autoritatea pentru **capul de redare**: ce piesă cântă, poziția, play/pause. Are mereu o copie locală a cozii și trece singur la următoarea, apoi anunță coordonatorul.
2. **Indicii ca adrese.** „Mută 5 la 2” nu mai înseamnă același lucru dacă celălalt dispozitiv a schimbat coada între timp. Bug-ul de reordonare reparat recent venea tot de aici.
   - **Schimbarea mea:** fiecare operație folosește id-ul intrării („mută intrarea X după intrarea Y”). O editare făcută offline își păstrează astfel sensul.
3. **Shuffle ca rearanjare fizică a întregii cozi**, cum face azi Smart Shuffle. Nu se împacă bine cu „manualul nu se mișcă”.
   - **Schimbarea mea:** shuffle permută doar intrările libere (listă sau recomandări nemutate), în pozițiile pe care le ocupau deja. Intrările protejate, piesa curentă și istoricul nu se mișcă.
4. **Polling la 0,5 s.** Înseamnă 2 conexiuni TCP noi pe secundă pe telefon, adică trezirea radioului și consum de baterie. În plus, comenzile și starea partajează aceeași buclă.
   - **Recomandare:** long-poll pentru evenimente (răspuns imediat la schimbare, altfel heartbeat la ~5 s) și comenzi trimise direct. Schimbarea atinge detectarea deconectării, deci o fac doar cu testul de rezistență existent ca plasă de siguranță.
5. **„Următoarea + încă 1–2” complete pe telefon.** Un album FLAC 24/192 înseamnă 100–200 MB pe piesă.
   - Pe telefon pregătesc „următoarea” complet. „Încă 1–2” doar pe Wi-Fi, cu baterie peste prag sau la încărcat, și cu spațiu liber.

**Alternative comparate:**

| Variantă | Pentru | Contra | Verdict |
|---|---|---|---|
| **A. Coordonator (PC) + jurnal de operații cu revizii** (ales) | simplu de testat, un singur arbitru, comenzile retrimise se recunosc după id, recuperarea e simplă (jurnal sau snapshot) | când PC-ul lipsește, nimeni nu arbitrează; dispozitivul activ continuă cu copia lui, iar editările făcute între timp așteaptă (vezi §3.5) | **Prima versiune** |
| B. CRDT de listă (Automerge/Yjs) pe ambele dispozitive | editare offline completă, fără arbitru | „move” într-o listă CRDT e o problemă încă de cercetare: mutările concurente pot produce duplicate sau cicluri; în plus, o dependență mare [Dovadă: Kleppmann 2020, Da & Kleppmann 2024] | Prea complex acum. Modelul pe id-uri lasă loc pentru trecerea la CRDT mai târziu |
| C. Telefonul rămâne singurul stăpân, PC-ul doar redă (azi) | deja stabil | nu există coadă comună; redarea nu poate rămâne pe PC fără telefon | Ce vrei să depășim |
| D. Coordonare prin cloud | merge și în afara casei | cont, server, latență, confidențialitate | Mai târziu, eventual |

---

## 3. Arhitectura recomandată

### 3.1 Modelul cozii [implementat: `core/remote/queue/UniversalQueue.kt`]

- **Intrare (`QueueEntry`)**:
  - `id` propriu (UUID), unic pentru fiecare apariție;
  - `origin`: `LIST`, `MANUAL` sau `AUTO`;
  - `pinned`: mutată explicit de utilizator;
  - `priority`: face parte din secțiunea „Play next”.
- **Media (`MediaRef`)**:
  - `contentKey` pentru cache: două intrări cu aceeași melodie folosesc o singură copie;
  - `home` (dispozitivul cu originalul) și `localId`.
- **Separarea cerută între intrare și fișier:** intrarea e un loc în coadă, `contentKey` e un fișier audio.
- **Regulile (testate):**
  - `MANUAL` și `pinned` sunt protejate: shuffle nu le mută, doar `Remove` le scoate;
  - Play next merge după piesa curentă și după „Play next”-urile anterioare;
  - Add merge după selecția manuală și înaintea recomandărilor; recomandările merg la final;
  - o recomandare mutată devine protejată;
  - shuffle nu atinge piesa curentă, istoricul sau intrările protejate; când îl oprești, intrările libere revin la ordinea dinainte;
  - pornirea unui album nou păstrează intrările protejate, imediat după piesa nouă. Așa face și Spotify cu „Next in queue”. **Întrebare pentru tine, §7.**

### 3.2 Sincronizarea [implementat: `QueueSync.kt`]

- **Comandă = id unic + operație pe id-uri de intrare + epoca de redare cunoscută.**
  - Comanda se retrimite cu același id până primește răspuns.
  - Coordonatorul ține minte ultimele 2048 de rezultate: o retrimitere primește același răspuns și nu se execută a doua oară.
  - E idempotența „la cel mult o dată” prin cheie de cerere.
- **Revizii.** Fiecare operație aplicată primește următorul număr. Un dispozitiv cere „ce s-a schimbat de la revizia N” și primește:
  - fie operațiile lipsă, dintr-un jurnal limitat (256);
  - fie, dacă a rămas prea în urmă, toată coada (snapshot).
  - O „gaură” în operații e detectată și nimic nu se aplică în ordine greșită.
- **Shuffle-ul îl decide coordonatorul.** Ordinea e trimisă explicit, nu sămânța aleatoare, ca versiuni diferite ale aplicației să nu ajungă la ordini diferite.
- **Afișare optimistă.** Dispozitivul arată imediat editarea lui peste ultima stare confirmată. Când vine răspunsul, se aliniază.
- **Conflicte (deterministe):**
  - mută × mută: câștigă cea care ajunge ultima la coordonator;
  - mută × șterge: ștergerea câștigă, mutarea e refuzată, iar afișarea se corectează singură;
  - inserările concurente se păstrează amândouă;
  - „Clear” șterge doar intrările pe care utilizatorul le vedea, deci melodiile adăugate concurent de pe celălalt dispozitiv rămân.
- **Epoca de redare (fencing).** Fiecare transfer de redare o crește. Comenzile de redare (`SetCurrent`, `SetOwner`) cu o epocă veche sunt refuzate. Așa „Next”-ul întârziat al dispozitivului care a pierdut redarea nu mai mută capul de redare, iar două transferuri simultane nu pot câștiga amândouă [Dovadă: principiul „fencing token”, Kleppmann 2016].

### 3.3 Canalul de comenzi și canalul audio

- Comenzile și audio folosesc deja servere/conexiuni diferite [Dovadă: cod]. Deci un transfer mare nu blochează la nivel de aplicație un Pause sau un Skip.
- Rămâne concurența pe Wi-Fi. Pe o legătură aglomerată, pachetele mici întârzie din cauza bufferelor [Ipoteză, de măsurat].
  - **Măsură:** transferurile de pre-încărcare au limită de viteză și se opresc cât timp are loc un handoff. Transferul piesei curente nu e limitat.
- **Reconectare:** backoff exponențial cu jitter. Telefonul are deja backoff până la 2 s. Jitter-ul evită ca reconectările să se sincronizeze [Dovadă: AWS, Brooker].

### 3.4 Handoff (schimbarea dispozitivului)

```
sursa (redă)                coordonator                     destinația
     |   PREPARE(intrare, poziție, epoca e) ----------------> |  fișier/stream gata, decodor deschis,
     |                                                        |  buffer umplut la poziție
     |   <-------------------------------- READY(buffer ms)   |
     |   COMMIT: SetOwner(dest) cu epoca e  ->  epoca e+1     |
     |   pauză la poziția P  ---- P ------------------------> |  pornește de la P
     |                                       <---- STARTED    |
```

- **Eșec înainte de COMMIT** (timeout, fișier lipsă, decodor nepregătit): nu se schimbă nimic, sursa cântă mai departe.
- **Eșec după COMMIT, dar înainte de STARTED** (în ~5 s):
  - coordonatorul trece redarea înapoi pe sursă (`SetOwner(sursa)`, epoca e+2);
  - sursa are piesa încărcată, pe pauză la P, și reia de acolo.
- **Ambiguitate:** sursa nu poate întreba coordonatorul dacă destinația a pornit (rețeaua a căzut exact atunci).
  - **Regula:** sursa rămâne pe pauză și arată „Redarea s-a mutat pe PC? — Continuă aici”.
  - **Nu repornește automat:** prefer liniștea în locul redării simultane pe două dispozitive [Recomandare].
- **Discontinuitate:** gol scurt, de ~100–300 ms [Ipoteză, de măsurat], nu suprapunere. Suprapunerea ar cere ceasuri sincronizate între dispozitive.
- **Crossfade în curs:**
  - commit-ul așteaptă terminarea fade-ului, maximum lungimea fade-ului;
  - PREPARE poate rula în paralel;
  - pe destinație, crossfade-ul următor se face doar dacă și piesa de după are decodorul și bufferul gata. Altfel piesa curentă se termină normal.

### 3.5 La deconectare

- **Dispozitivul activ continuă** cu ce are local: piesa curentă (completă în cache sau în bibliotecă) și următoarele pregătite. Trece singur de la o piesă la alta, din copia lui a cozii.
  - Piesele încă nedescărcate de pe celălalt dispozitiv sunt sărite (cu notificare). Asta nu le scoate din coadă.
- **Editările făcute între timp** așteaptă în outbox. La reconectare se trimit cu id-urile lor. Fiind adresate prin id-uri de intrare, își păstrează sensul (testat).
- **Reconectarea:**
  - nu repornește muzica, nu suprascrie coada (revizii + jurnal) și nu pornește al doilea player;
  - doar proprietarul cu epoca curentă poate reda.
- **Limită reală:** dacă PC-ul dispare definitiv, iar telefonul a continuat (și invers), cele două copii pot diverge.
  - Telefonul devine coordonator local pentru propria copie.
  - La reconectare, dispozitivul care a redat ultimul dictează capul de redare. Editările de structură se reaplică (sunt idempotente și pe id-uri), iar intrările protejate nu se pierd.
  - Ce nu se poate garanta: ordinea relativă a două editări concurente pe aceeași intrare, făcute pe dispozitive deconectate (rămâne „ultima ajunsă câștigă”).

### 3.6 Cache

Diferențe față de `RemoteCache` actual:
- **Locație:**
  - pe PC, în folderul aplicației (nu în temp golit la fiecare pornire);
  - pe telefon, în `cacheDir`, în afara bibliotecii și a MediaStore. Deci fără import și fără reindexare 24D.
- **Cheie:** `contentKey`. Intrările repetate folosesc aceeași copie.
- **Limită:** cel mai mic dintre 2 GB pe PC / 1 GB pe telefon (propunere, §7) și 10% din spațiul liber. Ștergerea e LRU și protejează piesa curentă și următoarea pregătită.
- **Reluarea transferurilor:** `Range: bytes=N-` cu `If-Range: <ETag>`. Dacă fișierul s-a schimbat, serverul trimite 200 cu tot fișierul, deci nu se lipesc bucăți din fișiere diferite [Dovadă: RFC 9110 prin MDN]. Scrierea se face într-un fișier `.part`, redenumit doar când e complet.
- **Integritate:**
  - mărimea se verifică întotdeauna;
  - o eroare de decodare din cache duce la ștergere și o singură redescărcare; dacă se repetă, piesa se redă direct prin stream.
- **Prioritate:** curentă > următoarea > +1 > +2.
  - Se recalculează la fiecare revizie (Skip, Play next, reordonare).
  - Transferurile ieșite din fereastră se anulează, cu excepția celor aproape gata.
- **Spațiu insuficient:** fără pre-încărcare; piesa curentă se redă direct prin stream (Range), iar utilizatorul vede un mesaj.
- **Formate pe telefon:** ExoPlayer citește FLAC, WAV, MP3, AAC, Opus, Vorbis; ALAC doar cu extensia FFmpeg [Dovadă: Media3 supported formats]. Pentru APE, WavPack, WMA, DSD din biblioteca PC-ului:
  - PC-ul le convertește cu ffmpeg în FLAC (fără pierderi suplimentare), cu `contentKey` derivat;
  - sau sunt redate doar pe PC.
  - „Originalul” nu e posibil literal aici, doar calitatea lui.

### 3.7 Fundal și baterie (Android)

- Redarea pe telefon rulează într-un `MediaSessionService` cu `foregroundServiceType="mediaPlayback"`. Aplicația îl declară deja [Dovadă: manifest, documentație Android].
- **Limită reală:**
  - Android 12+ nu permite pornirea unui serviciu foreground din fundal, cu excepții [Dovadă: documentație Android].
  - Deci un PC nu poate „împinge” redarea pe un telefon cu aplicația închisă sau în fundal fără sesiune activă.
  - Handoff-ul spre telefon e sigur când telefonul e deja în sesiune: serviciul e în foreground ca telecomandă. Altfel se pornește de pe telefon, printr-o atingere.
  - Android 17 restrânge și mai mult redarea audio din fundal fără serviciu foreground pornit cât aplicația era vizibilă [Dovadă: documentația Android 17].
- **Wi-Fi în fundal:**
  - `WIFI_MODE_FULL_LOW_LATENCY` funcționează doar cu ecranul aprins și aplicația vizibilă;
  - Doze restrânge rețeaua aplicațiilor fără serviciu foreground [Dovadă: documentație Android].
  - Deci pre-încărcările mari se fac cât timp serviciul e în foreground (redare/sesiune) și sunt limitate la „următoarea” când bateria e scăzută.
- **iOS:** nu are Connect; e în afara primei versiuni.

---

## 4. Plan etapizat cu criterii de acceptare

| # | Etapa | Criterii de acceptare | Stare |
|---|---|---|---|
| 0 | Reordonare corectă pe Android (PR #58) | mutări pe id, nu indici vechi; 12 teste de drag reale | ✅ publicat (v1.0, versionCode 148) |
| 1 | **Modelul cozii comune** (Kotlin pur, partajat Android + Windows) | regulile din §3.1 verificate de teste fixe și de ~10.000 de editări aleatoare | ✅ implementat, testat local |
| 2 | **Sincronizare** (coordonator + replici) | rețea cu 25% pierderi, 15% duplicate, întârzieri și reordonare, două căderi de rețea, 3 dispozitive care editează simultan: după vindecare toate arată exact coada coordonatorului; nicio comandă aplicată de două ori; nicio intrare manuală pierdută fără Remove; comenzi de redare cu epocă veche refuzate | ✅ implementat, testat local (50 de scenarii) |
| 3a | Protocolul cozii pe rețea (Kotlin pur) | rute `/queue/commands` și `/queue/since` (long-poll, max 4 s, sub pragul „Reconnecting” de 6 s); cererea ținută deschisă nu blochează play, pauză sau status; un PC fără coadă răspunde 404, iar legătura se oprește liniștit; sesiunile încheiate sunt refuzate | ✅ implementat, 8 teste pe socket-uri reale, cu proxy care pierde, refuză și întârzie |
| 3b-1 | Telefonul își oglindește coada pe PC; PC-ul o afișează. Opțiunea „Shared queue (beta)” e pe PC, oprită implicit | coada de pe PC devine exact cea a telefonului (ordine, piesa curentă, proveniență) prin căderi de rețea; fără ecou (nimic de retrimis la final); fiecare operație aplicată o dată; legătura nu atinge redarea; PC fără opțiune → telefonul lucrează ca înainte | ✅ implementat. Automat: 2000 de ținte aleatoare atinse exact; pe socket-uri cu cădere de rețea, 120 de schimbări → 141 de operații, toate aplicate exact; afișarea pe PC testată. **Neverificat pe dispozitive** |
| 3b-2 | Editările de pe PC ajung în playerul telefonului: Play next, sus, jos, ștergere, redare (clic pe rând) | aplicate prin aceleași apeluri verificate ca drag-ul telefonului (melodia așteptată trebuie să fie la locul ei), fără restart; ce refuză telefonul e anulat în coada comună; editările simultane telefon + PC converg prin căderi de rețea | ✅ implementat. Automat, pe socket-uri: 150 de pași cu editări pe ambele părți și o cădere de rețea → aceeași coadă pe ambele, fiecare operație aplicată o dată; o editare refuzată de telefon e anulată pe PC; butoanele PC-ului produc exact operațiile așteptate. **Neverificat pe dispozitive** |
| 4 | Cache v2 pe PC (`core/remote/cache/MediaCache`, refolosibil pe telefon în etapa 6) | reluare după întrerupere, fișier corupt → redescărcare, disc plin → stream, nicio copie dublă pentru intrări repetate, memorie constantă (buffer 256 KB) | ✅ implementat. Automat: transfer tăiat → reluat de la octetul unde s-a oprit, cu `If-Range` (descărcat ≈ o dată); fișier schimbat între încercări → vine întreg, nelipit; piesa curentă preia transferul, cealaltă se reia după; limită de viteză doar la pre-încărcare; LRU fără curenta/următoarea; fără spațiu → stream; copie coruptă → încă o descărcare, apoi doar stream; după repornire: copiile rămân, parțialele se reiau; pe PC, cu ffmpeg real: redare și căutare din copie, copie stricată → redarea continuă și copia e refăcută. **Neverificat pe dispozitive** |
| 5 | Telefonul ține minte proveniența fiecărei intrări | Smart Shuffle lucrează pe proveniență, nu pe poziții; regula 6 (manual protejat) testată în `SmartQueueManagerQueueTest`; un id per intrare, și pe PC | ✅ implementat. Automat: 7 teste noi de Smart Shuffle (plus cele 24 vechi, neschimbate) și 10 pentru `QueueShadow`; test de mutație: dacă Smart Shuffle ignoră proveniența, 7 teste pică; pe PC, aceeași melodie de două ori rămâne două intrări distincte când una e mutată. **Neverificat pe telefon** |
| 6 | PC → telefon: server de fișiere pe PC + cache pe telefon | piese din biblioteca PC-ului redate pe telefon din cache; fără import în bibliotecă și fără 24D | ✅ implementat. Automat: ruta `/media` cu token, intervale și „încă se convertește” (503), descărcată de cache-ul telefonului pe socket-uri reale; conversie reală cu ffmpeg WavPack → FLAC (aceeași durată), o singură dată; fișier care nu se poate converti → 404, nu reîncercări la nesfârșit; 120 de pași pe socket-uri, cu cădere de rețea, în care PC-ul adaugă 33 de piese: fiecare ajunge o dată în playerul telefonului, ca manuală, iar cozile ajung identice; PC-ul își redă propriul fișier când telefonul i-l trimite; Smart Shuffle nu trimite piesele PC-ului motorului de recomandări. **Neverificat pe dispozitive** |
| 7 | Handoff în ambele sensuri | §3.4: fără redare dublă în niciun punct de eșec (test cu eșec injectat la fiecare pas); handoff < 1 s pe Wi-Fi bun [țintă de măsurat] | |
| 8 | Crossfade pe PC | motorul PC face crossfade doar cu următoarea decodată în buffer; altfel trecere normală | |
| 9 | Testare pe dispozitive reale | matricea din §5 | |

---

### Ce face 3b-1 concret (și ce nu)

- **PC:** Connect → „Shared queue (beta)”. Pornit, cât timp un telefon redă pe PC, panoul Queue arată toată coada telefonului (până la 500 de piese), cu eticheta „Next up”, „Queued” sau „Recommended”. Coperțile apar pentru piesele trimise deja de telefon.
- **Telefon** (`ConnectQueueBridge`): cât durează sesiunea Connect, oglindește fereastra „ultimele 50 + curenta + următoarele 500” (cu 300 ms de grupare a schimbărilor rapide) prin aceeași sesiune, deci un Stop de pe PC închide și coada.
- **3b-2:** în panoul Queue al PC-ului, fiecare melodie din coada comună are butoanele Play next, sus, jos și ștergere; un clic pe rând o redă. Editarea merge întâi în playerul telefonului:
  - se aplică doar dacă melodia e încă acolo unde o vedea PC-ul;
  - altfel coada comună revine la a telefonului.
- **Ce nu face încă:**
  - piesele de pe PC nu intră încă în coada comună (etapa 6).

### Ce face etapa 5 concret

- **Proveniența** (`QueueShadow`, în `domain/playback`): o listă paralelă cu playerul Media3, în aceeași ordine. Fiecare intrare are:
  - un id propriu (aceeași melodie de două ori = două intrări);
  - originea: listă, manuală (Play next, Add to queue) sau Smart Shuffle;
  - dacă a fost mutată de utilizator (fixată).
- `PlaybackConnection` o actualizează la fiecare operație pe player. Schimbările făcute din afară (Android Auto, sesiune restaurată) sunt potrivite melodie cu melodie; ce e nou contează ca listă.
- **Smart Shuffle** decide după proveniență, nu după poziție. Protejate (nu sunt șterse, mutate sau înlocuite de shuffle): melodiile manuale, oriunde ar fi, și orice melodie mutată de mână, inclusiv o recomandare.
  - **Pornire Smart:** pleacă doar melodiile listei rămase nemișcate. Înainte, un „Add to queue” de la final sau o melodie scoasă din „Next up” erau șterse.
  - **Regenerare:** sunt înlocuite doar recomandările nemișcate.
  - **Random:** amestecă doar melodiile libere, fiecare protejată rămâne exact pe locul ei.
  - **Oprire shuffle:** ordinea originală revine, iar melodiile protejate rămân cam unde erau (după atâtea melodii câte erau libere înaintea lor). Random → Off dă înapoi exact coada de dinainte, iar un „Add to queue” rămâne la final, nu mai sare în față.
- **Album nou** (decizia 1, acum și în player): melodiile manuale care urmau se păstrează imediat după piesa aleasă, și în Smart, și în Random.
- **PC:** primește id-ul fiecărei intrări și dacă e fixată, deci o mutare între două copii ale aceleiași melodii nu mai e confundată.
- **Limită:** proveniența nu se salvează. După ce Android oprește aplicația, coada restaurată e toată „listă”.

### Ce face etapa 6 concret

- **Pe PC:** cu „Shared queue (beta)” pornit și un telefon care redă pe PC, fiecare melodie din Songs are la hover două butoane: „Play next on the phone” și „Add to the phone's queue”.
  - Melodia intră în coada comună ca manuală.
  - Play next o pune după rândul „Next up”; Add o pune după melodiile tale și înaintea recomandărilor.
- **Pe telefon:** melodia intră în player ca o intrare a ta.
  - Nu intră în bibliotecă: fără rând în baza de date, fără analiză 24D, fără număr de redări, fără favorite.
  - Are un id negativ, care nu poate fi niciodată un id din bibliotecă.
- **Redare:**
  - **Telefonul redă pe PC:** PC-ul primește cheia melodiei, recunoaște că e a lui și o redă direct din fișierul propriu. Nu o descarcă de la telefon.
  - **Telefonul redă singur** (după Stop sau după ce se pierde legătura): o redă din copia lui. Dacă nu are copia întreagă, o citește direct de la PC, cu căutare prin intervale.
  - **PC-ul nu răspunde și nu există copie:** piesa e reîncercată o dată, apoi sărită, ca orice piesă care nu se încarcă. Rămâne în coadă.
- **Cache-ul telefonului:**
  - stă în `cacheDir/connect-media`, nu în bibliotecă și nu în MediaStore; Android îl poate goli când rămâne fără spațiu;
  - păstrează piesa curentă și următoarele 3 care sunt de pe PC, cu același cod ca pe PC (reluare, LRU);
  - limita e 1 GB și niciodată peste 10% din spațiul liber.
- **Formate:**
  - MP3, FLAC, AAC, Ogg/Opus și WAV pleacă de pe PC așa cum sunt;
  - APE, WavPack, WMA, DSD, ALAC și AIFF sunt convertite o singură dată în FLAC, fără pierderi (DSD devine 24-bit/88,2 kHz), și păstrate în `connect-converted`, cu limită de 2 GB;
  - cât durează conversia, telefonul primește „mai încearcă” și așteaptă până la 30 s;
  - PC-ul își redă tot originalul.
- **Securitate:**
  - doar telefoanele împerecheate pot descărca, cu tokenul lor;
  - doar cât e pornit „Shared queue”;
  - id-ul unei melodii e un hash din cale și data modificării, nu calea însăși.
- **Limite:**
  - piesele PC-ului nu se păstrează după o repornire a aplicației: coada restaurată are doar piesele din bibliotecă;
  - limita cache-ului de pe telefon nu se poate schimba încă din setări (e fixă la 1 GB);
  - cât telefonul redă pe PC, playerul telefonului (pe pauză) poate citi și el începutul piesei curente de la PC, deci o parte se transferă de două ori;
  - o piesă de pe alt PC decât cel conectat acum e refuzată de telefon.

### Ce face etapa 4 concret

- **Locul:** `%LOCALAPPDATA%\Harmony\connect-cache` (local, nu roaming), departe de folderele de muzică, deci niciodată scanat în bibliotecă. Vechiul folder temporar e golit la pornire.
- **Cheia:** cheia de conținut trimisă de telefon (`dev:<telefon>:<melodie>`), aceeași în toate sesiunile. O melodie ascultată din nou mâine nu se mai descarcă. Telefoanele vechi folosesc „telefon + id”.
- **Ce se descarcă:** piesa curentă, următoarea și încă două, în ordinea asta. Telefonul trimite acum URL-uri pentru primele 3 din „up next”, nu doar pentru prima. Pre-încărcarea are limită de 8 MB/s, ca să lase loc pe Wi-Fi; piesa curentă nu are limită.
- **Limita** se alege în Connect: 1, 2 (implicit), 5, 10 sau 20 GB, dar niciodată peste 10% din spațiul liber. Tot acolo e și butonul „Empty”.
- **Validare:** telefonul trimite acum un `ETag` pentru fiecare fișier (mărime, data modificării, document) și răspunde la `If-Range` / `If-None-Match`.
- **Telefonul:** cache-ul de pe telefon (redarea pieselor de pe PC) vine în etapa 6, cu același cod.

## 5. Ce testăm și cum

| Scenariu | Automat (unde) | Pe dispozitive |
|---|---|---|
| Rețea lentă / întreruperi în redare | simulare (etapa 2); `ConnectSoakTest` cu proxy de latență, pierderi, refuz (etapa 3) | Wi-Fi slab, router oprit 10 s / 60 s |
| Reconectare + comenzi retransmise | `QueueSyncTest`, simulare (cu test de mutație: fără deduplicare pică) | retrimitere forțată |
| Editări simultane | `QueueSyncTest` (mută × mută, mută × șterge, offline × online) | două ecrane, aceeași secundă |
| Shuffle peste coadă manuală | `QueueRulesTest`, `QueuePropertyTest` | ✓ |
| Schimbarea dispozitivului în timpul unui crossfade | etapa 7 (motor simulat) | ✓ |
| Aplicația în fundal | — (nu se poate automat) | ecran stins, Doze (`adb shell dumpsys deviceidle force-idle`) |
| Cache incomplet, corupt, disc plin | etapa 4 | ✓ |

Ce măsurăm (etapele 3–9): goluri audio (număr și durată), timpul de handoff, cât de des „următoarea” e gata la timp, memoria (heap PC, RSS telefon), trezirile radioului și bateria (Battery Historian), octeții transferați față de octeții redați.

---

## 6. Ce e verificat acum și ce nu

- **Verificat automat:**
  - regulile cozii;
  - sincronizarea pe o rețea simulată cu pierderi;
  - formatul JSON (inclusiv refuzul operațiilor necunoscute, ca un dispozitiv nou să poată vorbi cu unul vechi);
  - legătura pe socket-uri reale (`QueueLinkTest`):
    - o editare de pe PC ajunge pe telefon în sub 0,5 s;
    - după o cădere cu editări pe ambele părți, coada e din nou sincronizată la ~0,2 s de la revenire, fiecare editare o singură dată;
    - o conexiune refuzată e reîncercată cu backoff, nu în buclă;
    - o coadă inactivă costă 3 cereri la 12 s, față de 24 cu polling-ul la 0,5 s.
- **Pe dispozitive reale:** nimic încă. Etapele 1–2 nu ating playerul și nici rețeaua reală; etapa 5 schimbă playerul telefonului și e verificată doar prin teste cu un player simulat și prin compilare în CI.
- **Neconfirmat:** golul la handoff, efectul transferurilor asupra latenței comenzilor pe Wi-Fi real, comportamentul în Doze.

## 7. Decizii (răspunsurile tale)

1. **Album nou:** melodiile puse manual (Play next / Add) se păstrează, imediat după piesa nouă (`QueueOp.StartList`, testat).
2. **Prima conectare:** cozile se **combină**. Coada celuilalt dispozitiv vine după a celui care redă, cu proveniența păstrată (`QueueOp.Merge`, testat):
   - lista lui și selecțiile lui manuale intră după selecțiile de aici și înaintea recomandărilor;
   - recomandările lui merg la final;
   - nimic din coada de aici nu se mută.
3. **Cache:** limitele sunt **setabile** pe PC (1–20 GB, implicit 2). Pe telefon limita e deocamdată fixă la 1 GB (setarea vine după testarea pe dispozitive). Plafonul de 10% din spațiul liber e activ peste tot.
4. **Formate nesuportate de telefon** (APE, WavPack, WMA, DSD, ALAC, AIFF): PC-ul le **convertește în FLAC** cu ffmpeg (etapa 6, implementat). Conversia se face o singură dată pe versiune de fișier; un fișier re-etichetat primește alt id.

## Surse

- Android:
  - [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types);
  - [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15);
  - [Background playback with MediaSessionService](https://developer.android.com/media/media3/session/background-playback);
  - [Background audio hardening (Android 17)](https://developer.android.com/about/versions/17/changes/bg-audio);
  - [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager.html);
  - [Power management limits (Doze)](https://developer.android.com/topic/performance/power/power-details);
  - [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).
- Spotify Connect (open-source, inginerie inversă):
  - [librespot connect.proto](https://docs.rs/crate/librespot-protocol/latest/source/proto/connect.proto);
  - [librespot issue #1459 (poziția pierdută la transfer)](https://github.com/librespot-org/librespot/issues/1459).
- HTTP:
  - [If-Range (MDN)](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/If-Range);
  - [HTTP range requests](https://http.dev/range-request).
- Reîncercări: [Exponential Backoff And Jitter (AWS)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/).
- Liste și mutări concurente:
  - [Moving Elements in List CRDTs (Kleppmann, 2020)](https://martin.kleppmann.com/2020/04/27/papoc-list-move.html);
  - [Extending JSON CRDTs with Move Operations (2024)](https://martin.kleppmann.com/2024/04/22/json-crdt-move.html).
- Fencing tokens: [How to do distributed locking (Kleppmann, 2016)](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html).
