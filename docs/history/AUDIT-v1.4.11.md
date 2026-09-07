# Harmony 1.4.11 — audit și corecturi

Audit realizat pe arhiva furnizată `Harmony-v1_4_10-Discovery-Refresh-Fix(1).zip`. Rezultatul este proiectul complet, versiunea **1.4.11 / versionCode 83**. Nu este un APK compilat.

Am verificat fluxurile principale din app, core, data, domain, playback, feature și sync: navigare, stocare, scanare, playlisturi, redare, editor, analiză audio, descărcări, autorizare Spotify și Discovery. Corecturile de mai jos au rezultat din citirea codului și din teste controlate. Acest audit nu constituie o garanție că aplicația nu mai conține alte buguri.

## Probleme corectate

| Componentă | Problema identificată | Comportamentul după corectură |
| --- | --- | --- |
| Scanare | Două fișiere diferite, cu același nume și aceeași mărime, puteau fi confundate. | Identitatea folosește volumul și calea fizică, când acestea sunt cunoscute. Pentru furnizori necunoscuți se păstrează URI-ul. |
| Scanare | Adăugarea unui folder SAF putea înlocui URI-ul deja cunoscut pentru același fișier. | URI-urile existente au prioritate. Aliasurile vechi nu sunt șterse automat, pentru a proteja referințele din playlisturi. |
| Scanare | Un volum SD indisponibil sau o interogare care eșuează putea fi interpretat ca ștergere a fișierelor. | Eliminarea din baza de date se limitează la sursele enumerate complet și volumele prezente pe durata enumerării. Un cursor nul produce eroare. |
| Scanare | Mai multe scanări puteau modifica simultan biblioteca. Ultimul lot incomplet se salva după evenimentul `Completed`. | Scanările sunt serializate; lotul final este salvat înainte de anunțarea finalizării. |
| Scanare | Fișierele MediaStore aflate încă în curs de scriere puteau ajunge la analiză. | Interogarea exclude `IS_PENDING=1`. |
| Playlisturi și liste inteligente | Unele interogări citeau tagurile originale, ignorând corecturile din editor. | Playlisturile, Favorites, Recently played, Most played și listele după energie citesc `songs_effective`. |
| Playlisturi | Două adăugări simultane puteau calcula aceeași poziție de inserare. | Calcularea poziției și inserarea au loc într-o tranzacție. Dublurile nu ocupă poziții suplimentare. |
| Favorites | Apăsările concurente puteau pierde o comutare; un eveniment pentru o melodie deja ștearsă putea încălca relația din baza de date. | Comutarea este tranzacțională; inserarea verifică existența melodiei. |
| Import M3U | `+`, BOM, codificarea procentuală și nume identice puteau conduce la potrivirea greșită. | Se preferă URI-ul exact, `+` rămâne literal, secvențele procentuale valide sunt decodate, iar potrivirile ambigue nu aleg arbitrar primul fișier. |
| Coada salvată | Dacă dispărea o piesă dinaintea celei selectate, se restaura altă piesă. | Se păstrează piesa și apariția selectată, inclusiv în cozi cu duplicate. Dacă piesa selectată lipsește, se alege una supraviețuitoare și poziția revine la zero. |
| Serviciul de redare | O nouă instanță de serviciu putea primi același ExoPlayer deja eliberat. | Playerul și procesoarele sale aparțin unei instanțe de serviciu prin `ServiceScoped`. Timerul și callback-ul sunt desprinse și curățate la oprire. |
| Conectarea playerului | Controlerul nu trata deconectarea; unele operații puteau aștepta la nesfârșit. | Conexiunea are timeout, se reface la revenirea în aplicație sau la Play și afișează eroare dacă eșuează. Setările audio trimise anterior sunt reaplicate. |
| Istoric de ascultare | Tick-urile de cinci secunde, când ecranul era stins, depășeau limita acceptată de vechiul contor. | Contorul folosește ceasul de ascultare, timpul real și viteza de redare. Seek-ul resetează măsurarea. |
| Crossfade | Alegerea `index + 1` ignora shuffle și revenirea la început în Repeat All. | Se folosește `nextMediaItemIndex`, iar ținta este verificată din nou înainte de transfer. Predicția din UI urmează aceeași ordine. |
| Crossfade | Playerul auxiliar putea continua după Pause sau întreruperea redării principale. | Preview-ul este oprit la pauză, pierderea redării, seek, eroare și schimbarea ordinii. Oprirea nu pornește muzica din nou. |
| Crossfade | Limita de 100 de pași scurta fade-urile mai lungi la aproximativ cinci secunde. | Rampa urmărește progresul audio real, inclusiv la viteze diferite de 1×. |
| Crossfade | Playerul principal era ținut la final și când preview-ul nu pornise. | Pauza la limita piesei este activată numai când preview-ul redă efectiv. Dezactivarea crossfade-ului eliberează playerul auxiliar imediat. |
| ReplayGain | Tagurile de gain nu ajungeau în player pentru cozile trimise prin MediaController. | Valorile călătoresc în metadatele MediaItem, inclusiv pentru Android Auto. |
| ReplayGain | Procesorul inactiv la 0 dB putea să nu primească PCM după activarea funcției în timpul piesei. | Rămâne în lanț; la 0 dB copiază PCM fără modificare. Gain-ul se poate schimba în timpul redării; valorile nefinite sunt respinse și clipping-ul este limitat. |
| Viteză și pitch | Schimbarea pitch correction putea readuce viteza la 1×. | Pitch-ul este calculat folosind viteza actuală a playerului, inclusiv când aceasta vine prin MediaController. |
| Sleep timer | Listenerul rămânea atașat; expirarea la ultima piesă putea lăsa o oprire armată pentru mai târziu. | Listenerul este desprins la oprire, `STATE_ENDED` este tratat, iar numărătoarea folosește timp monoton. |
| Artwork | Android Auto/notificarea puteau primi coperta originală după alegerea unei coperte proprii. | URI-ul și octeții trimiși disting coperta utilizatorului de cea extrasă. Reducerea imaginii ține cont de ambele dimensiuni. |
| Rescan în Settings | O eroare lăsa butonul ocupat și putea propaga o excepție netratată. | Se afișează eroare, iar starea ocupată este eliberată în `finally`. Accesul SAF refuzat este tratat. |
| Editor | Căutările sau selecțiile vechi puteau suprascrie selecția nouă. | Cererile vechi sunt anulate și rezultatele sunt verificate față de selecția curentă. |
| Editor | Undo all edits putea reafișa tagurile deja editate. O salvare întârziată putea marca altă melodie ca salvată. | Revert citește din nou valorile originale/efective; rezultatul salvării este aplicat doar selecției care a pornit operația. |
| Editor | Imaginea era citită pe firul UI fără limită de mărime. Erorile de scriere nu erau afișate. | Citirea se face pe IO, cu limită de 32 MB; sunt afișate starea ocupată și erorile. |
| Analiză audio | Bucla MediaCodec putea continua fără ieșire dacă decoderul nu mai producea date; anularea era înghițită. | Bucla verifică anularea și are timeout de stagnare de 15 secunde. Inspectorul și repository-ul propagă anularea. |
| Analiză audio | Analiza putea folosi rata de eșantionare de intrare în locul celei decodate; downmix-ul întreg trunchia fracțiuni mici. | Inițializarea folosește formatul PCM de ieșire; rata și numărul de canale sunt validate, iar media canalelor se calculează în virgulă mobilă. |
| Spotify | Un refresh de token întârziat putea reintroduce tokenurile după Disconnect sau schimbarea Client ID. | Scrierea tokenurilor verifică generația sesiunii sub același lock ca deconectarea. |
| Spotify | Paginarea accepta orice adresă `next` și putea repeta pagini până la limită. | Sunt acceptate doar adrese HTTPS Spotify API; redirecturile automate sunt oprite, iar paginile repetate produc eroare explicită. |
| Descărcare directă | Transferul nu verifica anularea în bucla de citire și putea publica un răspuns incomplet. | Anularea este verificată, dimensiunea este comparată cu Content-Length când există, iar containerul FLAC este validat înainte de publicare. |
| Publicarea descărcării | O actualizare MediaStore cu zero rânduri modificate putea fi raportată ca succes. | Finalizarea verifică numărul de rânduri; curățarea nu ascunde eroarea inițială. |
| SpotiFLAC / FFmpeg | Așteptarea blocantă a encoderului nu răspundea la anularea coroutinei. | Așteptarea este anulabilă, iar procesul este oprit la anulare sau timeout înaintea curățării fișierelor temporare. |
| Verificare după descărcare | Anularea verificării spectrale putea apărea ca eroare obișnuită după salvare. | Anularea este propagată, fără publicarea unui rezultat întârziat al verificării. |

