package com.example.hbook.util;

import android.speech.tts.TextToSpeech;

public class EmotionTtsHelper {

    private static final float RATE_MIN = 0.6f;
    private static final float RATE_MAX = 1.6f;
    private static final float RATE_BASE = 1.0f;

    private static final float PITCH_MIN = 0.7f;
    private static final float PITCH_MAX = 1.4f;
    private static final float PITCH_BASE = 1.0f;

    public static void speakWithEmotion(TextToSpeech tts, String text, float valence, float arousal, String utteranceId) {
        if (tts == null || text == null || text.isEmpty()) return;

        float speechRate = calcSpeedRate(valence, arousal);
        float pitch = calcPitch(valence, arousal);

        tts.setSpeechRate(speechRate);
        tts.setPitch(pitch);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    private  static float calcSpeedRate(float valence, float arousal) {
        float range = (RATE_MAX - RATE_MIN) / 2f;
        float rate = RATE_BASE + (arousal * range);

        if (valence < -0.5f) {
            rate -= (-valence - 0.5f) * 0.3f;
        }

        return clamp(rate, RATE_MIN, RATE_MAX);
    }

    private  static float calcPitch(float valence, float arousal) {
        float range = (PITCH_MAX - PITCH_MIN) / 2f;
        float pitch = PITCH_BASE + (valence * range);

        if (arousal > 0.5f && valence > 0f) {
            pitch += arousal * 0.1f;
        }

        return clamp(pitch, PITCH_MIN, PITCH_MAX);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
