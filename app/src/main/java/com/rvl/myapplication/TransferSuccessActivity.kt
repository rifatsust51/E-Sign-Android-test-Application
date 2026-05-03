package com.rvl.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class TransferSuccessActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_success)

        val amount        = intent.getStringExtra("amount")          ?: "0"
        val channel       = intent.getStringExtra("transfer_channel") ?: "NPSB"
        val sourceName    = intent.getStringExtra("source_name")     ?: ""
        val sourceAccount = intent.getStringExtra("source_account")  ?: ""
        val timestamp     = intent.getStringExtra("timestamp")       ?: ""

        findViewById<TextView>(R.id.tvSuccessAmount).text   = "BDT ${fmt(amount)}"
        findViewById<TextView>(R.id.tvSuccessChannel).text  = "$channel Transfer"
        findViewById<TextView>(R.id.tvSuccessDateTime).text = timestamp
        findViewById<TextView>(R.id.tvSuccessName).text     = sourceName
        findViewById<TextView>(R.id.tvSuccessAccount).text  = sourceAccount

        findViewById<MaterialButton>(R.id.btnShareReceipt).setOnClickListener {
            val text = """
                ✅ Transaction Successful
                Amount:         BDT ${fmt(amount)}
                Channel:        $channel Transfer
                Account Name:   $sourceName
                Source Account: $sourceAccount
                Date/Time:      $timestamp
            """.trimIndent()
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }, "Share Receipt"
            ))
        }
    }

    private fun fmt(amount: String): String = try {
        String.format("%,.2f", amount.toDouble())
    } catch (_: Exception) { amount }
}