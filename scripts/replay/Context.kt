package android.content
import java.io.File
class Context(val filesDir: File) { val cacheDir=File(filesDir,"cache").apply { mkdirs() } }
