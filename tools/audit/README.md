# Verificări pentru Harmony 1.0.0

Testele obișnuite sunt în modulele `src/test`. Cu SDK-ul și Gradle configurate:

```powershell
cd D:\Harmony
.\gradlew.bat testDebugUnitTest :core:common:test :domain:library:test :domain:playback:test -PharmonyAbi=arm64-v8a
python tools\audit\verify_database.py
```

`verify_database.py` folosește biblioteca standard Python și execută SQL-ul DAO real pe schema SQLite din proiect. Nu execută generatorul Room.

`run_isolated.py` este verificarea suplimentară folosită când buildul Android nu este disponibil. Necesită Java 17, Kotlin 2.1.0 și JAR-urile dependințelor declarate în proiect, inclusiv Android 35, Compose, Media3 1.5.0, Room, Hilt, DataStore, WorkManager, Kotlin coroutines, JUnit și `org.json`. Pentru AAR-uri se folosește `classes.jar`; biblioteca Go inclusă se extrage tot din AAR. Directorul trebuie să conțină o singură versiune a fiecărei biblioteci și un singur `android.jar`.

```powershell
python tools\audit\run_isolated.py --kotlin-home D:\Tools\kotlinc --dependencies D:\Tools\harmony-audit-jars
```

Compilează sursele Kotlin/Compose împreună, cu API-urile reale; numai două ID-uri `R.drawable` sunt înlocuite pentru că sunt generate de Android build tools. Această verificare nu validează separarea classpath-ului între module, generarea KSP/Hilt/Room, manifestul rezultat, resursele sau împachetarea APK-ului.

Cele două directoare `*-harness` conțin exclusiv teste pentru JVM. Înlocuiesc câteva servicii Android cu implementări în memorie; nu intră în aplicație. Testele pentru editor folosesc ViewModel/coroutines reale și un repository simulat. Testele Spotify execută clientul real cu răspunsuri HTTP controlate și preferințe în memorie. Crossfade folosește interfețele Media3 reale și un player simulat; testele ReplayGain procesează PCM real. Ele nu înlocuiesc ascultarea și verificarea pe telefon.
