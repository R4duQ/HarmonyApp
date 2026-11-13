# Egalizare automată perceptuală în Harmony: analiză și recomandare

Etapa aceasta livrează o analiză și o recomandare, nu cod de produs. Fiecare afirmație
este marcată:

- **[D] demonstrat**: citit în cod, măsurat aici (cu metoda) sau citat dintr-o sursă oficială;
- **[I] ipoteză**: plauzibil, dar trebuie măsurat pe telefon sau confirmat cu cineva.

> **Actualizare (13.11.2026): Clarity Live implementat.** La cererea ta am construit direcția C:
> `native/harmonyeq/src/clarity.rs` (24 de benzi ERB care se reglează singure, ~190 de analize pe
> secundă, feed-forward, fără latență adăugată, analiză decimată peste 50 kHz), în lanț după
> ReplayGain/preamp și înaintea celor 10 benzi manuale. Auto se oprește cât rulează Clarity.
> Comutator, intensitate, echilibru și vizualizare live în ecranul Equalizer; oprit implicit.
> Cu Clarity oprit, ieșirea e identică bit cu bit cu motorul anterior (verificat la 44,1/48/96/192 kHz).
> iOS (motorul C++) nu îl are încă. Testul orb din secțiunea 6 rămâne de făcut.

## Ce am analizat

| Ce | Unde |
|---|---|
| Codul | `main` la **67e9807** (build 165). Ultima modificare a `native/harmonyeq`: **db6b364**. |
| Motorul Rust | `native/harmonyeq/src/{engine.rs, design.rs, lib.rs, fft.rs, ring.rs}`, `native/harmonyeq/tests/engine.rs` |
| Integrarea Android | `core/eq-engine/src/main/kotlin/com/harmony/core/eq/{HiResEqAudioSink, EqSinkStage, CustomEqAudioProcessor, AudioOutputMonitor}.kt`, `playback/service/.../player/{HarmonyPlayer, TwinPlayer}.kt` |
| iOS | `core/eq-engine/src/main/cpp/` (motorul C++, compilat încă de aplicația iOS) |
| Desktop | `desktop/src/main/kotlin/com/harmony/desktop/dsp/{Clarity, ClarityLayer}.kt`, `desktop/.../engine/AudioEngine.kt` |
| Istoric | commit **94153be** (Clarity adăugat pe telefon), **031eeef** (Clarity scos de pe telefon când a venit motorul nativ) |
| Soundtheory, citit la 13.11.2026 | soundtheory.com: pagina produsului, *Downloads → Tech Specifications*, *Support → FAQ*; *Gullfoss Operation Manual* (v1.11, rev. 1.3, 16.11.2021); *Gullfoss Changelog* (până la v1.12.1, 12.11.2026); *Soundtheory License Agreement*. Paginile site-ului sunt randate din JavaScript; textul l-am citit din pachetul aplicației site-ului și din PDF-urile oficiale. |
| Măsurători | Pe acest server (Intel Xeon 2,1 GHz, un nucleu, build release Rust). **Nu pe un telefon.** |

---

## Pe scurt

1. **Gullfoss nu poate intra în Harmony așa cum se vinde.** [D]
   - Există doar ca plugin AU/VST/VST3/AAX pentru macOS și Windows, cu procesoare x86 (SSE3/AVX) sau Apple Silicon.
   - Se activează prin iLok.
   - Licența interzice încorporarea în alte programe.
   - Pe site nu apare nicio versiune pentru Android sau iOS, niciun SDK și nicio ofertă OEM.

   Singura cale ar fi un acord comercial direct cu Soundtheory, iar existența lui e necunoscută.
2. **Harmony are deja un procesor perceptual de tip Gullfoss: *Clarity*.** [D]
   - Are 24 de benzi ERB, un model de mascare și compensare de loudness.
   - Este scris și testat în Kotlin, în aplicația Windows.
   - A rulat și pe telefon între build 125 și înlocuirea motorului EQ (031eeef). A fost scos odată cu vechiul lanț audio, nu în urma unui test care să-l fi găsit slab.
3. **Auto-ul actual nu estimează mascarea.** [D] Egalează un echilibru tonal pe 10 octave față de o curbă-țintă fixă. Nu are rezoluția necesară să vadă suprapunerea dintre voce și instrumente.
4. **Costul de calcul nu e problema; naturalețea este.** [D] Pe acest server, un stagiu de tip Clarity costă cam 0,8% dintr-un nucleu la 48 kHz stereo. Motorul actual costă cam 0,55%. [I] Pe un nucleu mic de telefon e probabil de 3–6 ori mai scump, deci tot mic.
5. **Latența de procesare nu contează la redarea de fișiere.** [D] Latența până la ieșire e dominată de bufferul AudioTrack și, pe Bluetooth, de codec. Cele ~21 ms ale Gullfoss nu ar fi o problemă. Compromisurile făcute de Gullfoss Live ca să coboare la 2 ms nu ne-ar aduce nimic.

**Recomandare:** un procesor propriu în Rust, pornit din Clarity-ul de pe desktop, ca stagiu în `harmonyeq`, în spatele unui comutator oprit implicit. Înainte de orice integrare, un test orb pe fragmente randate offline decide dacă merită (detalii la final).

---

## 1. Egalizatorul existent

### 1.1 Lanțul de semnal [D]

Pentru fiecare cadru, în float pe 32 de biți, cu filtre în f64 (`engine.rs`, comentariul de la început și `process_block`):

```
intrare (16/24/32-bit sau float, 8–384 kHz)
  → igienizare (NaN/Inf → 0, limită ±16 = +24 dBFS)
  → trece-sus după ieșire (Butterworth: 90 Hz difuzor, 18 Hz fir, 24 Hz Bluetooth, 10 Hz DAC USB)
  → preamp (headroom automat + pad Bluetooth −0,3 dB + preamp utilizator −24..+12 + ReplayGain −30..+15)
  → 10 biquad-uri DF2T (raft la 31 Hz și 16 kHz, Q 0,707; vârfuri la 62 Hz–8 kHz, Q 1,2→2,0 cu câștigul)
  → saturator cu genunchi moale peste −1,8 dBFS (tanh Padé)
  → limitator cu lookahead de 2 ms (plafon −0,15 / −0,4 BT / −0,05 USB dBFS)
  → ieșire → inel SPSC spre analizorul vizual
```

În paralel, pe intrare, înainte de trece-sus și de EQ, rulează un **banc de analiză**:

- 10 trece-bandă de o octavă (Q √2) pe mono, (L+R)/2;
- un detector de sub-bas (trece-jos 80 Hz, după trece-sus);
- un detector de sibilanță (trece-bandă de o octavă în jur de 5,66 kHz).

