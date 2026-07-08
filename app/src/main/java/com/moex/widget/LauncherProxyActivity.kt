package com.moex.widget

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

import android.widget.Toast

class LauncherProxyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Toast.makeText(this, R.string.instructions, Toast.LENGTH_LONG).show()
        finish()
    }
}