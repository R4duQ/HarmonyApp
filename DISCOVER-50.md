# Discover · 50 de melodii apreciate

Inclus în **Harmony 1.0** (versionCode **100**). A fost introdus în 1.0.4-discover-songs; fluxul actual e descris în [DISCOVER-FLOW.md](DISCOVER-FLOW.md).
Bază: arhiva utilizatorului `Harmony-Albume-SpotiFLAC-Soulseek (2).zip`.

## Utilizare

1. Deschide Discover și alege țara, genul și/sau vibe-ul; fiecare poate rămâne random.
2. Alege modul de țară:
   - **Popular in country**: topul Apple Music al țării alimentează recomandările; selecția este extinsă prin Deezer, genuri, vibe și artiști apreciați. Este o preferință de piață, nu o restricție de naționalitate.
   - **Artists from country**: numai înregistrări ale artiștilor legați explicit de Deezer în MusicBrainz, cu țara asociată selectată. Metadatele MusicBrainz nu garantează locul nașterii/cetățenia; artiștii fără metadate confirmate sunt excluși. Local, se reutilizează doar înregistrări potrivite cu acest catalog.
3. Ascultă până la 30 secunde și glisează dreapta (Like) sau stânga (Pass). Există și butoane, plus Undo pentru ultima alegere înainte de completarea selecției.
4. Exact **50 de aprecieri**, nu 50 de swipe-uri, creează o selecție persistentă. Alegerea sursei/calității și pornirea descărcării sunt explicite; nu se descarcă automat.
5. Fișierele existente sunt reutilizate. WorkManager descarcă piesele lipsă secvențial prin SpotiFLAC sau Soulseek. Sunt disponibile Pauză și Resume.
6. După ce toate cele 50 de fișiere sunt accesibile și indexate, playlistul este salvat atomic în **Your playlists**, în ordinea aprecierilor. Nu se marchează complet un playlist cu piese lipsă.
7. Poți începe următoarele 50 și reveni la selecțiile anterioare din Recent Discover playlists, inclusiv după repornirea aplicației.

## Catalog și recomandări

- Deezer: categorii de gen, chart-uri, playlist-uri și artiști; Apple: topuri pe țară și fragmente furnizate de API; MusicBrainz: metadate despre țara asociată artistului și relația lui cu Deezer.
- Genurile principale sunt încărcate din Deezer. Un gen suplimentar poate fi introdus în selector; pentru subgenuri se caută playlist-uri relevante. Această clasificare contextuală poate fi imperfectă.
- Vibe-ul folosește contextul playlist-urilor și euristici de gen, nu măsurători de BPM/energie și nu analiză AI a audio-ului remote.
- Preferințe de artist și gen învățate din ultimele 500 de voturi, 30% explorare, evitarea artiștilor din ultimele trei alegeri când există alternative, țintă de aproximativ o piesă locală la patru recomandări când există ambele surse.
- Pool limitat, catalog cache-uit în memorie (12 răspunsuri / 15 minute), timeout-uri și citiri limitate. Index local pentru potrivirea înregistrărilor, eșantion local de până la 2.000 de melodii și deck de până la 800 de candidați. Nu este o garanție matematică a „celui mai bun” algoritm.
- Dublurile sunt comparate prin ID, ISRC când există, artist/titlu normalizat și durată când este disponibilă. Sufixele live/remix nu sunt eliminate. Credite/metadate diferite între cataloage pot împiedica unele potriviri.
- Voturile recente (maximum 5.000), aprecierile curente, filtrele, selecțiile de 50 și progresul descărcărilor sunt păstrate local. Schimbarea filtrelor nu șterge aprecierile.
- Qobuz **nu** este integrat direct ca sursă de discovery în această versiune. Sursele de download și calitățile reale rămân cele ale motorului configurat; nu sunt inventate chei de API și nu sunt ocolite verificările furnizorilor.
- Pentru catalog se trimit preferințele de țară/gen/vibe și, la extinderea recomandărilor, numele unor artiști apreciați. Audio-ul local și biblioteca integrală nu sunt încărcate. Previzualizările remote sunt redate la cerere, nu arhivate.

## Descărcare, calitate, compatibilitate

