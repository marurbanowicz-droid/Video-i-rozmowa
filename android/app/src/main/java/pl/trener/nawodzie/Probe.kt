package pl.trener.nawodzie

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

object Probe {
    fun make(): ExternalAudioProcessingFactory {
        val f = ExternalAudioProcessingFactory()
        f.setCapturePostProcessing(object : ExternalAudioProcessingFactory.AudioProcessing {
            override fun initialize(sampleRateHz: Int, numChannels: Int) {}
            override fun reset(newRate: Int) {}
            override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {}
        })
        return f
    }
}
