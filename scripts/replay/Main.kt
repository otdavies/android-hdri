package app.hdri.processing
import android.content.Context
import app.hdri.data.SessionStore
import java.io.File
fun main(args: Array<String>) {
 val store = SessionStore(Context(File(args[0])))
 val project = store.list().single()
 val start = System.nanoTime()
 var last = ""
 HdrPipeline(store, project, { stage, p ->
   val group = stage.substringBefore(" ·").substringBefore(" ·")
   if (group != last) { println("${(System.nanoTime()-start)/1e9} ${"%.1f".format(p*100)}% $stage"); last=group }
 }, {}).run()
 println("HOST_SECONDS=${(System.nanoTime()-start)/1e9}")
}
