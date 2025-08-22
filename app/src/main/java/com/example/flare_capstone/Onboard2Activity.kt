package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityOnboard2Binding

class Onboard2Activity: AppCompatActivity() {

    private lateinit var binding: ActivityOnboard2Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityOnboard2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nextButton.setOnClickListener{
            startActivity(Intent(this, Onboard3Activity::class.java))
            finish()
        }

    }
}