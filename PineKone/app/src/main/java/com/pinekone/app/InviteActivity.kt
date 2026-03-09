package com.pinekone.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.pinekone.app.databinding.ActivityInviteBinding
import com.pinekone.app.protocol.toHexString
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch

class InviteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInviteBinding
    private var invitePayload: String = ""
    private var codeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.buttonScan.setOnClickListener {
            startActivity(Intent(this, JoinActivity::class.java))
        }
        binding.buttonViewCode.setOnClickListener {
            codeVisible = !codeVisible
            renderCodeVisibility()
        }
        binding.buttonCopyInvite.setOnClickListener {
            if (invitePayload.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("PineKone invite", invitePayload))
            Snackbar.make(binding.root, getString(R.string.invite_code_copied), Snackbar.LENGTH_SHORT).show()
        }

        val engine = (application as PineKoneApp).engine
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                engine.identity.collect { identity ->
                    val fingerprint = identity.fingerprint.joinToString("") { b -> "%02x".format(b) }
                    val encodedName = Uri.encode(identity.displayName)
                    val attestationRef = UUID.nameUUIDFromBytes(
                        "${identity.nodeId}:${Instant.now().epochSecond / 3600}".encodeToByteArray()
                    ).toString()
                    val aliasId = UUID.nameUUIDFromBytes(identity.fingerprint).toString().take(12)
                    val epoch = Instant.now().epochSecond / 86_400
                    val invitePayload = buildString {
                        append("pk://relay/")
                        append(identity.nodeId)
                        append("?fp=")
                        append(fingerprint)
                        append("&name=")
                        append(encodedName)
                        append("&pk=")
                        append(identity.publicKey.toHexString())
                        append("&inviter=")
                        append(identity.nodeId)
                        append("&att=")
                        append(attestationRef)
                        append("&alias=")
                        append(aliasId)
                        append("&epoch=")
                        append(epoch)
                        append("&role=relay")
                    }
                    this@InviteActivity.invitePayload = invitePayload
                    binding.inviteCodeText.text = invitePayload
                    renderQr(invitePayload)
                    renderCodeVisibility()
                }
            }
        }
    }

    private fun renderCodeVisibility() {
        binding.inviteCodeText.visibility = if (codeVisible) android.view.View.VISIBLE else android.view.View.GONE
        binding.buttonViewCode.text = if (codeVisible) {
            getString(R.string.invite_hide_code)
        } else {
            getString(R.string.invite_view_code)
        }
    }

    private fun renderQr(content: String) {
        val writer = QRCodeWriter()
        try {
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            binding.qrImage.setImageBitmap(bitmap)
        } catch (ex: WriterException) {
            binding.inviteHint.text = getString(R.string.invite_hint)
            binding.inviteCodeText.text = ""
        }
    }

    companion object {
        private const val QR_SIZE = 512
    }
}
