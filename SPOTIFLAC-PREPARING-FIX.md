# Corectie SpotiFLAC max 24/96 — Preparing

Versiunea precedenta trimitea tokenul generic `HI_RES` inclusiv catre Tidal.
Manifestul Tidal 1.2.6 declara `HI_RES_LOSSLESS`, nu `HI_RES`. In backendul
SpotiFLAC Mobile v4.9.5, un token absent din manifest este inlocuit cu prima
optiune din lista. La Tidal, acea optiune este `DOLBY_ATMOS`. Astfel, cererea
putea intra intr-o ruta diferita de FLAC-ul selectat.

Corectia citeste optiunile extensiilor instalate si alege explicit un nivel
lossless recunoscut inaintea fiecarei incercari, inclusiv la schimbarea
furnizorului. Maparile curente pentru FLAC max 24/96 sunt:

| Furnizor | Calitatea solicitata |
| --- | --- |
| Tidal | `HI_RES_LOSSLESS` |
| Qobuz | `HI_RES` |
| Amazon Music | `best` |
| Deezer / sursa limitata la CD | `LOSSLESS` |

Limita de 24-bit / 96 kHz se aplica fisierului rezultat, independent de
calitatea maxima pe care o poate livra furnizorul. Rezolutiile inferioare
sunt pastrate. Optiunile CD si MP3 folosesc in continuare surse lossless.
Nu sunt selectate optiuni Atmos sau lossy in locul FLAC.

Mesajul nativ gol `preparing` nu mai suprascrie detaliile furnizorului si
calitatii cerute. Dupa descarcare, raportarea progresului nativ este oprita,
pentru a pastra vizibile etapele locale de conversie si import.

Nu este necesara stergerea bibliotecii sau schimbarea preferintei salvate.
Reconstruieste proiectul si actualizeaza aplicatia folosind semnatura existenta.

## Verificare

Compilarea izolata Kotlin 2.1.0 / Compose a trecut pentru toate cele 223 de
fisiere de productie (plus ID-urile R de test). Cele 23 de fisiere de teste
au fost compilate, iar toate cele **174 de teste JVM au trecut**.

Testul de regresie reproduce substitutia gresita `HI_RES` -> `DOLBY_ATMOS`
din router si verifica cererea corectata `HI_RES_LOSSLESS`. Sunt verificate
si Qobuz, Amazon, furnizorii limitati la CD, manifestele vechi, pastrarea
optiunilor CD/MP3 si respingerea optiunilor spatial/lossy.

Verificarea locala nu include descarcare live pe telefon sau un APK compilat.
Bugul de mapare este confirmat din cod; disparitia blocajului observat pe
dispozitiv trebuie confirmata dupa instalare.

## Surse tehnice

- [Manifest Tidal](https://github.com/spotiflacapp/SpotiFLAC-Extension/blob/main/sources/tidal-web/manifest.json)
- [Manifest Qobuz](https://github.com/spotiflacapp/SpotiFLAC-Extension/blob/main/sources/qobuz-web/manifest.json)
- [Manifest Amazon](https://github.com/spotiflacapp/SpotiFLAC-Extension/blob/main/sources/amazon/manifest.json)
- [Routerul Go inclus, v4.9.5](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/v4.9.5/go_backend/extension_fallback.go)