### 1.2 Ce face analiza spectrală [D]

Sunt **două analize separate**, cu roluri diferite:

1. **Pe firul audio**, cele 10 benzi de octavă dau patru plicuri:
   - energie rapidă (~50 ms), pentru preamp;
   - echilibru tonal lent (~1,2 s, integrat pe blocuri de 32 de cadre), pentru Auto;
   - RMS de bandă largă (~100 ms), care îngheață adaptarea sub −42 dBFS;
   - plicurile de tranzient (atac 8 ms, eliberare 120 ms) pentru sub-bas și sibilanță.
2. **Pe firul UI (60 fps)**, un FFT Hann de 2048 de puncte peste semnalul de **ieșire** desenează vizualizatorul. Nu influențează sunetul. Mostrele trec printr-un inel lock-free și sunt citite în ritmul redării, ca barele să se miște odată cu sunetul.

### 1.3 Ce face Auto [D] (`auto_offset_target_db`, `target_curve_db`)

- **Ce compară:** echilibrul tonal pe ~1,2 s cu o curbă-țintă pe octave.
  - Pe căști, Bluetooth și DAC: o pantă caldă de −4,5 dB pe octavă.
  - Pe difuzorul telefonului: aceeași pantă, cu mijlocul ridicat și fără sub-bas așteptat.
- **Corecția:** offsetul este −0,75 × diferența, limitat la ±4,5 dB.
- **Reguli fixe pe bandă:**
  - 31, 62 Hz, 8, 16 kHz pot doar urca (*Boost*);
  - 125, 250 Hz, 2, 4 kHz pot doar coborî (*Cut*);
  - 500 Hz și 1 kHz nu se mișcă (*Hold*).
- **Siguranțe:**
  - nu ridică o bandă aflată la peste 24 dB sub țintă (partea de sus tăiată a unui MP3, zgomot);
  - nu ridică sub-basul pe difuzor;
  - offseturile alunecă spre țintă cu ~1 s;
  - totul îngheață în liniște.
- **Două „îmblânzitoare” rapide:**
  - **sub-basul** peste −5 dBFS scade *boost-ul* de la 31/62 Hz;
  - **sibilanța** peste −18 dBFS scade *boost-ul* de la 4/8 kHz;
  - ambele cu cel mult 4 dB. **Ele doar retrag un boost existent; nu taie niciodată sub zero** (`gain -= min(duck, max(0, gain))`). Deci nu sunt un de-esser și nici un supresor de rezonanțe: dacă sursa e stridentă și nimeni n-a ridicat 4 kHz, nu se întâmplă nimic.
- **Starea Auto supraviețuiește seek-ului și reset-ului** (comentariul din `reset`). Cu `TwinPlayer` fiecare piesă rulează pe unul din două motoare, alternativ. Fiecare motor își păstrează starea de la piesa pe care a cântat-o el, adică de acum două piese. Deci o piesă nouă pornește Auto de la echilibrul piesei N−2 și converge în 1–2 s. [I] Dacă se aude, e de măsurat.

### 1.4 Preamp, ReplayGain, headroom [D]

- **Headroom-ul automat** ponderează câștigul curbei cu energia rapidă din fiecare bandă: +10 dB la 62 Hz costă 10 dB pe o linie de bas și aproape nimic pe o voce. Se ia repede (atac 5 ms) și se dă înapoi încet (eliberare 300 ms).
- **ReplayGain** se setează pe fiecare motor la `onMediaItemTransition` (`HarmonyPlayer`), înaintea limitatorului, și rulează și cu EQ-ul oprit.
- [I] Headroom-ul automat variază cu muzica, deci este un control de nivel dependent de semnal. Eliberarea lentă evită pompajul în cazurile obișnuite, dar asta nu e măsurat.

### 1.5 Netezirea [D]

- Controalele sunt citite la fiecare 32 de cadre.
- **Coeficienții** fiecărui biquad alunecă eșantion cu eșantion spre țintă: un pol, ~8 ms, interpolare direct pe b0..a2.
- **Preamp-ul** alunecă cu ~15 ms.
- Când un filtru și-a atins ținta, se fixează exact pe ea.

[I] Interpolarea directă a coeficienților e sigură pentru pași mici și filtrele de aici, dar nu garantează că răspunsul intermediar „trece printre” cele două curbe. Pentru un procesor care mișcă 24 de benzi de sute de ori pe secundă, alegerea topologiei contează (vezi 4.3).

### 1.6 Saturatorul și limitatorul [D, măsurat]

- **Limitatorul:**
  - atac instantaneu, eliberare 80 ms;
  - minim pe fereastra de lookahead (deque monoton), apoi o medie „box” pe 2 ms;
  - latență de `lookahead − 1` cadre, compensată: primele cadre sunt reținute și `drain()` le eliberează la final;
  - plafonul e pe eșantion, nu true-peak; pad-ul Bluetooth acoperă parțial vârfurile dintre eșantioane.
- **Saturatorul rulează mereu, chiar cu EQ-ul oprit** („the protection stages stay on either way”). L-am măsurat cu o sinusoidă de 1 kHz, EQ oprit, căști pe fir, după 0,5 s de încălzire (binarul `thd` dintr-un banc Rust scris în această sesiune peste `harmonyeq`, nepăstrat în repository):

| Nivel intrare | Vârf ieșire | THD |
|---|---|---|
| −6 dBFS | −6,00 dBFS | ~0 (−157 dB) |
| −3 dBFS | −3,00 dBFS | ~0 (−152 dB) |
| −1 dBFS | −1,04 dBFS | **0,15 %** (−56 dB) |
| −0,5 dBFS | −0,65 dBFS | **0,65 %** (−44 dB) |
| −0,1 dBFS | −0,42 dBFS | **1,40 %** (−37 dB) |

**Ce înseamnă:** fără ReplayGain, vârfurile unui master modern trec peste −1,8 dBFS și sunt modelate neliniar, chiar dacă utilizatorul a oprit EQ-ul. Pe o sinusoidă continuă acesta e cazul cel mai rău. [I] Pe muzică sunt afectate doar vârfurile scurte, iar audibilitatea trebuie testată. Constatarea contează pentru obiectivul „păstrăm caracterul înregistrării” și este independentă de decizia despre Gullfoss (vezi 3, direcția B).

### 1.7 Fire, buffere, platforme [D]

