package com.gsmautoanswer.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LinearResamplerTest {

    @Test
    fun `48k to 16k keeps one third of the samples`() {
        val resampler = LinearResampler(sourceRate = 48_000)
        var emitted = 0
        repeat(48_000) { resampler.push(0.25f) { emitted++ } }

        assertEquals(16_000, emitted)
    }

    @Test
    fun `8k to 16k doubles the samples`() {
        val resampler = LinearResampler(sourceRate = 8_000)
        var emitted = 0
        repeat(8_000) { resampler.push(0.5f) { emitted++ } }
        // One leading sample is emitted by the first push, so the last interval stays open.
        assertTrue(abs(emitted - 16_000) <= 1)
    }

    @Test
    fun `interpolates between samples`() {
        val resampler = LinearResampler(sourceRate = 8_000)
        val output = mutableListOf<Float>()
        resampler.push(0f) { output.add(it) }
        resampler.push(1f) { output.add(it) }

        assertEquals(3, output.size)
        assertEquals(0f, output[0], 0.0001f)
        assertEquals(0.5f, output[1], 0.0001f)
        assertEquals(1f, output[2], 0.0001f)
    }

    @Test
    fun `chunk boundaries do not restart the timeline`() {
        val resampler = LinearResampler(sourceRate = 48_000)
        val output = mutableListOf<Float>()
        repeat(48_000) { resampler.push((it % 100).toFloat()) { value -> output.add(value) } }

        assertEquals(16_000, output.size)
        assertEquals(0f, output.first(), 0.0001f)
    }
}
