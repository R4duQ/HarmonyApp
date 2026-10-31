# Universal Queue: protocolul de test pe dispozitive (etapa 9)

Testele automate (etapele 1–8) rulează pe socket-uri reale, cu motorul real al PC-ului și ffmpeg, dar cu un player simulat pe telefon. Rămân de confirmat pe dispozitive reale:

- golul audio la handoff;
- comportamentul pe Wi-Fi slab;
- telefonul cu ecranul stins și în Doze;
- crossfade-ul pe placa de sunet a PC-ului;
- transferurile mari în timpul redării.

Fiecare test de mai jos spune:

- ce faci;
- ce trebuie să se întâmple;
- ce linie din jurnal arată rezultatul.

La final îmi trimiți jurnalele. Le citesc, măsor din ele și corectez ce nu iese.

---

## 0. Pregătire

**Ai nevoie de:**

- telefonul cu Harmony (versionCode ≥ 152);
- PC-ul cu Harmony pentru Windows, cu ultima versiune;
- același Wi-Fi pentru ambele;
- câteva piese locale pe telefon, cel puțin 5, din care măcar una de peste 6 minute;
- 3–5 piese în biblioteca PC-ului;
- pentru testul 8, un album întreg pe PC (≥ 300 MB).

**Pornire:**

1. Pe PC: **Connect** → asociază telefonul (codul QR sau codul scris), apoi activează **Shared queue (beta)**.
2. Pe telefon, în **Now Playing** → **Play on**, PC-ul apare în listă.
3. **Opțional, adb.** Cu telefonul legat prin USB și depanarea USB activă, urmărește jurnalul în timp real:

```bash
adb logcat -s HarmonyConnect:I HarmonyTransfer:I
```

**Ce să notezi la fiecare test:**

- ce ai auzit (gol, dublură, tăietură, salt);
- ora aproximativă, ca să găsesc momentul în jurnal.

---

## 1. Matricea de teste

| # | Test | Pași | Rezultat așteptat | În jurnal |
|---|---|---|---|---|
| 1 | **Handoff telefon → PC, în redare** | Redă pe telefon o piesă locală, la ~1 min. **Play on** → PC-ul. Repetă de 5 ori, în puncte diferite ale piesei. | Muzica trece pe PC din același loc. Niciun moment în care se aud ambele. Golul e sub 1 s, ideal neobservabil. | Telefon: `handoff phone -> <PC>: Moved(atMs=…, playing=true) · computer ready … ms · silence ≈ N ms`. **N** e golul măsurat (cu o eroare de până la 100 ms, cât e intervalul de citire a stării PC-ului). |
| 2 | **Handoff PC → telefon, în redare** | Cât cântă pe PC, **Play on** → **This phone**. De 5 ori. | Muzica revine pe telefon din același loc. Nu cântă ambele. | Telefon: `handoff -> phone: Moved(…) · phone ready … ms · computer's pause confirmed N ms after asking · phone playing M ms after that`. Golul e **M**. |
| 3 | **Handoff cu pauză** | Pune pauză pe telefon. **Play on** → PC. Apoi, tot pe pauză, înapoi pe **This phone**. | Piesa se mută pe pauză, la aceeași poziție. Nu pornește singură. | `Moved(…, playing=false)`. |
| 4 | **Handoff în timpul unui crossfade** | Pe telefon: Settings → Crossfade 8 s. Redă până la ~10 s înainte de finalul piesei, apoi **Play on** → PC exact în timpul fade-ului. | Handoff-ul așteaptă finalul fade-ului, apoi mută piesa nouă. Nicio dublură. | Handoff-ul pornește, iar `Moved` apare la câteva secunde după. |
| 5 | **Handoff cu PC-ul care nu răspunde** | Telefonul cântă pe PC. Scoate cablul de rețea al PC-ului (sau oprește-i Wi-Fi-ul), apoi **This phone**. | Telefonul nu pornește peste un PC care poate încă suna. Sesiunea se încheie cu un mesaj, iar telefonul e pe pauză în locul unde era. Apeși Play și merge. | `stayed (SOURCE_UNREACHABLE)` sau `Unsure`, apoi `session … ended`. |
| 6 | **Rețea slabă / întreruptă** | În timpul redării pe PC: (a) oprește routerul 10 s; (b) apoi 60 s; (c) mergi cu telefonul la marginea Wi-Fi-ului. | (a) Cel mult „Reconnecting”, apoi totul continuă: coada e aceeași pe ambele, comenzile date între timp nu se pierd și nu se aplică de două ori. (b) Sesiunea se încheie curat. Muzica rămâne pe PC până o oprești. (c) Comenzile întârzie, dar nu se dublează. | `computer not answering (1x)`, `(10x)`, … apoi `computer answering again after N failed reads`. Pe PC: liniile `com.harmony.connect` și `queue link`. |
| 7 | **Coada comună: editări simultane** | Cu Shared queue pornită: pe PC, mută o piesă în coadă. În aceeași secundă, pe telefon, șterge alta. Apoi „Play next” pe PC pentru o piesă din biblioteca PC-ului. | Ambele cozi ajung identice în câteva secunde. Piesa PC-ului apare în coada telefonului, marcată ca piesă de pe PC, și cântă când îi vine rândul. | Pe PC: `phone: play … next=…`. Telefon: liniile `cache: pc:… complete`. |
| 8 | **Transfer mare în timpul redării** | Telefonul cântă (local sau pe PC). Pe PC: un album → **Send to phone**. Acceptă pe telefon. Pe la jumătate, oprește Wi-Fi-ul telefonului 30 s, apoi pornește-l. | Muzica nu se întrerupe și comenzile răspund fără întârziere vizibilă. Transferul se reia de unde a rămas, nu de la zero. Fișierele apar în `Music/Harmony/From PC/` cu tag-urile originale, iar o a doua trimitere a aceluiași album nu creează dubluri. | Telefon: `transfer xxxx: run 0 started`, `file … cut at N KB …; again in …`, apoi `finished after S s · M MB on the phone · {done=…}`. PC: `com.harmony.transfer`. |
| 9 | **Ecran stins** | Redare pe PC din telefon, ecranul telefonului stins 10 min. Dă comenzi de pe PC (următoarea, pauză). | Telefonul nu se deconectează și nu pierde coada. Notificarea Harmony rămâne. | Fără `ended` în acest interval. Eventual câteva `computer not answering (1x)` urmate imediat de `answering again`. |
| 10 | **Doze** | Ca la 9, dar forțezi Doze (comenzile de mai jos), 5 min. | Ce se întâmplă trebuie notat: muzica pe PC continuă (PC-ul nu depinde de telefon pentru piesele lui). Telefonul poate rata comenzi cât e în Doze, dar la ieșire revine fără dubluri. | Ora intrării și ieșirii din Doze, apoi `answering again`. |
| 11 | **Crossfade pe PC** | Pe PC: Equalizer → **Crossfade** 6 s. Redă un album local pe PC. Apoi, de pe telefon, redă pe PC o listă de-a telefonului, cu Crossfade-ul telefonului pe 8 s. | Trecerile au fade, fără clic sau salt de volum. Piesele de pe telefon folosesc 8 s. Dacă o piesă nu e gata la timp, trecerea e normală, fără gol. | PC: `crossfade: 6000 ms into the next song` sau `crossfade: the next song isn't decoded yet…`. Pentru piesele telefonului: `phone: play … crossfade=8`. |
| 12 | **Repornire în mijlocul transferului** | În timpul testului 8: închide Harmony pe PC, apoi redeschide-l. | Transferul se reia singur, sau rămâne „Waiting for the phone” până telefonul îl cere din nou. Nimic corupt, nicio dublură. | PC: `transfer xxxx: … file(s) ready for …` după repornire. |