- **Firul audio** este firul de redare al ExoPlayer: `HiResEqAudioSink` rulează EQ-ul în fața unui `DefaultAudioSink` cu ieșire float.
  - Procesarea rulează **înaintea** bufferului AudioTrack, nu în callback-ul lui.
  - Mutex-ul stării audio e luat doar de acest fir, deci nu e contestat.
  - Controalele sunt atomice; telemetria trece prin inel SPSC.
  - Nicio alocare pe calea audio. Starea se repară singură la NaN.
- **Consecința bufferului:** ce schimbi în EQ se aude abia după ce se golește bufferul AudioTrack. [I] La valorile implicite din Media3 pentru PCM, acesta e de ordinul a 250–750 ms, de verificat pe telefon. Asta afectează direct o comparație înainte/după (vezi 5.3).
- **TwinPlayer:** fiecare din cele două playere are propriul motor EQ. În crossfade rulează amândouă, deci costul se dublează pe durata tranziției.
- **Bit-perfect** pe DAC USB ocolește motorul complet.
- **iOS** compilează încă motorul **C++** (`core/eq-engine/src/main/cpp/README.md`). Orice stagiu nou trebuie scris de două ori până se mută și iOS pe Rust.
- **Desktop** are lanțul lui DSP în Kotlin, pe PCM de 16 biți: EQ Winamp, Tone, **Clarity**, Even volume, limitator moale.
- **„Cast”**: în cod nu există Google Cast. Echivalentul e **Harmony Connect**, unde PC-ul redă prin propriul lanț. Setările de EQ ale telefonului nu circulă în `PlayRequest`.
- **Performanță** [D, server, nu telefon]:
  - testul din repo (`cargo test --release -p harmonyeq --features testing`) dă **33× timp real la 192 kHz stereo**;
  - bancul meu dă **~180× timp real la 48 kHz stereo**, adică ~0,55% dintr-un nucleu.

### 1.8 Ce lipsește pentru o estimare reală a mascării și ce putem reutiliza

**Lipsește** [D, din cod]:

1. **Rezoluție în frecvență.** O octavă la 250 Hz acoperă ~180–350 Hz. Vocea, chitara și pianul se suprapun tocmai în benzi mai înguste decât atât. O rezonanță de 2,8 kHz se pierde în octava de 2 kHz. Pentru mascare e nevoie de rezoluție de ordinul benzilor critice:
   - lățimea ERB la 100 Hz ≈ 35 Hz;
   - la 1 kHz ≈ 133 Hz;
   - la 5 kHz ≈ 564 Hz (formula Glasberg & Moore: 24,7·(4,37·f/1000 + 1)).
2. **Un model de împrăștiere a mascării.** O componentă puternică acoperă vecinii, mai abrupt în jos și mai lin în sus. Panta din sus depinde de nivel.
3. **Pragul absolut de audibilitate**, ca să nu fie „recuperat” zgomotul.
4. **Un model de loudness**, ca procesorul să nu pară „mai bun” doar pentru că e mai tare.
5. **Decizii dependente de semnal, nu de o curbă-țintă fixă:** ce e dominat (de scos în față) și ce e dominant (de îmblânzit), în fiecare moment.
6. **Stereo:** analiza actuală vede doar (L+R)/2. Conținutul din lateral, cum ar fi reverbul sau dublurile panoramate, e invizibil, iar anularea de fază în mono poate păcăli analiza.
7. **Tăieri dinamice reale.** „Îmblânzitorul de stridență” trebuie să poată tăia sub zero.

**Se poate reutiliza** [D]:

- **din `harmonyeq`:**
  - proiectarea biquad-urilor (RBJ), DF2T în f64, netezirea;
  - poarta de liniște, conștiința dispozitivului (trece-sus, plafon, curbă);
  - limitatorul (după corecția saturatorului), compensarea latenței (`prime_frames`, `drain`) cu bufferele dimensionate pentru 384 kHz;
  - FFT-ul radix-2 (de înlocuit cu unul real pentru jumătate din cost), inelul SPSC, flush-ul de denormale, auto-repararea și testele de motor;
