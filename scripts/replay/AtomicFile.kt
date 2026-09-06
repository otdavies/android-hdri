package android.util
import java.io.File
import java.io.FileOutputStream
class AtomicFile(val file: File) {
 fun openRead() = file.inputStream()
 fun startWrite(): FileOutputStream = file.outputStream()
 fun finishWrite(out: FileOutputStream) { out.close() }
 fun failWrite(out: FileOutputStream) { out.close() }
}