- SpotiFLAC: FLAC CD, FLAC max 24/96 sau MP3 320, conform motorului existent. Nu se face upsampling artificial. Soulseek: FLAC/MP3 nativ, fără promisiune de 24/96 sau 320 kbps pentru fiecare peer.
- Un playlist nou are un ID determinist în spațiul negativ al ID-urilor Room. Tranzacția inserează playlistul și cele 50 de poziții împreună; reluarea nu îl dublează și nu suprascrie modificările ulterioare ale utilizatorului. Playlisturile Discover rămân editabile și exportabile.
- Fișierele publicate sunt checkpoint-uite înaintea scanării. La reluare se rescanează, se verifică accesibilitatea URI-urilor și se reîncearcă doar ce lipsește. Problemele de acces nu sunt tratate ca dovadă că fișierul a dispărut.
- Verificarea furnizorului în browser revine în Discover; nu pornește o descărcare individuală în ecranul Downloads.
- Reutilizează publicarea/storage și motorul FLAC existente. Nu adaugă codec nativ nou. **Nu se garantează compatibilitate cu toate telefoanele**: rămân limitele Android/ABI și ale runtime-urilor descrise în `SPOTIFLAC-COMPATIBILITATE.md`.
- Descarcă doar muzică pentru care ai permisiunea necesară. Furnizorii pot cere autentificare/verificare și pot fi indisponibili.

## Verificări și limite de validare

- Compilare Kotlin 2.1.0 + Compose pe întregul cod de producție cu API-urile Android/dependențele locale reale; teste JVM, inclusiv prag 50, Undo, deduplicare, filtrare, diversitate, mix local/remote, completitudine și serializarea progresului.
- Verificări live, doar metadate, la 16 septembrie 2026: Deezer genre/playlist și Apple România au răspuns; MusicBrainz search a returnat HTTP 503 în mediul de test. Modul țării artistului raportează această eroare și permite reîncercare, fără fallback care să inventeze originea artistului.
- Build-ul Gradle standard este blocat în mediul local la rezolvarea pluginului `foojay-resolver-convention` / compilarea claselor generate din settings, înainte de compilarea modulelor. Compilarea alternativă nu verifică KSP/Hilt/Room generat, separarea Gradle a modulelor, resursele sau împachetarea APK.
- Nu s-a produs un APK și nu s-au testat swipe-urile, audio-ul, WorkManager sau descărcările pe un telefon real în această sesiune. Arhiva este un proiect-sursă, nu o versiune declarată testată pe dispozitive.

### Verificări necesare pe dispozitiv înainte de distribuire

1. `./gradlew :app:assembleDebug :domain:library:test :data:library:testDebugUnitTest :feature:discover:testDebugUnitTest`
2. 49 Like + multe Pass: niciun playlist final; la al 50-lea Like apare selecția. Atingeri rapide/swipe dublu nu trebuie să dubleze voturi.
3. Rotație, navigare, restart și schimbare filtre la 30 Like: progres păstrat. Verifică TalkBack, font mărit, swipe și butoane pe ecran mic.
4. Testează separat țara topului și țara artistului, genurile/subgenurile, serviciu 503/offline și previzualizare expirată. Confirmă că un eșec MusicBrainz nu afișează rezultate cu țară presupusă.
5. Playlist mixt cu fișiere locale și remote: pauză/restart în timpul transferului, spațiu insuficient, acces MediaStore revocat, peer offline și verificare furnizor. Fișierele existente nu trebuie duplicate.
6. Verifică salvarea o singură dată a 50 de poziții, ordinea, redenumirea, reordonarea, exportul și redarea din Your playlists; testează închiderea procesului imediat după salvare.
7. Verifică Android 10+ și dispozitive cu pagini de 16 KB conform limitelor runtime-ului moștenit, înainte de a declara compatibilitatea.

Surse primare pentru contractele de catalog: [Apple RSS](https://www.apple.com/ca/rss/), [iTunes Search API](https://performance-partners.apple.com/search-api), [MusicBrainz Artist](https://musicbrainz.org/doc/Artist), [MusicBrainz Search](https://musicbrainz.org/doc/MusicBrainz_API/Search), [MusicBrainz rate limiting](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting), [Deezer API](https://developers.deezer.com/api).
