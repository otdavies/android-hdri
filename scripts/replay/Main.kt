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
 if ("--export-exr" in args) {
   val exportStart = System.nanoTime()
   val dir = store.dir(project.id)
   var lastPercent = -10
   ExrExport.write(File(dir, "environment.hdr"), File(dir, "environment.exr"), { progress ->
     val percent = (progress * 100).toInt()
     if (percent >= lastPercent + 10) { println("OpenEXR export: $percent%"); lastPercent = percent }
   }, {})
   println("EXR_EXPORT_SECONDS=${(System.nanoTime()-exportStart)/1e9}")
 }
}
