package android.graphics
import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.ImageIO
class Bitmap(val image: BufferedImage) {
 enum class Config { ARGB_8888 }
 enum class CompressFormat { JPEG }
 fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) { image.setRGB(x,y,w,h,pixels,offset,stride) }
 fun compress(format: CompressFormat, quality: Int, output: OutputStream): Boolean = ImageIO.write(image,"jpg",output)
 fun recycle() {}
 companion object { fun createBitmap(w: Int,h: Int,config: Config) = Bitmap(BufferedImage(w,h,BufferedImage.TYPE_INT_RGB)) }
}