### Doze (testul 10)

```bash
adb shell dumpsys battery unplug
```

```bash
adb shell dumpsys deviceidle force-idle
```

Ieșirea din Doze:

```bash
adb shell dumpsys deviceidle unforce
```

```bash
adb shell dumpsys battery reset
```

---

## 2. Ce îmi trimiți

1. **Jurnalul telefonului.** Now Playing → **Play on** → **Save diagnostics**. Apare „Saved to Download/Harmony/harmony-connect-log-….txt”. Trimite fișierul din aplicația Files.
   - Fișierul conține Connect, coada comună, cache-ul pieselor PC-ului, transferurile și jurnalul Android Auto.
   - Cu adb, îl poți lua direct:

```bash
adb pull /sdcard/Android/data/com.harmony.app/files/connect-log.txt
```

2. **Jurnalul PC-ului.** Connect → **Open log folder** (`%LOCALAPPDATA%\Harmony\logs`). Trimite `harmony-0.log` (cel mai nou) și, dacă există, `harmony-1.log`.
3. **Notițele tale:** testul, ce ai auzit și ora aproximativă.
4. Modelul telefonului, versiunea Android și tipul de Wi-Fi (2,4 / 5 GHz, router sau mesh).

**Ce conțin jurnalele:** doar id-uri, poziții, durate și cauze. Nu conțin titluri de piese, nume de fișiere, tokenuri sau adrese cu tokenuri.

---

## 3. Ce măsor din jurnale

| Măsură | Țintă | De unde |
|---|---|---|
| Golul la handoff telefon → PC | < 1 s, ideal < 300 ms | `silence ≈ N ms` |
| Golul la handoff PC → telefon | < 1 s | `phone playing M ms after that` |
| Redare dublă | niciodată | ordinea liniilor: oprire confirmată înainte de pornire |
| Handoff-uri reușite | 5 din 5 pe Wi-Fi bun | `Moved` față de `Stayed` |
| Revenirea după cădere | < 2 s după ce rețeaua revine | `answering again` față de ora notată |
| Crossfade aplicat | toate trecerile pe PC, cu piesa următoare locală | `crossfade: … into the next song` |
| Transfer | reluat, nu de la zero; fără dubluri | `cut at N KB` apoi `finished`; numărul de fișiere în `From PC` |
