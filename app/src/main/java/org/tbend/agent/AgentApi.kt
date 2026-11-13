// Written for Thoughts. License is GPLv3.

package org.tbend.agent

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The agent API (see AGENT-API.md). The app registers its commands in Application.onCreate with
 * Agent.register(...) and declares in its manifest AgentProvider (authority "${applicationId}.agent",
 * exported), AgentActivity (exported, translucent, android:permission="android.permission.DUMP") and
 * its alias AgentAppActivity (exported, no permission) for other apps.
 */
class AgentCommand(
    val name: String,
    /** e.g. "query or id, start_s?" */
    val args: String,
    val description: String,
    /** true: needs AgentActivity (opens a screen or starts playback from the background). */
    val needsActivity: Boolean = false,
    /** false: runs on the caller's thread (network or disk work); it must then post UI/player work itself. */
    val mainThread: Boolean = true,
    /** Returns a JSONObject/JSONArray/String/Number/Boolean or null. */
    val run: (Context, JSONObject) -> Any?,
)

class AgentError(message: String) : Exception(message)

object Agent {
    private val commands = linkedMapOf<String, AgentCommand>()
    private val main = Handler(Looper.getMainLooper())

    fun register(vararg list: AgentCommand) { list.forEach { commands[it.name] = it } }

    fun command(name: String): AgentCommand? = commands[name]

    fun help(context: Context): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("app", context.applicationInfo.loadLabel(context.packageManager).toString())
            .put("package", context.packageName).put("version", info.versionName)
            .put("content", "adb shell content call --uri content://${context.packageName}.agent --method <command> --arg '<json>'")
            .put("activity", "adb shell am start -n ${context.packageName}/org.tbend.agent.AgentActivity --es command <command> --es arg '<json>'")
            .put("activity_from_apps", "${context.packageName}/org.tbend.agent.AgentAppActivity, started for a result or with shared identity")
            .put("commands", JSONArray(commands.values.map {
                JSONObject().put("name", it.name).put("args", it.args).put("description", it.description)
                    .put("needs_activity", it.needsActivity)
            }))
    }

    /** Runs a command on the main thread and waits for it (at most 10 s). */
    fun run(context: Context, name: String, arg: JSONObject): Any? {
        if (name == "help") return help(context)
        val command = commands[name] ?: throw AgentError("unknown command '$name'; call help for the list")
        if (!command.mainThread || Looper.myLooper() == Looper.getMainLooper()) return command.run(context, arg)
        var result: Any? = null
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        main.post {
            try { result = command.run(context, arg) } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        if (!done.await(10, TimeUnit.SECONDS)) throw AgentError("'$name' did not finish within 10 s")
        failure?.let { throw it }
        return result
    }

    /** Runs block on the main thread and waits for its result (for commands that run off it). */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw AgentError("the app did not answer within 10 s")
        return result!!.getOrThrow()
    }

    fun parse(arg: String?): JSONObject = when {
        arg.isNullOrBlank() -> JSONObject()
        arg.trim().startsWith("{") -> JSONObject(arg)
        else -> JSONObject().put("query", arg)  // a bare word: the common case of a search/title
    }

    /** Matching for titles: lowercase without accents. */
    fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Picks an item by "id" or "query" (every word in the title); throws a helpful error. */
    fun <T> find(arg: JSONObject, items: List<T>, id: (T) -> String, title: (T) -> String): T {
        arg.optString("id").takeIf { it.isNotEmpty() }?.let { wanted -> items.firstOrNull { id(it) == wanted }?.let { return it } }
        val words = fold(arg.optString("query", arg.optString("title"))).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) throw AgentError("give an id or a query (words of the title); use list")
        val matches = items.filter { item -> fold(title(item)).let { t -> words.all { it in t } } }
        return matches.minByOrNull { if (fold(title(it)) == words.joinToString(" ")) 0 else 1 }
            ?: throw AgentError("nothing called '${words.joinToString(" ")}'; use list")
    }
}

/**
 * Who may run agent commands, without Android classes so that the rule can be tested: root, adb
 * (uid shell), the app itself, apps signed with the app's key, and packages allowed from adb, each
 * pinned to the signing certificate it had when it was allowed.
 */
object AgentCallerRule {
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    /** A package of the calling uid and the SHA-256 digests (hex) of its signing certificates, the current one first. */
    class CallerPackage(val name: String, val certificates: List<String>)

    sealed class Decision {
        object Allow : Decision()

