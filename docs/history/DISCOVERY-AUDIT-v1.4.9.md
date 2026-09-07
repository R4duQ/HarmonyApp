# Audit Discovery — Harmony 1.4.9

Auditul a pornit de la sursele 1.4.8 si a acoperit ecranul Discovery, rundele de
swipe, recomandarile, persistenta, filtrele, loturile de albume, preview-urile,
legaturile SHFL, conectivitatea si intrarea in descarcarea unui album. Problemele
gasite mai jos sunt corectate in surse. Verificarea de cod si testele JVM nu
inlocuiesc un test al interfetei si al serviciilor Android pe telefon.

## Probleme gasite si corectii

| ID | Problema si efect | Corectia | Verificare |
| --- | --- | --- | --- |
| D01 | Dupa eliminarea ultimului album din ultimul lot Saved, lista putea deveni goala desi existau alte albume salvate. Refresh putea ramane activ fara alternative si afisa mereu „next 24”. | Reia lotul eligibil cand nu mai exista albume nevazute; calculeaza corect albumele ramase, disponibilitatea Refresh si dimensiunea urmatorului lot. | Test de regresie si scenariu cu ViewModel: 25 salvate, lot de 24, ultimul album eliminat, revenire la cele 24. |
| D02 | Filtrul pentru versiuni live cauta cuvantul oriunde in titlu. Excludea titluri normale precum „Live Forever” si piesele live ale albumului „Live at the Regal”. | Recunoaste calificative de versiune; albumul live din catalog accepta propriile inregistrari live si le poate folosi ca piesa de pornire. | Teste pentru titlu normal, album live, remixuri si versiuni de studio. |
| D03 | Normalizarea metadatelor pentru descarcare elimina caracterele nelatine. Titluri sau artisti japonezi/chirilici diferiti puteau ajunge la aceeasi cheie. | Pastreaza literele si cifrele Unicode, normalizand diacriticele si punctuatia. | Testul care identifica doua nume japoneze ca fiind identice esua inaintea corectiei si trece acum. |
| D04 | Eliminarea unei etichete Deluxe putea elimina si „(Live)” din numele editiei. O editie live putea fi confundata cu una de studio. | Elimina doar sufixe recunoscute, de la sfarsitul titlului, pastrand identitatea inregistrarii. | Regresie pentru „Album (Live) (Deluxe Edition)” si verificari pentru remaster. |
| D05 | Cautarea editiilor filtra dupa titlul albumului, dar doar sorta dupa artist. Albume omonime ale altor artisti puteau ramane selectabile. | Cere titlul si artistul asteptat; accepta aliasurile explicite ale artistului din catalog. Verifica din nou artistul la incarcarea tracklistului. | Teste pentru artist diferit, Prince ca alias si remaster; scenariu de salvare a editiei aprobate. |
| D06 | O cautare inceputa pentru un album putea lasa rezultate vechi la schimbarea albumului sau dupa o eroare. Un callback vechi putea incerca selectarea unei editii sub alt ID. | Goleste rezultatele la o noua cautare, le asociaza albumului solicitat si respinge raspunsurile/selectiile care nu mai apartin acestuia. | ViewModel si parser reale, raspuns HTTP intarziat simulat, eroare 503 si selectie veche respinsa. |
| D07 | Un preview inca la incarcare nu era oprit daca porneai playerul principal. Limita de asteptare incepea doar dupa cautarea URL-ului; potrivirea bibliotecii rula pe firul UI. Versiunea separata a unei piese nu era verificata. | Anuleaza si cererile in asteptare; limita de 20 s acopera cautarea locala si online; potrivirea ruleaza in fundal. Verifica versiunea piesei si albumul pentru un preview live. | Scenarii cu pornirea playerului in timpul incarcarii si timeout cu timp virtual; 5 teste pentru alegerea inregistrarii si URL-uri. Nu este revendicata o masuratoare de performanta. |
| D08 | „Play local tracks” pastra shuffle/repeat existente, putand schimba ordinea albumului sau repeta aceeasi piesa. | Opreste preview-ul, dezactiveaza shuffle si repeat, apoi porneste lista ordonata a albumului. | Scenariu cu mod SMART si Repeat ONE active anterior; verifica ordinea comenzilor si lista redata. |
| D09 | „Running Up That Hill” aparea de doua ori, sub titlu scurt si complet, si putea primi doua voturi. | Foloseste o singura alegere. Migreaza ID-ul alternativ catre cel canonic, pastrand ID-ul celeilalte piese si voturile vechi. | Test pentru ID-uri stabile si restaurare din preferinte vechi, inclusiv progresul rundei. |
| D10 | Next sau Undo putea pastra pozitia de scroll din partea de jos a cardului lung de recomandare, ascunzand noua melodie. | Readuce lista la inceputul cardului corespunzator la schimbarea rundei, Undo dupa reveal sau revenirea in Swipe songs. | Flux de cod verificat si Compose compilat; comportamentul vizual necesita telefon. |
| D11 | Eliminarea albumului aflat in fata putea readuce pagerul la primul album. | Salveaza si pozitia albumului; daca ID-ul dispare, foloseste pozitia ramasa valida. | Persistenta pozitiei verificata in ViewModel; randarea pagerului necesita telefon. |
| D12 | Daca toate albumele erau marcate ascultate, butonul de explorare din rezultatul gol trimitea inapoi la For you, unde ele erau excluse. | Deschide All albums si elimina filtrul de gen in acest caz. | Rutare verificata in cod si compilarea integrarii de navigare. |
| D13 | Textul explicativ din dialogul Info nu avea scroll, ceea ce putea ascunde continut la font mare sau pe ecran mic. | Textul dialogului poate fi derulat. | Compose compilat; verificare vizuala necesara pe telefon. |

