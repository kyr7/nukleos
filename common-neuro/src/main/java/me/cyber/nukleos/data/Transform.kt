package me.cyber.nukleos.data


fun mapNeuralDefault(data: List<FloatArray>) =
        mapNeural(data,
                { channel -> meanAbsoluteValue(channel) },
                { channel -> waveformLength(channel) },
                { channel -> zeroCrossing(channel) },
                { channel -> slopeSignChanges(channel) }
        )

/**
 * @param data - list where each element is data reading. Each data reading has multiple channels (FloatArray)
 * @return list where each element is features for a channel
 */
fun mapNeural(data: List<FloatArray>,
              vararg features: (List<Float>) -> Float): List<FloatArray> {
    val result = ArrayList<FloatArray>()
    for (i in data[0].indices) {
        val channelData: List<Float> = data.map { it[i] }
        val channelFeatures: FloatArray = features.map { it(channelData) }.toFloatArray()
        result.add(channelFeatures)
    }
    return result
}


fun meanAbsoluteValue(segment: List<Float>): Float {
    var sum = 0.0F
    for (element in segment) {
        sum += kotlin.math.abs(element)
    }
    return sum / segment.size
}

fun waveformLength(segment: List<Float>): Float {
    var wl = 0.0F
    for (i in 1 until segment.size) {
        wl += kotlin.math.abs(segment[i] - segment[i - 1])
    }
    return wl
}

fun zeroCrossing(segment: List<Float>): Float {
    var zc = 0.0F
    for (i in 0 until segment.size - 1) {
        if (segment[i] * segment[i + 1] < 0) {
            zc += 1
        }
    }
    return zc
}

fun slopeSignChanges(segment: List<Float>): Float {
    var ssc = 0.0F
    for (i in segment.indices) {
        if ((segment[i - 1] < segment[i] && segment[i] > segment[i + 1]) || (segment[i - 1] > segment[i] && segment[i] < segment[i + 1])) {
            ssc += 1
        }
    }
    return ssc
}