package com.example.springcat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.springcat.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The text shown on screen comes from the C++ layer.
        binding.sampleText.text = stringFromCpp()
    }

    // Implemented in native-lib.cpp and called through JNI.
    private external fun stringFromCpp(): String

    companion object {
        init {
            // Loads libspringcat.so, produced by CMake from our C++ sources.
            System.loadLibrary("springcat")
        }
    }
}