- **din desktop, aproape tot modelul:**
  - `ClarityBands`: 24 de benzi pe scara ERB, 40 Hz–16 kHz;
  - `ClarityModel`: ponderarea urechii (Terhardt), pragul în liniște, împrăștiere cu pante 27 dB/Bark în jos și dependentă de nivel în sus, integrare de 60 ms, *Recover*/*Tame*, înclinare, potrivire de loudness Zwicker (exponent 0,23);
  - `ClarityFilterBank.fit`: ajustarea a 24 de filtre peaking ținând cont de suprapunerea lor;
  - testele existente (`ClarityLayerTest`, `ClarityModelTest`);
- **din `harmonydsp`**, analizorul offline al bibliotecii (LUFS, energii pe benzi, centroid, contrast): bază pentru un profil pe piesă calculat o singură dată (vezi 4.6).

---

## 2. Se poate integra Gullfoss?

### 2.1 Ce spun sursele oficiale [D]

| Subiect | Sursa oficială (citat sau rezumat fidel) |
|---|---|
| Ce este | „Gullfoss is an equalizer that intelligently adjusts its settings at every moment to improve the clarity of your audio signal.” (FAQ) |
| Ediții | Gullfoss („20ms latency, lower CPU consumption”), Gullfoss Live („Latency below 2ms”, „Minimal treatment of transients, leading to a different sound character”), Gullfoss Master („20ms latency, higher CPU consumption”). |
| Specificații | „Mono-to-mono or stereo-to-stereo processing. Stereo processing is compatible with M/S channels.” · „Supported sample rates from 16 kHz to 384 kHz.” · „Approximately 20ms of processing latency for Gullfoss and Gullfoss Master and 2ms for Gullfoss Live.” · „Approximately 1000 auditory perception model updates per second.” · „Approximately 300 equalizer updates per second.” · „Preservation of perceived loudness and dynamics.” |
| Cerințe | „macOS 10.9 or greater / Windows 7 or greater.” · „Apple Silicon processor / Intel, AMD or compatible processor not older than 2008 and with support for SSE3.” · „Graphics processor with support for OpenGL 3.2 or later.” · „iLok License Manager for activation (dongle is not required).” · „64 bit plug-in host with support for AU, VST, VST3 or AAX Native.” |
| Latența, din manual | „Gullfoss requires knowledge of a 20 milliseconds time-frame to make an EQ decision and therefore introduces a latency of around 21 milliseconds.” Despre Live: „In some situations Gullfoss Live has to try to predict the future. This mostly affects quick changes in the signal and may impact the ability to accurately process percussive elements…” |
| CPU, din manual | Setarea de calitate *Best* poate cere „up to four times the load” față de *Normal*. Changelog v1.12.1 (12.11.2026): „Made the latency independent of the quality setting.” |
| Activare | „Your iLok license provides you with 2 activations.” Trial: „fully functional, but has a time-limit of two weeks.” Licențe limitate la 25 de ani începând cu 16.06.2024. |
| Licența (EULA) | 2.4(b): nu ai voie să „sub-license … adapt, vary or modify the Software”. 2.4(c): nici să „permit the Software or any part of it to be combined with, or become incorporated in, any other programs”. 2.4(d): nici „disassemble, decompile, reverse-engineer or create derivative works”. 2.1: „personal or business use only”. |
| Platforme mobile, SDK, OEM | **Nu apar nicăieri pe site.** Am căutat în tot pachetul site-ului: „Android”/„iPhone” apar doar în codul bibliotecilor web. Contactul public este support@soundtheory.com. |
| Versiune curentă | 1.12.1 (12.11.2026). Manualul public e pentru 1.11. |

### 2.2 Pe platformele Harmony

- **Android:** imposibil cu produsul public. [D] Nu există binare ARM64 pentru Android, iar Harmony nu este gazdă de plugin-uri. [I] Chiar cu un SDK, ar trebui verificat că nucleul DSP nu depinde de SSE3/AVX (pe ARM e NEON) și nici de OpenGL pentru partea non-grafică.
- **iOS:** la fel. [D] Nu există AUv3 pentru iOS în lista oficială.
- **Desktop (Windows):** tehnic, Harmony ar putea deveni gazdă VST3 prin JNI. Fiecare utilizator ar trebui să aibă propria licență Gullfoss și iLok, plus un GPU OpenGL 3.2. [D] Licența interzice însă combinarea cu alte programe (2.4(c)). [I] Încărcarea unui plugin cumpărat de utilizator într-o gazdă e uzul normal al oricărui DAW; probabil nu încalcă 2.4(c), dar e o întrebare pentru un jurist. Oricum, ar fi o funcție de nișă: doar cine are deja Gullfoss.

### 2.3 Latența de procesare față de latența totală

- **Latența pluginului** [D]: ~21 ms (Gullfoss, Master), ~2 ms (Live). Este timpul de lookahead al deciziei. Într-un DAW, gazda îl compensează.
- **Latența totală până la ieșire pe Android** se adună din:
  - decodare;
  - EQ-ul Harmony (2 ms de lookahead acum);
  - **bufferul AudioTrack**: [I] sute de ms la valorile implicite Media3, de măsurat;
  - mixerul sistemului și HAL-ul;
  - pe **Bluetooth A2DP**, bufferul și codecul: [I] tipic 100–300 ms, în funcție de codec și căști.
- **Ce înseamnă pentru Harmony:** redă fișiere și le citește înainte, deci 20 ms de lookahead în plus nu se aud ca întârziere la redare. Contează doar la:
  - timpul de pornire după seek (+20 ms, neglijabil față de buffer);
  - reacția la controale și la A/B (deja dominată de buffer);
  - sincronizarea cu imaginea (Harmony n-are video; vizualizatorul își compensează deja întârzierea).

**Concluzie:** compromisurile pe care le face Gullfoss Live ca să ajungă la 2 ms, în special pe tranzienți, nu ne aduc nimic.

### 2.4 De confirmat direct cu Soundtheory

1. Există un SDK sau o licență OEM pentru încorporarea motorului într-o aplicație terță? În ce condiții (taxă fixă, redevență pe instalare)?
2. Rulează nucleul pe ARM64 Android (NDK) și pe iOS, fără interfață și fără OpenGL? Cu ce cost CPU pe un nucleu Cortex-A55 sau A78 la 44,1/48 kHz stereo?
3. Activarea fără iLok: e posibilă într-o aplicație distribuită prin magazin, offline?
4. Comportamentul pe **master-uri finale** (Gullfoss e gândit pentru mix și mastering), pe conținut **cu pierderi** (MP3/AAC) și înaintea unui **codec Bluetooth**.
5. Cum se comportă cu buffere de gazdă variabile și mari (Media3 trimite bucăți de dimensiuni variabile) și la schimbări de sample rate în flux.
6. Fixarea parametrilor și a „volumului intern de ascultare” al modelului (Gullfoss Master îl menționează) pentru redare la volum variabil.
7. Ce înseamnă pentru ei termenul de 25 de ani și transferul licenței, aplicat unui produs încorporat.
8. Dacă au ceva împotriva denumirilor de parametri. Clarity-ul nostru de pe desktop folosește exact cele cinci nume ale Gullfoss: *Recover, Tame, Bias, Brighten, Boost*. [I] Cuvintele generice probabil nu sunt protejate, dar aș redenumi oricum, ca să nu sugerăm o legătură care nu există. Pentru un răspuns ferm e nevoie de un jurist.

---

## 3. Compararea direcțiilor

| | A. Gullfoss comercial | B. Auto-ul actual îmbunătățit | C. Procesor perceptual propriu în Rust |
|---|---|---|---|
| Beneficiu probabil | Mare [I]: produs matur, reglat pe mii de mixuri. Dar e gândit pentru mix/master, nu pentru redarea unor master-uri finale. | Mic pentru claritatea vocii [I]. Corectează echilibrul tonal, nu mascarea. | Mediu spre mare [I]. Depinde aproape doar de reglaj. |
| Fezabilitate azi | **Nulă** fără un acord (2.1–2.2). | Imediată. | Imediată. Modelul există deja în Kotlin (desktop). |
| Complexitate | Necunoscută (SDK inexistent public). Juridic și comercial: mare. | Mică: zile. | Medie: 2–4 săptămâni pentru port, teste și integrare. Reglajul și testele de ascultare sunt partea lungă. |
| Mentenanță | Dependență de un furnizor închis (ABI, actualizări, activare). Nu putem repara nimic. | Mică. | Medie. Cod propriu, testat pe gazdă, de dublat în C++ pentru iOS până la mutarea pe Rust. |
| Consum | Necunoscut pe ARM. *Best* ≈ 4× *Normal* pe desktop [D]. | Neschimbat (~0,55% nucleu pe server [D]). | +0,8% nucleu pe server [D]. [I] Pe un nucleu mic de telefon ~3–6×, tot sub câteva procente. |
| Latență | 21 ms [D]: irelevant pentru redare (2.3). | 2 ms, neschimbat. | 0 ms adăugat cu analiza feed-forward pe IIR. Opțional 10–20 ms de lookahead pentru tranzienți (4.2). |
| Potrivire cu arhitectura | Proastă: plugin x86/Apple, iLok, UI OpenGL. Harmony nu e gazdă. | Perfectă. | Perfectă: același motor, aceleași fire, aceleași teste. |
| Risc principal | Nu se poate face. | Nu atinge obiectivul (mascarea). | Corecții nenaturale: pompaj, zgomot de codec scos în față, conflict cu EQ-ul manual. |

**Direcția B are oricum câteva corecturi utile**, independente de decizie:

1. **Saturatorul**, care adaugă distorsiune pe vârfuri chiar cu EQ-ul oprit (1.6). De acționat doar când semnalul chiar a fost ridicat (EQ, preamp sau ReplayGain pozitive), altfel doar limitatorul.
2. **„Îmblânzitorul de stridență”**, care poate doar retrage un boost (1.3).
3. **Starea Auto moștenită de la piesa N−2** cu `TwinPlayer`.
4. **Documentația:** `docs/DESKTOP-v1.0.md` spune despre Clarity „the same as on the phone”. Nu mai e adevărat din 031eeef.

**Contest câteva presupuneri:**

- **„Mai multă claritate între voce și instrumente.”** Un egalizator, oricât de inteligent, aplică același câștig întregului mix la o frecvență dată. Nu poate separa vocea de chitară *în aceeași bandă*. Poate doar muta echilibrul, clipă de clipă, spre ce era acoperit. Separarea reală înseamnă separare de surse (ML, de tip Demucs): [I] sute de MB, consum mare, artefacte. Nu o recomand pe telefon.
- **„Reducerea mascării” pe fișiere cu pierderi.** Codecurile perceptuale (MP3, AAC, Opus) aruncă sau cuantizează grosier tocmai ce e mascat. „Recover” poate ridica zgomotul de cuantizare. [I] Pe FLAC e o problemă mică; pe MP3 la 128–192 kbps poate fi audibilă; trebuie testat explicit (6.2). Același lucru, în sens invers, înaintea unui codec Bluetooth SBC: semnalul procesat schimbă ce trebuie codat.
- **„În timp real.”** Harmony redă fișiere locale, deci poate vedea viitorul: are bufferul Media3 și poate analiza piesa offline. Un procesor de redare nu trebuie să ghicească precum Gullfoss Live. Și nici nu trebuie să pornească „la rece” la fiecare piesă (4.6).
- **„Gullfoss ca reper.”** Ca *reper de ascultare*, da. Trialul de două săptămâni permite randarea câtorva fragmente într-un DAW, de comparat orb cu procesorul nostru. Ca *țintă de copiat*, nu: licența interzice ingineria inversă, deci nu ajustăm algoritmul ca să-i reproducă ieșirea.

---

## 4. Arhitectura unui prototip propriu

Îl consider justificat: costul de pornire e mic, pentru că modelul există, iar întrebarea reală (sună mai bine?) se poate decide repede (6).

### 4.1 Bancă de filtre perceptuale sau STFT

| | Bancă de filtre IIR (24 peaking pe ERB), comandată de o analiză FFT | STFT cu câștig pe fiecare bin (overlap-add) |
|---|---|---|
| Latență adăugată | **0** în varianta feed-forward. Analiza privește trecutul. | Cel puțin o fereastră: 2048 de cadre ≈ **43 ms** la 48 kHz. |
| Rezoluție de *corecție* | 24 de benzi netede, ca o curbă de EQ. | Un câștig per bin: ~23 Hz la 2048/48 kHz. |
| Artefacte tipice | Faza filtrelor minime (ca orice EQ analogic); modulație de coeficienți dacă se mișcă prea repede. | „Muzicalitate” sau zgomot muzical la câștiguri care variază de la un bin la altul; pre-ecou pe tranzienți; netezire obligatorie între cadre și pe frecvență. |
| Tranzienți | Filtrul reacționează în timp, fără pre-ecou. | Fereastra de 43 ms împrăștie tranzientul; pre-ecoul apare dacă câștigul se schimbă între cadre. |
| Cost măsurat aici [D] | **0,83%** dintr-un nucleu (24 biquad/canal + FFT 2048/512 + mascare 24×24). | **1,06%** dintr-un nucleu (FFT înainte și invers per canal, hop 512). |
| Potrivire cu motorul | Aceleași biquad-uri, aceeași netezire, același limitator. | Un al doilea tip de procesare, cu buffere OLA și latență nouă de compensat. |

Măsurătorile sunt din bancul `clarbench` (Rust, release, 60 s de zgomot la 48 kHz stereo, un nucleu Xeon 2,1 GHz). FFT-ul folosit e cel complex din `harmonyeq`; un FFT real ar înjumătăți partea de FFT la ambele variante.

**Aleg banca de filtre comandată de FFT.** E ce face Clarity, iar după datele oficiale e și ce sugerează Gullfoss: ~1000 de actualizări ale modelului pe secundă, ~300 ale EQ-ului. Corecția e un EQ continuu, nu o mască pe bin-uri.

### 4.2 Rezoluție în frecvență, timp de analiză, viteză de reacție

O fereastră FFT de N eșantioane la rata fs dă bin-uri de fs/N Hz și „vede” N/fs secunde:

| N la 48 kHz | Bin | Fereastră | Ce rezolvă |
|---|---|---|---|
| 1024 | 46,9 Hz | 21 ms | Benzi ERB peste ~300 Hz. Basul se amestecă. |
| 2048 | 23,4 Hz | 43 ms | Benzi ERB peste ~100 Hz. E alegerea Clarity. |
| 4096 | 11,7 Hz | 85 ms | Tot basul, dar reacție lentă și tranzienți întinși. |

- **Compromisul.** O bandă ERB la 100 Hz are ~35 Hz, deci ai nevoie de ~2048 de eșantioane ca s-o separi de vecină. La 5 kHz banda are 564 Hz, iar 256 de eșantioane (5 ms) ar ajunge.
- **Recomandarea:**
  - **analiză pe două rezoluții**: 2048 pentru benzile de sub ~500 Hz, 512 pentru cele de sus, cu același hop de ~5–10 ms;
  - **sau** o singură fereastră de 2048 cu hop de 256 (~5 ms), a cărei latență de decizie se acoperă cu un **lookahead** de 10–20 ms, ca la Gullfoss.

  Lookahead-ul se poate permite pentru că redăm fișiere (2.3). Mecanismul de latență există deja în motor (`prime_frames`, `drain`). Bufferul de întârziere pentru 20 ms la 384 kHz și 8 canale are ~245 KB, prealocat.
- **Viteza de reacție.** Modelul se actualizează la fiecare hop. Filtrele urmează curba cu un atac rapid când trebuie să se îndepărteze de zero (Clarity: 40 ms) și cu o revenire lentă (250 ms). [I] Valorile sunt de reglat la ascultare. Prea rapid înseamnă pompaj și „respirație”; prea lent înseamnă că rezonanțele trecătoare scapă.
- **Sample rate mare.** Peste 20 kHz nu e nimic de analizat. La 96–384 kHz, analiza rulează pe semnal decimat (÷2…÷8), deci costul rămâne ca la 48 kHz. Clarity trece acum doar la 4096 de puncte peste 50 kHz.

### 4.3 Cum limităm corecțiile

1. **Limite absolute:** *Recover* cel mult +4 dB, *Tame* cel mult −5 dB la intensitatea implicită, totalul pe bandă în [−9, +8] dB (valorile Clarity). Intensitatea maximă ar trebui să fie mai prudentă decât acum: Clarity permite o pondere de 3,2×. FAQ-ul Gullfoss avertizează și el că valorile mari sună strident.
2. **Limită de viteză:** câștigul unei benzi se schimbă cu cel mult X dB/s ([I] 20–40 dB/s de reglat). Peste prag, nu sunt ascultate tranziții.
3. **Netezire pe frecvență** (Clarity: 0,2 din vecini) și ajustare care ține cont de suprapunerea filtrelor (`fit`).
4. **Nu recuperăm ce nu e muzică:**
   - pragul absolut și condiția de „prezent” (marja față de mascare peste −20 dB);
   - **detecția tăieturii de codec**: banda de sus goală a unui MP3 nu se ridică, ca regula „absent” din Auto;
   - **limite de frecvență pe ieșire**: nimic sub 90 Hz pe difuzorul telefonului.
5. **Îngheț în liniște și în fade-out** (Clarity: sub −62 dB ponderat).
6. **Topologie stabilă la modulare:** biquad-uri DF2T actualizate la sub-bloc, cu pași mici în dB, nu salturi de coeficienți. [I] O variantă *SVF* (Cytomic/Simper) e mai robustă la modulare rapidă și merită încercată dacă testele arată „zipper”.

### 4.4 Imaginea stereo și dinamica

- **Stereo:** **un singur set de câștiguri pentru ambele canale** (legat), deci imaginea și faza dintre canale rămân neatinse. Analiza nu ar trebui să ruleze pe (L+R)/2, ci pe **max(putere L, putere R)** per bandă sau pe M și S separat, ca să nu rateze conținutul lateral. Un mod M/S separat (ca Gullfoss, „compatible with M/S channels”) poate veni mai târziu.
- **Dinamica:**
  - corecția e spectrală, iar loudness-ul total se ține constant prin potrivirea Zwicker (`matchDb`), urmată lent (~1,5 s), ca să nu devină un compresor;
  - pe tranzienți (detectați din fluxul spectral), câștigurile se îngheață pentru câteva ms, ca procesorul să nu „mănânce” atacul tobelor; lookahead-ul ajută aici;
  - se verifică explicit LRA și factorul de creastă, înainte și după (6.2).

### 4.5 Cooperarea cu EQ-ul manual, ReplayGain, preamp-ul și limitatorul

Ordinea propusă în `harmonyeq`:

```
igienizare → trece-sus (dispozitiv) → ReplayGain + preamp utilizator + headroom automat
  → [Claritate: analiză feed-forward pe intrarea ei + 24 de filtre ERB legate stereo, cu potrivire de loudness]
  → EQ manual (10 benzi)
  → saturator (doar când e nevoie, 1.6) → limitator → ieșire
```

- **Analiza după ReplayGain.** Modelul depinde de nivel (pantele de mascare, pragul în liniște). Cu loudness-ul normalizat de ReplayGain, el „aude” fiecare piesă la un nivel comparabil. [I] Se poate da modelului și volumul de sistem (`AudioManager`), ca nivel de ascultare presupus. Acum Clarity presupune 90 dB SPL la 0 dBFS.
- **Claritatea înaintea EQ-ului manual.** Procesorul corectează înregistrarea, iar gustul utilizatorului se aplică la urmă. Ordinea inversă ar face procesorul să „lupte” cu un boost voit și să-l anuleze.
- **Claritatea înlocuiește Auto, nu se adaugă peste el.** Două sisteme adaptive în serie care urmăresc ținte diferite oscilează sau se anulează. Când Claritatea e pornită, offseturile live ale Auto se opresc. Curba-țintă a Auto poate rămâne ca înclinare (*Brighten*).
- **Headroom.** Câștigurile Clarității intră în calculul `need_db` al preamp-ului automat, la fel cum intră azi ale benzilor. Limitatorul rămâne ultimul. Potrivirea de loudness a Clarității înseamnă că, în medie, nu adaugă nivel.
- **Latența.** Dacă se adaugă lookahead (4.2), `latency_frames()` crește. Mecanismul existent de amorsare și golire o compensează fără schimbări în Kotlin.

### 4.6 Profilul offline pe piesă (opțional, pasul 2)

- **Ce face:** `harmonydsp` analizează deja biblioteca (LUFS, energii pe benzi, contrast spectral). Ar putea salva și echilibrul pe termen lung al fiecărei piese în cele 24 de benzi ERB. Procesorul ar porni atunci „cald”, cu echilibrul piesei (`balance` din Clarity, învățat acum în ~3 s). Partea în timp real ar rămâne doar cea rapidă: mascare clipă de clipă și rezonanțe trecătoare.
- **Costul:** o schimbare de `ANALYSIS_VERSION`, adică reanalizarea bibliotecii.

---

## 5. Utilizarea de zi cu zi

### 5.1 Evenimente

| Eveniment | Ce face motorul azi [D] | Ce trebuie să facă procesorul |
|---|---|---|
| Piesă nouă, gapless, același format | Același motor continuă (`EqSinkStage.startNextItem`); Auto nu știe că s-a schimbat piesa. | Să primească un semnal de „piesă nouă”, fie un apel nou din `EqSinkStage`, fie din `onMediaItemTransition`, care e însă decalat de buffer. Să uite echilibrul lung, sau să-l ia din profilul offline (4.6). |
| Piesă nouă cu crossfade (`TwinPlayer`) | Motorul celălalt preia, cu starea Auto de la piesa N−2 (1.3). | Reset al echilibrului la startul piesei (playerul care așteaptă o încarcă cu 6 s înainte, deci poate învăța înainte să fie auzit). Pe durata crossfade-ului, costul e dublu. |
| Seek | `reset()`: filtrele, plicurile și limitatorul se golesc; echilibrul Auto se păstrează. | La fel: păstrează echilibrul piesei, golește integratorii scurți și plicurile, ține câștigurile curente ca să nu „sară”. |
| Pauză | Nu se procesează nimic; vizualizatorul cade după 0,25 s. | Nimic. La reluare continuă din starea curentă. |
| Schimbare de sample rate | `configure()` / `nativeSetSampleRate` recalculează tot; bufferele sunt dimensionate pentru 384 kHz. | Recalculează filtrele și harta de bin-uri; analiză decimată peste 48 kHz (4.2); fără alocare. |
| Bluetooth | Pad −0,3 dB, plafon −0,4 dBFS, trece-sus 24 Hz. | [I] Intensitate implicită mai mică pe codecuri slabe (SBC). Testat înainte și după codec (6.2). |
| Difuzorul telefonului | Trece-sus 90 Hz, curbă proprie. | Fără *Recover* sub ~150 Hz. Folosește limitele de frecvență ale dispozitivului. |
| Bit-perfect USB | Motorul e ocolit. | La fel: Claritatea e indisponibilă și se spune asta în UI. |
| Harmony Connect („Cast” spre PC) | PC-ul redă prin lanțul lui; setările telefonului nu circulă. | Fie trimitem setările Clarității în `PlayRequest`, fie spunem clar în UI că PC-ul folosește Claritatea lui. Desktopul o are deja. |
| Descărcare în fundal (Mornye) | Concurează pentru CPU; firul audio n-are prioritate specială în cod. | De măsurat (6.3). Procesorul trebuie să aibă un cost per buffer previzibil. |

### 5.2 Fire, buffere, memorie

- **Totul pe firul audio existent**, în `process()`, ca acum. Analiza (FFT la fiecare hop, mascarea 24×24) rulează în linie, deterministic, fără mutex nou și fără alocare.
  - De ce nu pe un fir separat: un fir care calculează modelul și trimite câștigurile printr-un inel ar netezi vârfurile de CPU, dar ar aduce nedeterminism (câștiguri aplicate la momente care depind de planificator) și un mod de eșec nou (firul întârzie). Bufferul AudioTrack de sute de ms oferă oricum o marjă mare.
  - Riscul real e altul [I]: un apel `process()` care cade pe un nucleu mic, la frecvență joasă, în timp ce rulează și o descărcare.
- **Costul per buffer:** un buffer Media3 de ~10–20 ms conține 1–4 hop-uri de analiză. Costul e în trepte, dar mărginit, iar cazul cel mai rău se măsoară direct (6.3).
- **Memorie** [D, din dimensiuni]:
  - analiza: inel de 2048–4096 de float-uri, ferestre, spectru;
  - starea a 24×8 filtre;
  - lookahead-ul opțional: ~245 KB la 384 kHz/8 canale.
  - Totul prealocat în `Engine::new`, sub 1 MB per motor (două motoare cu `TwinPlayer`).
- **Siguranță:** aceeași auto-reparare la NaN și același flush de denormale. Orice panică e prinsă în stratul JNI (`guard`), cum e deja.

### 5.3 Controale pentru utilizator

- **Un comutator „Claritate”** în ecranul EQ, oprit implicit, în locul butonului AUTO când e pornit (4.5).
- **Un singur cursor „Intensitate”**, 0–100%, care mișcă împreună *Recover* și *Tame*. Implicit 50%, adică „Balanced” din Clarity.
- **Opțional, „Echilibru”**, între „îmblânzește” și „scoate în față” (*Bias*). Restul (înclinare, nivel) rămâne în presetări, nu în fața utilizatorului.
- **Înainte/după.** Un buton „ține apăsat pentru original”, cu loudness egal (potrivirea Zwicker rămâne activă pe ramura ocolită), ca să nu câștige varianta mai tare.
  - [D] Din cauza bufferului AudioTrack, comutarea se aude după acesta (1.7). Pentru o comparație corectă, comutarea trebuie să fie o tranziție de ~50 ms în motor, iar UI-ul trebuie să arate „se aplică…” cât trece bufferul. Altfel utilizatorul compară, fără să știe, aceeași variantă.
  - Testele riguroase se fac oricum offline (6.1).
- **Vizualizare:** curba aplicată, peste vizualizatorul existent (Clarity are deja `readout()`, cu nivel, prag de mascare și câștig). Ajută și la diagnostic.
- **Nume proprii pentru parametri**, nu cele ale Gullfoss (2.4, punctul 8).

---

## 6. Cum demonstrăm valoarea

### 6.1 Ascultare comparativă

- **Material:** 20–25 de fragmente de 15–30 s, cel puțin 4 pe categorie:
  1. **voce în față**: pop, folk, rap, inclusiv românească din bibliotecă;
  2. **mix dens**: rock, metal, EDM, orchestră;
  3. **bas puternic**: 808, dub;
  4. **tranzienți**: tobe acustice, pian, chitară ciupită;
  5. **pasaje liniștite**: jazz, clasic, fade-out-uri, reverb lung.

  Fiecare fragment în **FLAC** și în **MP3 la 128 kbps** (sau AAC), pentru riscul de codec.
- **Variante:**
  - ocolire;
  - Auto-ul actual;
  - procesorul la 50% și la 100%;
  - opțional, **Gullfoss** randat în trialul de două săptămâni, ca reper de ascultare (nu ca țintă de copiat, vezi 3).
- **Egalizarea loudness-ului:** toate variantele la **același LUFS integrat, ±0,5 LU** (ITU-R BS.1770, cu analizorul din `harmonydsp` sau ffmpeg `ebur128`). Altfel câștigă cea mai tare.
- **Metoda:**
  - **orb**, randat **offline** din același cod Rust (un CLI peste `harmonyeq`), redat pe telefon cu căștile obișnuite, dar și pe Bluetooth și pe difuzor;
  - **test de preferință în perechi** cu ordine aleatoare, plus **ABX** pentru „aud o diferență?” la intensitatea implicită;
  - întrebări pe trei axe: claritatea vocii, naturalețea, preferința;
  - cel puțin tu și încă 3–5 ascultători, fiecare pereche de 2–3 ori.

### 6.2 Măsurători obiective (offline, pe același material)

| Ce | De ce | Prag [I, de fixat înainte de reglaj] |
|---|---|---|
| ΔLUFS integrat, ΔLRA, Δfactor de creastă | Fără efect de volum, fără compresie ascunsă | \|ΔLUFS\| ≤ 0,5 LU; \|ΔLRA\| ≤ 1 LU; creasta scade cu ≤ 1 dB |
| Statistica câștigurilor pe bandă (medie, p95, % timp peste 3 dB) | Cât lucrează de fapt | p95 ≤ 6 dB la 50% |
| Rata de modulare a câștigurilor (dB/s, p99) | Proxy pentru pompaj sau „respirație” | Sub limita de viteză aleasă (4.3) |
| Corelația L/R, raportul M/S | Imagine stereo intactă | Δ ≤ 0,02 |
| THD/IMD pe tonuri | Fără neliniaritate nouă | Sub −90 dB sub −3 dBFS |
| Test de nul la intensitate 0 | Ocolire curată | Diferență ≤ −120 dBFS |
| Diferența procesat(MP3) − procesat(FLAC) față de MP3 − FLAC | Ridică zgomotul de codec? | Crește cu ≤ 1 dB |
| Aceeași comparație după un codec SBC/AAC simulat | Interacțiunea cu Bluetooth | La fel |

### 6.3 Pe telefon

- **Telefoane:** cel puțin un telefon modest (nuclee Cortex-A55) și unul de top. Release, ecran stins.
- **Scenarii:** EQ oprit; EQ cu Auto; EQ cu Claritate. Fiecare singur, apoi **cu o descărcare Mornye activă**, apoi **cu Harmony Connect** spre PC, plus o trecere cu crossfade.
- **CPU per apel `process()`:** cronometrare în motor (medie, p99, maxim), raportată prin telemetria existentă, plus `simpleperf` pe proces.
- **Întreruperi:** `AudioTrack.getUnderrunCount()` pe sesiune de 2 h și jurnalul de diagnostic (doar contoare, fără titluri).
- **Memorie:** `dumpsys meminfo` (heap nativ), înainte și după.
- **Consum:** 3 × 30 min de redare cu ecranul stins pentru fiecare variantă, prin Android Studio Power Profiler sau `batterystats`. Media și dispersia, ca diferența să nu se piardă în zgomot.
- **Latența de reacție:** timpul de la apăsarea „original” la schimbarea audibilă (înregistrare externă sau marcaj în ieșire). Arată cât e de mare bufferul (5.3).

### 6.4 Criterii de acceptare sau de abandon

Le fixăm **înainte** de reglaj, ca să nu le potrivim după rezultate.

**Acceptăm** dacă toate sunt adevărate:

1. **Preferința:** la 50%, procesorul e preferit în **cel puțin 60%** din perechi la categoriile *voce* și *mix dens*, semnificativ (binomial, p < 0,05). În nicio categorie nu e semnificativ mai rău decât ocolirea. La *naturalețe* nu e sub ocolire cu mai mult de o treaptă pe scala de 5.
2. **Obiectiv:** pragurile din 6.2 sunt îndeplinite; zero clicuri sau pompaj raportate la pasajele liniștite.
3. **Telefon:**
   - CPU adăugat ≤ **2% dintr-un nucleu mare / 5% dintr-un nucleu mic** la 48 kHz stereo;
   - p99 per apel ≤ **10% din durata bufferului**;
   - **zero underrun-uri în plus** în 2 h, cu descărcare și Connect;
   - consum ≤ **+3% pe oră** față de EQ cu Auto;
   - memorie ≤ **1 MB** per motor.

**Abandonăm sau reducem** dacă:

- după două runde de reglaj, preferința rămâne în jur de 50% (nu aduce nimic auzibil) sau se înclină spre ocolire;
- **sau** zgomotul de codec ridicat pe MP3 nu se poate ține sub prag fără să anulăm *Recover*. Atunci rămâne doar un *Tame* dinamic (rezonanțe, stridență), adică direcția B extinsă;
- **sau** costul pe telefonul modest depășește bugetul chiar cu analiza decimată și hop-ul mărit.

---

## Verdict

**Aș alege C: procesorul propriu în Rust, pornind de la Clarity-ul de pe desktop, ca stagiu în `harmonyeq`, în locul Auto-ului când e pornit.** Motivele:

- Gullfoss nu se poate integra (2.1–2.2). Un acord cu Soundtheory e nesigur, lent și ne-ar face dependenți de un binar pe care nu-l putem repara.
- Auto-ul actual nu are cum să atingă obiectivul tău: are rezoluție de octavă, țintă fixă și nu știe ce e mascarea.
- Pentru C, munca grea (modelul de mascare, potrivirea de loudness, ajustarea filtrelor) e deja scrisă și testată în repository. Costul de calcul măsurat e mic, iar arhitectura motorului (fire, latență, netezire, limitator) îl primește fără schimbări în Kotlin.
- Riscul real e de *sunet*, nu de inginerie. De aceea primul pas e un test de ascultare, nu o integrare.

Independent de decizie, aș face separat corecturile din direcția B (3), în primul rând **saturatorul care distorsionează vârfurile cu EQ-ul oprit** (1.6).

**Primul experiment (o săptămână, fără să atingem playerul):**

1. **Ziua 0, zero cod:** ascultă Clarity în aplicația Windows, cu căștile tale, pe 10 piese pe care le știi bine (comutatorul Clarity din ecranul de egalizator, presetarea *Balanced*). E un semnal grosier, nu un test orb.
2. **Zilele 1–4:** un CLI Rust (`native/harmonyeq`, binar de test) cu modelul Clarity portat. Randează cele 20–25 de fragmente din 6.1 în variantele *ocolire*, *Auto actual* și *Claritate 50%*, egalizate la LUFS. Opțional, *Gullfoss* randat cu trialul, ca reper.
3. **Zilele 5–7:** test orb de preferință și ABX pe telefon, cu fișierele randate, plus bancul de CPU compilat pentru `aarch64-linux-android` și rulat pe telefon.

Dacă testul orb nu arată o preferință clară la voce și mix dens (6.4), ne oprim aici, cu o săptămână cheltuită și nimic de întreținut.

**Ce mi-ar schimba opinia:**

- Soundtheory oferă un SDK ARM pentru Android/iOS în condiții rezonabile. Atunci A devine o opțiune serioasă, poate ca funcție premium, iar procesorul propriu rămâne planul de rezervă.
- Testul orb nu arată preferință, sau arată că Gullfoss e mult peste Clarity la aceleași fragmente. Atunci fie doar B, fie o investiție mai mare în model (mascare temporală, analiză M/S), cu un nou test.
- Biblioteca e în mare parte cu pierderi, iar *Recover* scoate zgomot de codec. Atunci un *Tame* dinamic simplu ar aduce mare parte din beneficiu cu mult mai puțin risc.
- Măsurătorile pe telefonul modest arată costuri mult peste estimarea de 3–6× față de server. Atunci analiza decimată și hop-ul mai mare devin obligatorii, iar profilul offline (4.6) devine prioritar.