        /**
         * Allowed, and [packageName] is from now on pinned to [certificate]: its entry had no
         * certificate yet (allowed by an older version, or before the app could be seen).
         */
        data class AllowAndPin(val packageName: String, val certificate: String) : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /**
     * [allowed] maps an allowed package to the certificate it is pinned to (null: not pinned yet).
     * [sameSignature] and [packages] are asked only for a caller that is not adb or the app.
     */
    fun decide(
        callerUid: Int,
        ownUid: Int,
        sameSignature: () -> Boolean,
        packages: () -> List<CallerPackage>,
        allowed: Map<String, String?>,
    ): Decision {
        if (callerUid < 0) return Decision.Deny("not allowed: the caller is not known")
        if (callerUid == ROOT_UID || callerUid == SHELL_UID || callerUid == ownUid) return Decision.Allow
        if (sameSignature()) return Decision.Allow
        var otherCertificate = false
        for (caller in packages()) {
            if (caller.name !in allowed) continue
            val pinned = allowed[caller.name]
            if (pinned == null) {
                val current = caller.certificates.firstOrNull() ?: continue
                return Decision.AllowAndPin(caller.name, current)
            }
            if (pinned in caller.certificates) return Decision.Allow
            otherCertificate = true
        }
        return Decision.Deny(
            if (otherCertificate) {
                "not allowed: this app is not signed with the certificate that was allowed; allow it again from adb"
            } else {
                "not allowed: allow this app from adb with --method allow --arg <package>"
            },
        )
    }

    /**
     * A start of AgentActivity. Android names the caller only when the caller shares its identity
     * or starts the activity for a result; [callerUid] is null otherwise. An unnamed caller is
     * accepted only at the entry Android itself guards with the manifest permission (adb holds it,
     * ordinary apps cannot), never at the alias other apps start.
     */
    fun decideStart(callerUid: Int?, permissionGuardedEntry: Boolean, identified: (Int) -> Decision): Decision = when {
        callerUid != null -> identified(callerUid)
        permissionGuardedEntry -> Decision.Allow
        else -> Decision.Deny("not allowed: start AgentAppActivity for a result, or with shared identity")
    }

    /** "package" or "package=<sha256 hex>" entries, as stored. */
    fun parseAllowed(entries: Set<String>): Map<String, String?> = entries.associate { entry ->
        entry.substringBefore('=') to entry.substringAfter('=', "").takeIf(::isCertificateDigest)
    }.filterKeys(::isPackageName)

    fun formatAllowed(allowed: Map<String, String?>): Set<String> =
        allowed.map { (name, certificate) -> if (certificate == null) name else "$name=$certificate" }.toSet()

    fun isPackageName(value: String): Boolean = value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

    fun isCertificateDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

    /** A certificate digest as adb or apksigner print it (upper case, colons) in the stored form, or null. */
    fun normalizeCertificateDigest(value: String): String? =
        value.trim().replace(":", "").lowercase().takeIf(::isCertificateDigest)
}

/** The caller rule ([AgentCallerRule]) with this device's packages and the stored allow list. */
object AgentCallers {
    private const val PREFERENCES = "agent_api"
    private const val KEY_ALLOWED = "allowed"

    /** Throws [AgentError] unless the app with [uid] may run agent commands. */
    fun check(context: Context, uid: Int) {
        when (val decision = decide(context, uid)) {
            is AgentCallerRule.Decision.Allow, is AgentCallerRule.Decision.AllowAndPin -> Unit
            is AgentCallerRule.Decision.Deny -> throw AgentError(decision.reason)
        }
    }

    private fun decide(context: Context, uid: Int): AgentCallerRule.Decision {
        val packageManager = context.packageManager
        val allowed = allowed(context)
        val decision = AgentCallerRule.decide(
            callerUid = uid,
            ownUid = Process.myUid(),
            sameSignature = { packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH },
            packages = {
                packageManager.getPackagesForUid(uid).orEmpty().map { AgentCallerRule.CallerPackage(it, certificates(packageManager, it)) }
            },
            allowed = allowed,
        )
        if (decision is AgentCallerRule.Decision.AllowAndPin) {
            store(context, allowed + (decision.packageName to decision.certificate))
        }
        return decision
    }

    /**
     * Throws [AgentError] unless this start of AgentActivity may run a command: a caller Android
     * names passes [check]; an unnamed one is accepted only through the permission-guarded entry.
     */
    fun checkStart(activity: Activity) {
        val decision = AgentCallerRule.decideStart(
            callerUid = launcherUid(activity),
            permissionGuardedEntry = activity.componentName.className == AgentActivity::class.java.name,
        ) { uid -> decide(activity, uid) }
        if (decision is AgentCallerRule.Decision.Deny) throw AgentError(decision.reason)
    }