## Rezultatele verificării

- **207 fișiere Kotlin de producție compilate**, cu Kotlin 2.1.0, Android 35, Compose și API-urile reale Media3 1.5.0, Room, Hilt, DataStore, WorkManager și dependințele aplicației. Au rămas avertismente de API depreciat/FlowPreview, fără erori de compilare Kotlin.
- **135 teste JUnit trecute**. Acoperă și funcțiile existente de Discovery, refresh, selecția albumelor, notificarea comună pentru descărcări, Spotify PKCE/matching, starea internetului, ascultarea albumelor și noile regresii pentru scanare, M3U, coadă și procese anulate.
- **7 scenarii editor**: rezultate de căutare vechi, selecții vechi, Back în timpul încărcării, revert, salvări repetate, navigare în timpul salvării, eroare de stocare.
- **4 scenarii Spotify** cu HTTP controlat: refresh concurent cu Disconnect, schimbare Client ID, refresh reușit, paginare repetată. Nu s-a folosit un cont Spotify real.
- **9 verificări crossfade**: țintă shuffle, Pause, rampă completă de 12 secunde, dezactivare, Repeat All, ultima piesă, Repeat One, preview lent și oprire fără reluare automată.
- **5 verificări ReplayGain** pe buffere PCM: activ la 0 dB, bypass exact, modificare în timpul piesei, clipping și metadate nefinite.
- **SQLite**: cele șase interogări corectate returnează tagurile editate; două scrieri pentru melodii inexistente sunt ignorate; ștergerea se propagă în patru tabele dependente.
- **Flux Discovery cu ViewModel și SavedStateHandle AndroidX**: patru loturi distincte, ciclu nou, refresh apăsat rapid, callback-uri vechi, schimbare gen, gen cu puține albume, restaurare după browser, offline/reconectare, swipe/undo, timeout preview și redarea albumului în ordine — trecut.
- **C++**: cele patru fișiere DSP/search fără JNI au trecut verificarea sintactică cu `g++ -std=c++17 -Wall -Wextra -fsyntax-only`. Nu este un build ARM/NDK și nu verifică încărcarea bibliotecilor native pe telefon.

