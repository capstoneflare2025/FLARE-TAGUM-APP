package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityOnboading4Binding

class Onboard4Activity: AppCompatActivity() {

    private lateinit var binding: ActivityOnboading4Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboading4Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nextButton.setOnClickListener{
            startActivity(Intent(this, Onboard3Activity::class.java))
            finish()
        }
    }
}