/*package com.example.droneswarm.server

import androidx.compose.runtime.Immutable

/**
 * DRONE DEVICE DATA MODEL
 * * Humne ise 'data class' banaya hai taaki MainActivity mein .copy() use ho sake.
 * 'Immutable' annotation Compose compiler ko optimization mein help karti hai.
 */
@Immutable
data class DroneDevice(
    val id: String,
    val name: String,
    val battery: Int,      // 'initialBattery' ki jagah direct 'battery' use kiya
    val height: String,
    val signal: String,    // 'initialSignal' ki jagah direct 'signal' use kiya
    val status: String = "ACTIVE",
    val startTime: Long = System.currentTimeMillis(),
    val connectTime: Long = System.currentTimeMillis(),
    val lat: Double, // Latitude add karein
    val lon: Double
)

    /**
     * Helper to format seconds into MM:SS
     */

/* Note for Developer:
   Jab aap connectedDronesList[index] = currentDrone.copy(battery = newLevel) call karte ho,
   toh Compose automatically detect kar leta hai ki list ka element change hua hai
   aur UI ko recompose kar deta hai. Isliye yahan internal 'var' ya 'mutableStateOf'
   ki zaroorat nahi hai.
*/*/