# Albume in Downloads — inclus in Harmony 1.0

## Utilizare

1. Deschide Downloads si alege **SpotiFLAC** sau **Soulseek**.
2. Apasa **Albums** (langa Tracks), cauta numele albumului si artistul.
3. Alege editia exacta din rezultate. Lista completa de piese este incarcata
   si salvata inainte de deschiderea albumului; nu se descarca audio la cautare.
4. Pentru tot albumul, lasa toate piesele bifate. Pentru o selectie, debifeaza
   piesele nedorite sau foloseste **Clear selection**, apoi bifeaza individual.
   Poti apasa pe intregul rand al unei piese. **Select all missing** bifeaza
   toate piesele care nu sunt deja in biblioteca.
5. Verifica sursa si formatul din pagina albumului. Poti alege SpotiFLAC sau
   Soulseek aici, fara schimbari automate intre ele. Pentru Soulseek este
   necesara conectarea contului; pentru SpotiFLAC poate fi necesara verificarea
   furnizorului. Nu sunt ocolite restrictiile de acces.
6. Apasa **Download full album**, **Download all missing** sau
   **Download selected**, conform selectiei. Exista si optiunea pentru retea
   necontorizata (Unmetered network only).

Albumele deschise apar in **Your album downloads**, inclusiv cele pornite anterior
din Discover. Redeschiderea unui album pastreaza selectia, sursa, fisierele si
progresul existente; verifica sursa afisata inainte de reluare.

## Comportament si siguranta

- Descarcarea se face piesa cu piesa, in ordinea discurilor si a numerelor de pista.
  Nu se tine intregul album in RAM si nu se pornesc transferuri paralele pentru toate piesele.
- WorkManager pastreaza lista exacta bifata la pornire. Schimbarea preferintelor
  ulterioare nu adauga alte melodii la transferul aflat in curs.
- **Pause album** opreste lucrul; fisierele deja publicate raman salvate. Revino
  in album pentru reluare. Fisierele existente sunt reutilizate, nu redescarcate.
- Nu poti modifica selectia acelui album cat timp este in coada. Bifarile rapide
  sunt serializate, iar pornirea asteapta salvarea selectiei.
- Albumul cu piese esuate nu mai este raportat de worker ca un succes complet.
  Erorile apar pe piesele respective; problemele temporare pot fi reincercate.
- SpotiFLAC: FLAC CD, FLAC max 24/96 sau MP3 320. Plafonul 24/96 nu mareste artificial
  rezolutia surselor inferioare. Soulseek: FLAC sau MP3 nativ de la peer; eticheta MP3
  nu promite 320 kbps. Nu se converteste automat un fisier lossy in FLAC.
- Cautarea albumelor foloseste metadate publice Deezer pentru ambele surse audio.
  Apar maximum 25 rezultate per cautare; limita existenta este de 250 piese per editie.
  Disponibilitatea metadatelor nu garanteaza ca toti furnizorii/peerii au toate piesele.

## Verificari efectuate

- Compilate toate cele 229 fisiere Kotlin de productie (230 impreuna cu clasa R
  folosita de harness), inclusiv Compose, cu API-urile reale ale dependintelor.
- 207 teste JVM trecute: 205 ale proiectului, plus 2 verificari ale extragerii
  pachetelor reale FFmpeg/Python pentru ARM64 si x86_64.
- 12 teste noi acopera albumul complet, selectia partiala, All/None, fisierele
  existente, reluarea, snapshot-ul cozii, ordinea discurilor si sursele/formatele permise.
- Nu s-au testat interfata pe emulator/telefon sau descarcari reale de albume.
- APK-ul complet NU a fost construit: Gradle/JDK esueaza local cu
  AccessDeniedException la inchiderea gradle-core-8.11.1.jar, inclusiv cu distributia
  Gradle copiata in workspace. Aceasta este o arhiva de surse, nu un APK instalabil.
  Verificarile Kotlin/JVM nu valideaza KSP, R8 sau impachetarea Android.

## Compatibilitate: ce este si ce nu este confirmat

Se pastreaza tinta proiectului: **Android 10+ (API 29), ARM64 si x86_64**.
Nu sunt adaugate suport iPhone sau Android pe 32 de biti. Se pastreaza corectiile
FFmpeg/libc++ din `SPOTIFLAC-COMPATIBILITATE.md`, dar NU exista garantie pentru
toate telefoanele. Cele trei biblioteci externe FFmpeg cu aliniere ELF de 4 KB
raman o limitare potentiala la conversie pe dispozitive cu pagini de 16 KB.

Android si producatorul pot suspenda/reprograma activitatea in fundal. Pe Android 16,
si lucratorii de lunga durata pot fi limitati de cotele sistemului. Progresul per piesa
este salvat pentru reluare; oprirea fortata a aplicatiei nu poate fi evitata prin cod.
Referinta: [documentatia Android pentru WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## Construire si verificare pe dispozitive

Deschide proiectul in Android Studio cu JDK 17, SDK 35, CMake 3.22.1 si
NDK 29.0.14206865. Ruleaza `./gradlew :feature:downloads:testDebugUnitTest :app:assembleDebug`.
Pastreaza cheia de semnare originala pentru actualizarea unei instalari existente.

Testeaza pe fiecare telefon afectat: un album complet, doua piese bifate din alt
album, anulare/reluare, pierderea retelei, inchiderea ecranului si reluarea aplicatiei.
Repeta pentru ambele surse; verifica numele, durata, formatul si redarea fisierelor.
La eroare sunt necesare modelul, versiunea Android si mesajul complet, fara parole/tokenuri.
