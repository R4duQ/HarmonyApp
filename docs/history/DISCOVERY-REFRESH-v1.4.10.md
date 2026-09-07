# Discovery Refresh — Harmony 1.4.10

## Problema reprodusa

In 1.4.9, ordinea albumelor ramanea fixa. Dupa cele patru loturi initiale,
urmatorul Refresh reda exact primul grup de 24. La reluarea catalogului,
ultimul lot era si considerat deja vizitat, ceea ce scurta ciclul urmator.
Testul adaugat in scenariul ViewModel a esuat pe codul vechi cu mesajul
`Refresh recycled the exact first batch after the catalogue was explored`.
Reproducerea a folosit implementarea reala AndroidX SavedStateHandle 2.8.7.
Primele patru schimbari de lot functionau in acest scenariu; nu a fost reprodus
un blocaj al primului Refresh pe dispozitivul utilizatorului.

Separat, selectia, ordinea si istoricul erau fluxuri independente. Un eveniment
Refresh imediat dupa schimbarea filtrului putea folosi lista ecranului anterior.
Evenimentele pagerului anterior nu aveau identificatorul lotului de care apartineau.

## Corectii

- Fiecare Refresh avanseaza istoricul si modifica seed-ul folosit la departajarea
  albumelor cu acelasi scor. Preferintele continua sa influenteze For you.
- La reluarea catalogului, albumele din lotul anterior sunt amanate doar pentru
  primul lot. Ele raman disponibile mai tarziu in acelasi ciclu.
- Selectia de raft/gen, seed-ul, istoricul si revizia se publica intr-o singura
  stare. Campurile primitive sunt pastrate in SavedStateHandle pentru restaurare.
- Refresh accepta numai revizia afisata si o avanseaza o singura data. Evenimentele
  intarziate ale butonului si pagerului sunt ignorate. Callback-urile Compose
  captureaza explicit revizia lotului, fara sa reciteasca starea delegata mai noua.
- Pagerul primeste o cheie noua pentru fiecare lot. Pozitia este resetata la
  primul card la Refresh si este pastrata cand revii fara sa schimbi lotul.
- Ecranul arata numarul Batch si cate albume mai sunt. Cand un gen are cel mult
  24 de albume si toate sunt deja in lot, afiseaza totalul si All sounds.
- Seed-ul melodiilor ramane separat: Refresh la albume nu reordoneaza runda de swipe.

Catalogul ramane cel inclus, de 96 de albume; repetarile dupa parcurgerea sa
sunt normale. Aceasta modificare nu introduce o sursa de albume live de pe SHFL.

## Verificare

- 58 de teste JUnit Discovery trecute. Cele patru teste noi verifica trei cicluri
  complete de 96 de albume, cicluri incomplete de 25/29/48/49/73/96 de albume,
  excluderea recomandarilor cu scor mare din lotul imediat urmator si toate genurile.
- Scenariul cu ViewModel, store si SavedStateHandle AndroidX reale trece pentru
  loturi distincte, variatia la reluarea catalogului, 20 de evenimente vechi de
  Refresh/pager, schimbarea imediata a genului, gen epuizat, revenire la All sounds,
  restaurarea lotului/pozitiei si Refresh dupa restaurare. Verifica si ca ordinea
  melodiilor ramane aceeasi. Contextul Android, conectivitatea si scope-ul
  ViewModel-ului sunt simulate; restaurarea copiaza valorile handle-ului, fara
  a pretinde o testare a serializarii Bundle sau a distrugerii procesului Android.
- Scenariile existente pentru offline/revenire, voturi, runde, Saved, preview si
  redarea albumelor trec in acelasi harness.
- Codul Discover si integrarea navigarii au fost compilate izolat cu Kotlin 2.1,
  Android 35, Compose si Media3 1.5.0. Versiunea este 1.4.10, versionCode 82.

Compilarea izolata nu acopera generarea Hilt si imbinarea resurselor dintr-un
build Gradle complet. Distributia Gradle nu poate fi descarcata in acest mediu;
nu a fost produs un APK si nu a fost rulat un emulator. Gesturile si restaurarea
vizuala a pagerului trebuie confirmate pe telefon cu aplicatia recompilata.

Pe un PC cu SDK-ul si dependintele disponibile, testele incluse pot fi rulate cu:

```powershell
.\gradlew.bat :feature:discover:testDebugUnitTest
```

Instalarea actualizarii este descrisa in `INSTRUCTIUNI-Discovery-v1.4.10.txt`.
