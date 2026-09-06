package app.hdri

import app.hdri.core.*
import app.hdri.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.*

class RadianceTest {
    @Test fun exposureMergeRecoversLargeRadianceRange(){
        val values=doubleArrayOf(.02,.2,2.0,20.0,1000.0)
        val times=doubleArrayOf(.0001,.004,.04,.25)
        val photos=times.map{t->ByteArray(values.size*3){i->(Radiance.srgb((values[i/3]*t).coerceIn(0.0,1.0))*255).roundToInt().toByte()}}
        val merged=Radiance.merge(photos,times,Radiance.response())
        values.forEachIndexed{i,expected->assertEquals(expected,merged.rgb[i*3].toDouble(),expected*.09)}
        assertTrue(merged.rgb.last()/merged.rgb.first()>40000)
    }
    @Test fun rgbePreservesHdrAndChannelOrder(){
        val values=listOf(floatArrayOf(.001f,.002f,.003f),floatArrayOf(500f,1000f,2000f),floatArrayOf(0f,0f,0f))
        for(v in values){val bytes=Radiance.rgbe(v[0],v[1],v[2]);val factor=2.0.pow((bytes[3].toInt() and 255)-128)/256
            for(c in 0..2)assertEquals(v[c].toDouble(),(bytes[c].toInt() and 255)*factor,max(.0001,v.max()*0.008))}
        val out=ByteArrayOutputStream();HdrWriter(out,8,1).use{it.row(FloatArray(24){if(it%3==2)100f else 1f})}
        val bytes=out.toByteArray();val marker="-Y 1 +X 8\n".toByteArray();val start=bytes.indices.first{i->i+marker.size<=bytes.size&&marker.indices.all{bytes[i+it]==marker[it]}}+marker.size
        assertEquals(2,bytes[start].toInt());assertEquals(8,bytes[start+3].toInt());assertEquals(8,bytes[start+4].toInt())
        assertEquals(4+4*(1+8),bytes.size-start)
    }
    @Test fun manifestRetainsExactExposureTimestampsAndOrientation(){
        val p=Project("12345678-1234-1234-1234-123456789abc",1234,"Test",Quality.DETAIL,targets=Sphere.targets(60.0),captures=listOf(Capture(0,Q.look(45.0,20.0),V3.ZERO,Lens(800,600,500.0,500.0,400.0,300.0),listOf(Exposure("one.jpg",123456789L,125,987654321234567L)))))
        val restored=SessionStore.decode(SessionStore.encode(p))
        assertEquals(p.captures[0].exposures,restored.captures[0].exposures)
        assertTrue(p.captures[0].rotation.angle(restored.captures[0].rotation)<1e-5)
        assertEquals(p.targets,restored.targets)
    }
    @Test(expected=IllegalArgumentException::class) fun unknownManifestVersionFailsClearly(){
        SessionStore.decode(SessionStore.encode(Project("12345678-1234-1234-1234-123456789abc",1,"Test",Quality.QUICK)).put("schema",99))
    }
}
