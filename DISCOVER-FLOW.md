# Discover · flux în 3 pași

Înlocuiește deck-ul „50 de like-uri” cu un flux în trei pași. Selecțiile salvate anterior (Recent Discover playlists) rămân deschise și pot fi reluate.

## Pași

1. **Preferences**
   - Harmony arată pe ce își bazează profilul, cu motive reale, de exemplu „You've played Muse a lot lately”.
   - Alegi nivelul de explorare: „For my taste” (~80/20), „Balanced mix” (~60/40) sau „Surprise me” (~40/60).
   - Alegi dimensiunea playlistului: 10–100 de melodii, implicit 50.
   - Poți folosi filtrele existente: țară, gen, cuvânt-cheie.
   - Fără istoric, alegi artiști și genuri.
   - Offline, butonul devine „Build from my library”.
2. **Songs**
   - Un card mare, prin care treci cu swipe, arată coperta, titlul, artistul, motivul, dacă melodia e deja în bibliotecă și sursa metadatelor. Dedesubt e lista completă.
   - Acțiuni pe melodie: Listen (30 s), Keep, More like this, Replace, Not interested, Remove.
   - Pentru toată selecția: „New songs” regenerează tot ce nu e păstrat, „Fill to N” completează după ștergeri, iar nivelul de explorare se poate schimba.
3. **Playlist**
   - Poți edita numele. Vezi o copertă mozaic, durata (sau câte durate sunt cunoscute) și starea fiecărei melodii: în bibliotecă, se descarcă, eșuată sau de descărcat.
   - Butonul „Create playlist with the X available songs” salvează imediat ce e disponibil și deschide playlistul.
   - Restul melodiilor rămân în selecție și sunt adăugate în același playlist când se descarcă și sunt scanate, inclusiv după repornirea aplicației. Fără dubluri și fără să re-adauge o melodie ștearsă de utilizator.

## Profil

Sursele profilului:
- istoricul redărilor, cu greutate mai mare pentru ascultările recente (timp de înjumătățire de 21 de zile) și o componentă pe termen lung;
- ascultările complete și reluările (o redare completă la mai puțin de 36 h de cea anterioară);
- skip-urile: redări care s-au oprit înainte de jumătate. Aplicația nu înregistrează secunda exactă a skip-ului;
- favoritele și melodiile din playlisturile proprii (cele create de Discover sunt excluse);
- voturile din vechiul deck de swipe;
- alegerile „More like this” și „Not interested”.

Genurile sunt folosite doar când eticheta e validă: se ignoră „Unknown”, „Other” și coduri numerice de tipul „(13)”.

Un skip doar scade ușor scorul unui artist și nu îl exclude niciodată. „Not interested” exclude doar melodia respectivă.

## Surse

- **Deezer** (API public, fără cheie): căutare de artiști, top piese, artiști înrudiți, topuri pe gen și preview-uri.
- **Apple Music**: topuri pe țară, preview-uri și căutare iTunes, folosită ca sursă de rezervă când Deezer nu răspunde.
- **MusicBrainz**: țara artistului, doar cu filtrul corespunzător.
- **Qobuz nu este sursă de discovery**, pentru că API-ul lui cere un app ID aprobat pe care Harmony nu îl are. Rămâne posibil provider de descărcare prin SpotiFLAC.

Selecția o face Harmony, nu furnizorii. Motivele numesc sursa, de exemplu „Deezer lists X as related to Y”. Nu se inventează energie, tempo sau atmosferă.

Tratarea erorilor pe fiecare sursă:
- timeout-uri de 8–10 s și maximum 3 încercări cu backoff;
- `Retry-After` este respectat până la 8 s;
- o sursă cu probleme e oprită 60–90 s;
- se distinge între offline, timeout, limită de cereri, autentificare expirată, serviciu indisponibil și răspuns invalid.

## Identitatea pieselor

Ordinea criteriilor: ISRC, apoi artistul principal, titlul de bază, versiunea și durata (toleranță de ±4 s, ±2 s când ISRC-urile diferă). Variantele live, remix, acoustic, cover, remaster, edit, instrumental și sped up nu sunt confundate cu originalul.

## Descărcări

Înainte de pornire se face o verificare a providerului, care se termină mereu cu „ready”, „acțiune necesară” (logare, verificare în browser) sau o eroare cu Retry:
- **Soulseek**: sesiune conectată și cont; se așteaptă maximum 10 s dacă tocmai se conectează.
- **SpotiFLAC**: verificarea providerului, cu timeout de 20 s.

Panoul arată progresul melodiei curente. Fișierele terminate sunt păstrate la pauză.

## Verificare

Rulat într-un harness Compose Desktop 1.5 cu sursele reale:
- 106 teste noi: domeniu, catalog, motor de recomandare, preview, ViewModel, JSON și UI;
- 16 capturi de ecran.

Nu am rulat compilarea Android/Gradle și nici teste pe dispozitiv. Comenzi recomandate:

```
./gradlew :app:assembleDebug :domain:library:test :data:library:testDebugUnitTest :feature:discover:testDebugUnitTest
./gradlew :feature:discover:connectedDebugAndroidTest
```
