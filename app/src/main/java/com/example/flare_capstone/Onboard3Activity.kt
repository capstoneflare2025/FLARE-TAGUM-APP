package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityOnboard3Binding

class Onboard3Activity: AppCompatActivity() {

    private lateinit var binding: ActivityOnboard3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityOnboard3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.getStartedButton.setOnClickListener{
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

    }
}