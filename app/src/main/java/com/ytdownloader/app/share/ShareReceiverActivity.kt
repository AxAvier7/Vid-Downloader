package com.ytdownloader.app.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ytdownloader.app.R
import com.ytdownloader.app.ui.OptionsActivity
import com.ytdownloader.app.util.UrlParser

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null && isSupportedUrl(url)) {
                startActivity(Intent(this, OptionsActivity::class.java).putExtra(OptionsActivity.EXTRA_URL, url))
            } else {
                Toast.makeText(this, R.string.parse_error, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex("https?://\\S+").find(text) ?: return null
        return match.value.trimEnd('.', ',', ';', ')', ']', '>')
    }

    private fun isSupportedUrl(url: String): Boolean {
        val parsed = UrlParser.parse(url)
        return parsed != null && (parsed.videoId != null || parsed.playlistId != null || parsed.isYoutube)
    }
}