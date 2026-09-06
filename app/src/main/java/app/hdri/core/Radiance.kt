package app.hdri.core

import java.io.OutputStream
import kotlin.math.*

object Radiance {
    fun srgb(x:Double)=if(x<=.0031308)12.92*x else 1.055*x.pow(1/2.4)-.055
    fun linear(x:Double)=if(x<=.04045)x/12.92 else ((x+.055)/1.055).pow(2.4)
    fun response()=FloatArray(256*3){linear((it/3)/255.0).toFloat()}
    data class Merge(val rgb:FloatArray,val clipped:Int,val moving:Int)
    /** Images and response are BGR; output is linear BGR. Normalize with measured shutter * ISO. */
    fun merge(images:List<ByteArray>,times:DoubleArray,response:FloatArray):Merge {
        require(images.size==times.size&&images.isNotEmpty()&&times.all{it>0&&it.isFinite()})
        require(images.all{it.size==images[0].size});require(response.size==768)
        val out=FloatArray(images[0].size);var clipped=0;var moving=0
        val shortest=times.indices.minBy{times[it]};val longest=times.indices.maxBy{times[it]}
        var p=0
        while(p<out.size){
            var reference=0;var best=-1
            for(i in images.indices){var weight=255;for(c in 0..2){val z=images[i][p+c].toInt() and 255;weight=min(weight,min(z,255-z))};if(weight>best){best=weight;reference=i}}
            var referenceL=0.0
            for(c in 0..2){val z=images[reference][p+c].toInt() and 255;referenceL+=response[z*3+c]/times[reference]}
            var moved=false
            for(c in 0..2){
                var sum=0.0;var weights=0.0
                for(i in images.indices){
                    val z=images[i][p+c].toInt() and 255
                    if(z<4||z>251)continue
                    var l=0.0;for(k in 0..2){val a=images[i][p+k].toInt() and 255;l+=response[a*3+k]/times[i]}
                    if(best>18&&i!=reference&&abs(ln((l+1e-8)/(referenceL+1e-8)))>.55){moved=true;continue}
                    val w=min(z,255-z).toDouble().pow(2);sum+=response[z*3+c]/times[i]*w;weights+=w
                }
                if(weights>0)out[p+c]=(sum/weights).toFloat()
                else{val i=if((images[shortest][p+c].toInt() and 255)>251)shortest else longest;val z=images[i][p+c].toInt() and 255;out[p+c]=(response[z*3+c]/times[i]).toFloat()}
                if(!out[p+c].isFinite()||out[p+c]<0)out[p+c]=0f
            }
            if((0..2).any{(images[shortest][p+it].toInt() and 255)>251})clipped++
            if(moved)moving++
            p+=3
        }
        return Merge(out,clipped,moving)
    }
    fun rgbe(r:Float,g:Float,b:Float):ByteArray {
        require(r.isFinite()&&g.isFinite()&&b.isFinite())
        val maximum=max(r,max(g,b)).toDouble()
        if(maximum<1e-32)return ByteArray(4)
        val e=floor(log2(maximum)).toInt()+1
        val scale=256.0/2.0.pow(e)
        return byteArrayOf((r*scale).toInt().coerceIn(0,255).toByte(),(g*scale).toInt().coerceIn(0,255).toByte(),(b*scale).toInt().coerceIn(0,255).toByte(),(e+128).coerceIn(0,255).toByte())
    }
}

/** Standard Radiance RGBE, row-wise RLE; only a single scanline is retained. */
class HdrWriter(private val stream:OutputStream,private val width:Int,height:Int):AutoCloseable {
    init{require(width in 8..32767&&height>0);stream.write("#?RADIANCE\n# Luma Sphere - relative scene radiance, linear RGB / D65\nFORMAT=32-bit_rle_rgbe\n\n-Y $height +X $width\n".toByteArray(Charsets.US_ASCII))}
    fun row(bgr:FloatArray,offset:Int=0){
        require(bgr.size-offset>=width*3)
        stream.write(byteArrayOf(2,2,(width shr 8).toByte(),width.toByte()))
        val channels=Array(4){ByteArray(width)}
        for(x in 0 until width){val p=offset+x*3;val bytes=Radiance.rgbe(bgr[p+2],bgr[p+1],bgr[p]);for(c in 0..3)channels[c][x]=bytes[c]}
        channels.forEach{channel->var x=0;while(x<width){val n=min(128,width-x);stream.write(n);stream.write(channel,x,n);x+=n}}
    }
    override fun close(){stream.close()}
}