## Ce a fost verificat

- 79 de teste JUnit trecute: 54 Discovery si 25 pentru albume, potrivirea
  fisierelor, acoperirea ascultarii, selectia pieselor, accesul la motorul nativ,
  proprietarii progresului si revenirea conexiunii. Sunt 13 teste noi fata de 1.4.8.
- Codul Discover, ecranele/workerul/ViewModel-ul pentru albume, stocarea,
  monitorul de ascultare si integrarea navigarii au fost compilate izolat cu
  Kotlin 2.1, Android 35 si API-urile Compose/Media3/WorkManager ale proiectului.
- Scenariul pentru Discovery foloseste store-ul si ViewModel-ul reale cu limite
  Android/Lifecycle simulate: patru loturi distincte, voturi concurente, offline,
  restaurare la 9/10, reveal, Undo, runda noua, migrare, recuperarea Saved,
  anularea preview-ului si ordinea albumului.
- Scenariul pentru cautarea editiilor foloseste parserul HTTP, store-ul si
  ViewModel-ul reale, cu raspunsuri HTTP simulate. Nu foloseste un cont de muzica
  si nu descarca audio.
- Codul de notificare pastreaza ID-ul unic al lotului si proprietarii separati
  pentru progres. Testele de progres trec. Aparitia/disparitia notificarii Android
  reale nu poate fi confirmata de un test JVM.

Compilarea izolata nu executa generarea Hilt si imbinarea resurselor unui build
Gradle complet. Limitele motoarelor native si unele ecrane nemodificate sunt
reprezentate prin semnaturile lor. Buildul Gradle complet ramane blocat aici la
descarcarea distributiei; nu a fost produs un APK si nu a fost folosit un emulator.

## Verificare pe telefon

1. In Saved, salveaza 25 de albume, treci la ultimul lot si elimina singurul album
   din acel lot. Celelalte 24 trebuie sa reapara, iar Refresh sa fie dezactivat.
2. Fa 10 alegeri, deruleaza recomandarea pana jos, apoi Next. Noua melodie trebuie
   sa fie vizibila. Repeta cu Undo dupa o recomandare si cu revenirea din album.
3. Porneste un preview si apoi muzica din mini-player cat preview-ul se incarca.
   Preview-ul trebuie sa ramana oprit. Incearca si pierderea conexiunii in acel moment.
4. Activeaza Shuffle si Repeat ONE, apoi reda un album local din Discovery.
   Piesele trebuie sa urmeze ordinea albumului.
5. Deschide Info cu fontul Android marit si verifica derularea textului.
6. Treci pe mod avion, apoi revino online cu Discovery deschis: functiile redevin
   disponibile dupa cele 5 secunde existente, cu progresul rundei pastrat.
7. Descarca doua piese selectate, navigheaza prin Downloads si inapoi, apoi Pause.
   Verifica notificarea reala si fisierele finalizate. Disponibilitatea surselor,
   viteza transferului, audio focus si restrictiile de baterie necesita dispozitiv.

Acesta este rezultatul auditului disponibil, nu o garantie ca nu mai exista alte
buguri pe toate telefoanele sau in raspunsurile viitoare ale furnizorilor.
