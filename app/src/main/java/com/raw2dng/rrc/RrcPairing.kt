package com.raw2dng.rrc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.raw2dng.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Pair with RapidRawCloud" - zero-typing setup against the RapidRawCloud
 * pairing service (RapidRawCloud `docs/CLOUD_SETUP.md` §6, `pairing/`).
 * Same protocol as RapidRAW's own `sync/pairing.rs`, with this app's redirect:
 *
 * 1. `GET <service>/api/pairing-info` -> OIDC issuer + public client id.
 * 2. `GET <issuer>/.well-known/openid-configuration` -> authorize/token endpoints.
 * 3. Browser (Custom Tab) at the authorize endpoint with PKCE S256 and
 *    `redirect_uri=raw2dng://auth-callback`.
 * 4. [PairingCallbackActivity] receives the redirect and calls [complete].
 * 5. Code -> token (public client, no secret), `GET <service>/api/config`
 *    with the bearer -> `{sync:{endpoint,bucket,region,...}, credentials:{...}}`,
 *    stored through [RrcConfig] (secret in the Keystore-backed prefs).
 *
 * The pairing service must advertise this app's redirect URI (it lists
 * `redirectUris`); otherwise the identity provider rejects the request and
 * the user sees the provider's error page.
 */
object RrcPairing {
    private const val TAG = "RrcPairing"
    const val REDIRECT_URI = "raw2dng://auth-callback"
    private const val PREFS = "raw2dng_rrc_pairing"
    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    class PairingException(message: String) : Exception(message)

    data class Pending(val state: String, val verifier: String, val tokenEndpoint: String, val clientId: String, val configUrl: String, val service: String, val startedAt: Long)

    fun normalizeServiceUrl(input: String): String {
        val t = input.trim().trimEnd('/')
        if (t.isEmpty()) throw PairingException("Enter the pairing service URL (e.g. rrc.example.com)")
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        if (URL(url).host.isNullOrEmpty()) throw PairingException("Invalid URL: no host")
        return url
    }

    fun pkcePair(): Pair<String, String> {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(bytes)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return verifier to challenge
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun buildAuthorizeUrl(authorizationEndpoint: String, clientId: String, state: String, challenge: String): Uri =
        Uri.parse(authorizationEndpoint).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid profile email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

    /** Parse the redirect; surfaces the provider's `error`/`error_description`. */
    fun parseCallback(uri: Uri): Pair<String, String> {
        uri.getQueryParameter("error")?.let { err ->
            throw PairingException(uri.getQueryParameter("error_description") ?: err)
        }
        val code = uri.getQueryParameter("code") ?: throw PairingException("Callback without a code")
        val state = uri.getQueryParameter("state") ?: throw PairingException("Callback without a state")
        return code to state
    }

    // --- pending flow (survives the browser round trip) ----------------------

    private fun savePending(context: Context, p: Pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", p.state).putString("verifier", p.verifier).putString("token", p.tokenEndpoint)
            .putString("client", p.clientId).putString("config", p.configUrl).putString("service", p.service)
            .putLong("started", p.startedAt).apply()
    }

    private fun takePending(context: Context): Pending? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = p.getString("state", null) ?: return null
        val pending = Pending(
            state, p.getString("verifier", "")!!, p.getString("token", "")!!, p.getString("client", "")!!,
            p.getString("config", "")!!, p.getString("service", "")!!, p.getLong("started", 0)
        )
        p.edit().clear().apply()
        return pending
    }

    // --- flow ---------------------------------------------------------------

