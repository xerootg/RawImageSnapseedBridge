package com.raw2dng.rrc

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.raw2dng.MainActivity
import com.raw2dng.source.SourceRegistry
import kotlinx.coroutines.launch

/**
 * Receives `raw2dng://auth-callback?code=…&state=…` from the browser,
 * finishes the pairing and returns to the Convert tab with the cloud source
 * selected. Deliberately has no UI of its own.
 */
class PairingCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) { finish(); return }
        lifecycleScope.launch {
            val message = try {
                val cfg = RrcPairing.complete(this@PairingCallbackActivity, data)
                SourceRegistry.setActive(this@PairingCallbackActivity, RrcSource.idFor(cfg))
                "Paired with ${cfg.bucket}"
            } catch (e: Exception) {
                "Pairing failed: ${e.message}"
            }
            Toast.makeText(this@PairingCallbackActivity, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@PairingCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }
}