    /** The uid that started [activity] when Android tells: shared identity (Android 14), or a start for a result. */
    private fun launcherUid(activity: Activity): Int? {
        if (Build.VERSION.SDK_INT >= 34) activity.launchedFromUid.takeIf { it >= 0 }?.let { return it }
        val caller = activity.callingPackage ?: return null
        return runCatching { activity.packageManager.getApplicationInfo(caller, 0).uid }.getOrNull()
    }

    /** adb's allow and deny: changes the list and returns it. [arg]: a package, or {"package":…,"sha256":…}. */
    fun change(context: Context, allow: Boolean, arg: String?): JSONArray {
        val text = arg.orEmpty().trim()
        val json = if (text.startsWith("{")) JSONObject(text) else null
        val name = (json?.optString("package") ?: text).trim()
        if (!AgentCallerRule.isPackageName(name)) throw AgentError("give a package name, e.g. --arg com.termux")
        val allowed = allowed(context).toMutableMap()
        if (allow) {
            val given = json?.optString("sha256").orEmpty()
            val certificate = if (given.isEmpty()) {
                // Pinned now when the app is installed and visible to this app, else at its first call.
                certificates(context.packageManager, name).firstOrNull()
            } else {
                AgentCallerRule.normalizeCertificateDigest(given)
                    ?: throw AgentError("sha256 must be the SHA-256 digest of the app's signing certificate (64 hex digits)")
            }
            allowed[name] = certificate
        } else {
            allowed -= name
        }
        store(context, allowed)
        return JSONArray(allowed.toSortedMap().map { (allowedName, certificate) ->
            JSONObject().put("package", allowedName).put("sha256", certificate ?: JSONObject.NULL)
                .put("pinned", if (certificate != null) "yes" else "at its first call")
        })
    }

    private fun allowed(context: Context): Map<String, String?> = AgentCallerRule.parseAllowed(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet(KEY_ALLOWED, emptySet())!!,
    )

    private fun store(context: Context, allowed: Map<String, String?>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ALLOWED, AgentCallerRule.formatAllowed(allowed)).apply()
    }

    /** SHA-256 digests of [packageName]'s signing certificates, the current one first; empty when it cannot be seen. */
    private fun certificates(packageManager: PackageManager, packageName: String): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching emptyList()
            // The history lists the certificates an app rotated through, oldest first.
            if (signing.hasMultipleSigners()) signing.apkContentsSigners.orEmpty().map { digest(it.toByteArray()) }
            else signing.signingCertificateHistory.orEmpty().map { digest(it.toByteArray()) }.reversed()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().map { digest(it.toByteArray()) }
        }
    }.getOrDefault(emptyList())

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** content://<applicationId>.agent — call(method = command, arg = JSON). */
class AgentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context!!
        val out = Bundle()
        try {
            if (method == "allow" || method == "deny") {
                if (Binder.getCallingUid() !in setOf(Process.ROOT_UID, Process.SHELL_UID)) throw AgentError("only adb can change who may call")
                val allowed = AgentCallers.change(context, allow = method == "allow", arg = arg)
                return out.apply { putBoolean("ok", true); putString("result", allowed.toString()) }
            }
            AgentCallers.check(context, Binder.getCallingUid())
            // A call can arrive while the app is still starting (providers answer before Application.onCreate
            // has finished, where the commands are registered): one round on the main thread waits for it.
            Agent.onMain { }
            val result = Agent.run(context, method, Agent.parse(arg))
            out.putBoolean("ok", true)
            out.putString("result", when (result) { null -> "{}"; is String -> JSONObject.quote(result); else -> result.toString() })
        } catch (e: Throwable) {
            out.putBoolean("ok", false)
            out.putString("error", e.message ?: e.toString())
        }
        return out
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * am start -n <pkg>/org.tbend.agent.AgentActivity --es command <name> --es arg '<json>': runs it in the foreground.
 * The manifest guards this entry with android.permission.DUMP, which adb holds and ordinary apps cannot.
 * Other apps start the alias org.tbend.agent.AgentAppActivity, where the caller must be one Android
 * names (a start for a result, or with shared identity) and one [AgentCallers.check] allows.
 */
class AgentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("command") ?: "help"
        try {
            AgentCallers.checkStart(this)
            Agent.run(this, name, Agent.parse(intent.getStringExtra("arg")))
        } catch (e: Throwable) {
            android.util.Log.w("AgentApi", "command $name failed: ${e.message}")
        }
        finish()
    }
}