Scripturile și sursele testelor suplimentare sunt în `tools/audit`. Testele unitare obișnuite sunt în modulele `src/test`.

## Limite și verificare pe telefon

Buildul Gradle complet nu a putut descărca distribuția prin conexiunea JVM disponibilă în acest mediu. SDK-ul/NDK-ul pentru un build Android complet nu sunt configurate aici. **Nu am produs un APK și nu am executat aplicația pe telefon sau emulator.**

Compilarea izolată nu rulează generarea KSP/Hilt/Room, îmbinarea manifestelor, împachetarea resurselor, semnarea sau verificările ABI. Testele suplimentare simulează servicii Android, repository-uri, HTTP și evenimente de player. Nu verifică latența reală a decoderelor, focusul Bluetooth, notificările OEM ori disponibilitatea furnizorilor externi.

Discovery păstrează catalogul inclus de 96 de albume. Refresh schimbă loturile locale și departajarea recomandărilor; nu descarcă automat un catalog nou de pe SHFL. Protecția offline și revenirea de cinci secunde, descărcarea selectivă a albumului și dialogul de păstrare/ștergere au fost păstrate. Corecția existentă de proprietar al notificării a trecut din nou testele; dispariția/reapariția notificării Android trebuie verificată și pe dispozitiv.

După build, verifică în special:

1. Actualizarea peste versiunea instalată cu aceeași cheie, apoi pornirea și păstrarea bibliotecii/playlisturilor.
2. Pause/Play, crossfade de 12 secunde, Shuffle, Repeat All, schimbarea vitezei și ReplayGain în timpul piesei, Bluetooth și Android Auto.
3. Scanare după scoaterea/reintroducerea unui card SD și după schimbarea permisiunilor unui folder.
4. Descărcare de album Soulseek și SpotiFLAC, schimbarea ecranului, anulare în timpul conversiei, pierderea/revenirea internetului și notificarea.
5. Ascultarea completă a albumului, păstrarea unor melodii și ștergerea celorlalte cu confirmarea Android; verificarea cozii și a playlisturilor după ștergere.
6. Spotify Connect cu propriul Client ID și redirectul configurat, importul unui playlist și Disconnect în timpul unei operații.

Pașii pentru `D:\Harmony` și SDK-ul `D:\Android` sunt în `INSTRUCTIUNI-Audit-v1.4.11.txt`. Nu este necesară dezinstalarea sau ștergerea datelor pentru actualizare.

## Referințe tehnice

- [Android: durata de viață a serviciului MediaSession](https://developer.android.com/media/media3/session/background-playback)
- [Android: componente și scope-uri Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
- API-urile de compilare folosite sunt cele declarate în `gradle/libs.versions.toml` și fișierele modulelor; versiunile dependințelor nu au fost actualizate în acest audit.
