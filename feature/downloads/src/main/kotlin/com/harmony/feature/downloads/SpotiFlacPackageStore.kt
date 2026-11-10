package com.harmony.feature.downloads

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Remembers the last package accepted for each provider (SharedPreferences in the app). */
internal interface AcceptedPackages {
    fun get(providerId: String): ProviderPackage?
    fun put(accepted: ProviderPackage)
}

/** The package a provider runs on, where its verified bytes are, and what was turned away on the way. */
internal class ResolvedPackage(val accepted: ProviderPackage, val file: File, val notes: List<String>)

/**
 * The verified provider packages on this phone, by content hash.
 *
 * A package is downloaded once and kept under its SHA-256, so a registry
 * update that changes one provider fetches one package, and a start with an
 * unchanged registry fetches none. It is also what makes a refused update
 * safe: the last accepted package is still here to run.
 *
 * [resolve] picks the package for one provider, in this order:
 *  1. the registry's, unless it is older than the last one accepted (a
 *     downgrade), fails its hash, or asks for more than Harmony has reviewed
 *     ([SpotiFlacProviderGate]);
 *  2. the last accepted one, from the store;
 *  3. the package pinned in this build (by commit and hash).
 */
internal class SpotiFlacPackageStore(
    private val root: File,
    private val accepted: AcceptedPackages,
    private val pinned: List<ProviderPackage>,
) {
    private fun file(item: ProviderPackage) = File(root, "${item.id}-${item.sha256.lowercase(Locale.ROOT)}.sflx")

    fun resolve(candidate: ProviderPackage, download: (ProviderPackage, File) -> Unit): ResolvedPackage {
        val notes = mutableListOf<String>()
        val last = accepted.get(candidate.id)
        val pin = pinned.firstOrNull { it.id == candidate.id }

        val older = last != null && SpotiFlacProviderGate.compareVersions(candidate.version, last.version) < 0
        if (older) {
            notes += "${candidate.id}: registry offers ${candidate.version}, older than the accepted ${last!!.version}; kept ${last.version}"
        } else {
            val problem = obtain(candidate, trusted = candidate.sha256.equals(pin?.sha256, ignoreCase = true), download)
            if (problem == null) {
                accepted.put(candidate)
                return ResolvedPackage(candidate, file(candidate), notes)
            }
            notes += "${candidate.id} ${candidate.version} not installed: $problem"
        }
        if (last != null && last.sha256 != candidate.sha256) {
            // Already accepted once: only its bytes need to still be the same.
            val stored = file(last)
            if (stored.isFile && sha256(stored).equals(last.sha256, ignoreCase = true)) {
                return ResolvedPackage(last, stored, notes)
            }
            notes += "${last.id} ${last.version} (last accepted) is no longer in the store"
        }
        if (pin != null && pin.sha256 != candidate.sha256) {
            val problem = obtain(pin, trusted = true, download)
            if (problem == null) return ResolvedPackage(pin, file(pin), notes)
            notes += "${pin.id} ${pin.version} (pinned) not installed: $problem"
        }
        throw SpotiFlacException(
            "The ${candidate.displayName} provider could not be installed.",
            "provider_install",
            technicalDetails = notes.joinToString("\n"),
        )
    }

    /**
     * The package last accepted for [providerId], when its bytes are still in
     * the store and still the ones that were accepted: usable right now, with
     * no request to anyone.
     */
    fun lastAccepted(providerId: String): ResolvedPackage? {
        val last = accepted.get(providerId) ?: return null
        val stored = file(last)
        return if (stored.isFile && sha256(stored).equals(last.sha256, ignoreCase = true)) {
            ResolvedPackage(last, stored, emptyList())
        } else null
    }

    /** Null when the package is in the store, verified; otherwise why it is not. */
    private fun obtain(item: ProviderPackage, trusted: Boolean, download: (ProviderPackage, File) -> Unit): String? {
        check(root.mkdirs() || root.isDirectory) { "Cannot create the provider package store" }
        val stored = file(item)
        if (!stored.isFile || !sha256(stored).equals(item.sha256, ignoreCase = true)) {
            val partial = File(root, "${stored.name}.part")
            try {
                partial.delete()
                runCatching { download(item, partial) }.onFailure {
                    return "download failed (${it.message ?: it.javaClass.simpleName})"
                }
                val actual = sha256(partial)
                if (!actual.equals(item.sha256, ignoreCase = true)) {
                    return "SHA-256 mismatch (expected ${item.sha256}, got $actual)"
                }
                if (!trusted) SpotiFlacProviderGate.refusal(partial, item)?.let { return it }
                stored.delete()
                if (!partial.renameTo(stored)) partial.copyTo(stored, overwrite = true)
            } finally {
                partial.delete()
            }
            return null
        }
        // Stored bytes were checked when they were stored; a package from the
        // registry is checked again, in case this build reviews less than the last.
        return if (trusted) null else SpotiFlacProviderGate.refusal(stored, item)
    }

    /** Drops stored packages that are neither in [keep] nor the last accepted for their provider. */
    fun prune(keep: Collection<ProviderPackage>) {
        val wanted = (keep + keep.mapNotNull { accepted.get(it.id) }).map { file(it).name }.toSet()
        root.listFiles()?.forEach { if (it.isFile && it.name !in wanted) it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}

/** The packages the runtime is to run, and what was turned away while choosing them. */
internal class ProviderSet(val packages: List<ResolvedPackage>, val notes: List<String>) {
    /** Changes whenever any installed package's bytes change. */
    val fingerprint: String
        get() = RegistrySnapshot(packages.map { it.accepted }, "", "").fingerprint
}

/**
 * How the providers are chosen when the engine starts.
 *
 * A download must not wait for the registry when the phone already has
 * providers that work: [cached] is the last accepted package of each
 * provider, straight from the store. The registry is then checked off to the
 * side ([fromRegistry]); a different set is only stored and noted, and the
 * engine switches to it between downloads, never under one.
 */
internal class SpotiFlacProviderStartup(
    private val store: SpotiFlacPackageStore,
    private val priority: List<String>,
) {
    /** The providers usable now without the network; empty on a first run. */
    fun cached(): ProviderSet = ProviderSet(priority.mapNotNull(store::lastAccepted), emptyList())

    /**
     * The set the registry leads to: each provider's package verified and
     * reviewed, or its last accepted or pinned one. A provider that cannot be
     * had at all is left out, with the reason in the notes.
     */
    fun fromRegistry(registry: RegistrySnapshot, download: (ProviderPackage, File) -> Unit): ProviderSet {
        val notes = mutableListOf<String>()
        val packages = registry.packages.mapNotNull { candidate ->
            runCatching { store.resolve(candidate, download) }
                .onSuccess { notes += it.notes }
                .onFailure { notes += (it as? SpotiFlacException)?.technicalDetails ?: it.message.orEmpty() }
                .getOrNull()
        }
        return ProviderSet(packages, notes)
    }
}

/**
 * Where a runtime keeps its extensions and their data: one folder per set of
 * packages (`<fingerprint>/extensions`, `<fingerprint>/data`), so a newer
 * package is never shadowed by an older extracted one.
 *
 * The data folder holds one subfolder per provider (its settings and its
 * signed session). A new set used to start with an empty data folder, which
 * signed every provider out whenever any one of them was updated.
 * [carryOver] copies the data of the providers whose package did not change;
 * the previous folder is left as it was, to go back to.
 */
internal object SpotiFlacRuntimeLayout {
    /** Copies `<previousData>/<id>` to `<newData>/<id>` for each of [unchangedIds] the new folder does not have yet. */
    fun carryOver(previousData: File, newData: File, unchangedIds: Collection<String>): List<String> {
        if (!previousData.isDirectory || previousData.canonicalPath == newData.canonicalPath) return emptyList()
        return unchangedIds.filter { id ->
            val from = File(previousData, id)
            val to = File(newData, id)
            // An id is a plain folder name: nothing that could point outside the data folder.
            if (id.isBlank() || id.any { it == '/' || it == '\\' } || id == "." || id == "..") return@filter false
            if (!from.isDirectory || to.exists()) return@filter false
            runCatching {
                newData.mkdirs()
                val staged = File(newData, ".$id.carry")
                staged.deleteRecursively()
                check(from.copyRecursively(staged, overwrite = true))
                check(staged.renameTo(to))
                true
            }.getOrElse { File(newData, ".$id.carry").deleteRecursively(); false }
        }
    }

    /** Removes the runtime folders of older sets, keeping [keep] (the running one and the one before it). */
    fun prune(base: File, keep: Set<String>) {
        base.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.matches(Regex("[0-9a-f]{16}")) && child.name !in keep) {
                runCatching { child.deleteRecursively() }
            }
        }
    }
}

/**
 * The registry text, kept for [maxAgeMs] so a start within that time asks the
 * network nothing; a stale copy still beats the bundled fallback when the
 * registry cannot be reached.
 */
internal class SpotiFlacRegistryCache(
    private val file: File,
    private val maxAgeMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The cached text when it is younger than [maxAgeMs]. */
    fun fresh(): String? = read()?.takeIf { now() - file.lastModified() in 0 until maxAgeMs }

    /** The cached text, however old. */
    fun read(): String? = runCatching { file.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readText() }.getOrNull()

    fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, "${file.name}.part")
            partial.writeText(text)
            if (!partial.renameTo(file)) {
                partial.copyTo(file, overwrite = true)
                partial.delete()
            }
            file.setLastModified(now())
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
