package com.screenrecorder.app

enum class AudioMode(val label: String) {
    DEVICE_AUDIO_ONLY("Device audio only"),
    MICROPHONE_ONLY("Microphone only"),
    DEVICE_AND_MIC("Device audio and microphone"),
    NO_AUDIO("No audio")
}
