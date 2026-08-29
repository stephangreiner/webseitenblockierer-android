package com.webseitenblockierer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.webseitenblockierer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: BlockStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BlockStore(this)

        binding.addButton.setOnClickListener {
            val value = binding.siteInput.text.toString()
            if (value.isBlank()) return@setOnClickListener
            if (store.addBlockedSite(value)) {
                binding.siteInput.text.clear()
                renderList()
            } else {
                Toast.makeText(this, R.string.already_blocked, Toast.LENGTH_SHORT).show()
            }
        }

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
        renderPermissionStatus()
    }

    private fun renderPermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.overlayStatus.text =
            getString(if (overlayOk) R.string.status_granted else R.string.status_missing)

        val accessibilityOk = isAccessibilityEnabled()
        binding.accessibilityStatus.text =
            getString(if (accessibilityOk) R.string.status_granted else R.string.status_missing)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${BlockerAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun renderList() {
        val container = binding.blockedListContainer
        container.removeAllViews()
        for (site in store.getBlockedSites()) {
            container.addView(buildRow(site))
        }
    }

    private fun buildRow(site: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(this).apply {
            text = site
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        val remove = Button(this).apply {
            text = getString(R.string.remove_button)
            setOnClickListener {
                store.removeBlockedSite(site)
                renderList()
            }
        }
        row.addView(remove)
        return row
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