    /** Step 1-3: discover and open the browser. Throws [PairingException] on any failure. */
    suspend fun start(context: Context, serviceInput: String) {
        val service = normalizeServiceUrl(serviceInput)
        val (authorizeUrl, pending) = withContext(Dispatchers.IO) {
            val info = getJson("$service/api/pairing-info", null)
            val issuer = info.optString("issuer").trimEnd('/')
            val clientId = info.optString("clientId")
            if (issuer.isEmpty() || clientId.isEmpty()) throw PairingException("Pairing service did not return an issuer and client id")
            val advertised = info.optJSONArray("redirectUris")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: listOfNotNull(info.optString("redirectUri").takeIf { it.isNotEmpty() })
            if (advertised.isNotEmpty() && REDIRECT_URI !in advertised) {
                Log.w(TAG, "Pairing service does not advertise $REDIRECT_URI (has $advertised); the provider may reject the login")
            }
            val configEndpoint = info.optString("configEndpoint", "/api/config")
            val discovery = getJson("$issuer/.well-known/openid-configuration", null)
            val authorizationEndpoint = discovery.optString("authorization_endpoint")
            val tokenEndpoint = discovery.optString("token_endpoint")
            if (authorizationEndpoint.isEmpty() || tokenEndpoint.isEmpty()) throw PairingException("OIDC discovery document is incomplete")
            val (verifier, challenge) = pkcePair()
            val state = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val configUrl = if (configEndpoint.startsWith("http")) configEndpoint else service + configEndpoint
            buildAuthorizeUrl(authorizationEndpoint, clientId, state, challenge) to
                Pending(state, verifier, tokenEndpoint, clientId, configUrl, service, System.currentTimeMillis())
        }
        savePending(context, pending)
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, authorizeUrl)
        } catch (e: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, authorizeUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Step 4-5: exchange the code and store the config. Returns the paired config. */
    suspend fun complete(context: Context, callback: Uri): RrcConfig = withContext(Dispatchers.IO) {
        val pending = takePending(context) ?: throw PairingException("No pairing in progress; start again from Settings")
        if (System.currentTimeMillis() - pending.startedAt > PENDING_TTL_MS) throw PairingException("Pairing timed out; start again")
        val (code, state) = parseCallback(callback)
        if (!constantTimeEquals(state, pending.state)) throw PairingException("State mismatch; start again")

        val form = listOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
            "client_id" to pending.clientId, "code_verifier" to pending.verifier
        ).joinToString("&") { (k, v) -> "${Uri.encode(k)}=${Uri.encode(v)}" }
        val token = postForm(pending.tokenEndpoint, form)
        val accessToken = token.optString("access_token")
        if (accessToken.isEmpty()) throw PairingException("Token endpoint returned no access token")

        val doc = getJson(pending.configUrl, accessToken)
        val sync = doc.optJSONObject("sync") ?: throw PairingException("Config document has no sync section")
        val creds = doc.optJSONObject("credentials") ?: throw PairingException("Config document has no credentials")
        val cfg = RrcConfig(
            endpoint = sync.optString("endpoint"), bucket = sync.optString("bucket"),
            region = sync.optString("region", "garage"), forcePathStyle = sync.optBoolean("forcePathStyle", true),
            pairingService = pending.service, username = userinfoName(token)
        )
        if (cfg.endpoint.isEmpty() || cfg.bucket.isEmpty()) throw PairingException("Config document is missing the endpoint or bucket")
        val s3 = SigV4.Credentials(creds.optString("accessKeyId"), creds.optString("secretAccessKey"))
        if (s3.accessKey.isEmpty() || s3.secretKey.isEmpty()) throw PairingException("Config document is missing the S3 credentials")

        RrcConfig.save(context, cfg, s3)
        SourceRegistry.register(RrcSource(context.applicationContext, cfg))
        Log.i(TAG, "Paired with ${cfg.bucket} at ${cfg.host}")
        cfg
    }

    fun unpair(context: Context) {
        RrcConfig.load(context)?.let { SourceRegistry.unregister(context, RrcSource.idFor(it)) }
        RrcConfig.clear(context)
    }

    /** preferred_username from the id token, if the provider sent one (unverified: display only). */
    private fun userinfoName(token: JSONObject): String? {
        val idToken = token.optString("id_token").takeIf { it.isNotEmpty() } ?: return null
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        return try {
            val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            claims.optString("preferred_username").ifEmpty { claims.optString("email") }.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    // --- HTTP ---------------------------------------------------------------

    private fun getJson(url: String, bearer: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (status !in 200..299) throw PairingException("$url -> HTTP $status ${body.take(300)}")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, form: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        try {
            conn.outputStream.use { it.write(form.toByteArray()) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (status !in 200..299) throw PairingException("Token exchange failed: HTTP $status ${body.take(300)}")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
