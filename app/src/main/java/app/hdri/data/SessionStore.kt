package app.hdri.data

import android.content.Context
import android.util.AtomicFile
import app.hdri.core.*
import app.hdri.core.Target
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Quality(val outputWidth:Int,val bracketCount:Int,val label:String) { QUICK(2048,3,"2K · 3 exposures"), DETAIL(4096,5,"4K · 5 exposures") }
data class Exposure(val file:String,val timeNs:Long,val iso:Int,val timestamp:Long) { val seconds get()=timeNs/1e9*iso/100.0 }
data class Capture(val targetId:Int,val rotation:Q,val position:V3,val lens:Lens,val exposures:List<Exposure>)
data class Project(val id:String,val created:Long,val name:String,val quality:Quality,
    val targets:List<Target> = emptyList(),val captures:List<Capture> = emptyList(),
    val state:String="capture",val stage:String="Ready to capture",val progress:Double=0.0,
    val warnings:List<String> = emptyList(),val error:String?=null,val sample:Boolean=false)

/** Every update re-reads the latest manifest under one process-wide lock. Completed brackets are atomic. */
class SessionStore(context:Context) {
    val root=File(context.filesDir,"sessions").apply { mkdirs() }
    fun dir(id:String):File {require(id.matches(Regex("[a-f0-9-]{36}")));return File(root,id).apply{mkdirs()}}
    fun create(quality:Quality,sample:Boolean=false):Project=synchronized(lock){
        val p=Project(UUID.randomUUID().toString(),System.currentTimeMillis(),if(sample)"Studio light · sample" else "Untitled sphere",quality,sample=sample)
        save(p);p
    }
    fun list():List<Project> = synchronized(lock) { root.listFiles()?.filter{it.isDirectory}?.mapNotNull { runCatching { read(it.name) }.getOrNull() }?.sortedByDescending{it.created}?: emptyList() }
    fun read(id:String):Project=synchronized(lock){decode(JSONObject(AtomicFile(File(dir(id),"session.json")).openRead().bufferedReader().use{it.readText()}))}
    fun update(id:String,change:(Project)->Project):Project=synchronized(lock){change(read(id)).also{save(it)}}
    fun save(p:Project)=synchronized(lock){
        val a=AtomicFile(File(dir(p.id),"session.json"));val stream=a.startWrite()
        try{stream.write(encode(p).toString(2).toByteArray());a.finishWrite(stream)}catch(e:Exception){a.failWrite(stream);throw e}
    }
    fun delete(id:String)=synchronized(lock){dir(id).deleteRecursively()}
    fun checkSpace(id:String,bytes:Long) {check(dir(id).usableSpace>bytes){"Free up at least ${bytes/1_000_000} MB of phone storage, then retry. Your captures are saved."}}
    companion object {
        private val lock=Any()
        private fun arr(values:List<*>)=JSONArray(values)
        private fun vector(v:V3)=arr(listOf(v.x,v.y,v.z))
        private fun quat(q:Q)=arr(listOf(q.x,q.y,q.z,q.w))
        private fun JSONArray.v()=V3(getDouble(0),getDouble(1),getDouble(2))
        private fun JSONArray.q()=Q(getDouble(0),getDouble(1),getDouble(2),getDouble(3)).normalized()
        private fun <T> JSONArray.mapItems(f:(JSONObject)->T)= (0 until length()).map{f(getJSONObject(it))}
        fun encode(p:Project):JSONObject=JSONObject().put("schema",1).put("id",p.id).put("created",p.created).put("name",p.name).put("quality",p.quality.name)
            .put("state",p.state).put("stage",p.stage).put("progress",p.progress).put("sample",p.sample).put("error",p.error?:JSONObject.NULL).put("warnings",arr(p.warnings))
            .put("targets",arr(p.targets.map { JSONObject().put("id",it.id).put("yaw",it.yaw).put("pitch",it.pitch) }))
            .put("captures",arr(p.captures.map { c->JSONObject().put("target",c.targetId).put("rotation",quat(c.rotation)).put("position",vector(c.position))
                .put("lens",arr(listOf(c.lens.width,c.lens.height,c.lens.fx,c.lens.fy,c.lens.cx,c.lens.cy)))
                .put("exposures",arr(c.exposures.map{JSONObject().put("file",it.file).put("timeNs",it.timeNs).put("iso",it.iso).put("timestamp",it.timestamp)})) }))
        fun decode(j:JSONObject):Project {
            require(j.getInt("schema")==1){"This capture uses a newer format. Install the latest Luma Sphere APK."}
            return Project(j.getString("id"),j.getLong("created"),j.getString("name"),Quality.valueOf(j.getString("quality")),
                j.getJSONArray("targets").mapItems{Target(it.getInt("id"),it.getDouble("yaw"),it.getDouble("pitch"))},
                j.getJSONArray("captures").mapItems { c->val l=c.getJSONArray("lens");Capture(c.getInt("target"),c.getJSONArray("rotation").q(),c.getJSONArray("position").v(),
                    Lens(l.getInt(0),l.getInt(1),l.getDouble(2),l.getDouble(3),l.getDouble(4),l.getDouble(5)),
                    c.getJSONArray("exposures").mapItems{Exposure(it.getString("file"),it.getLong("timeNs"),it.getInt("iso"),it.getLong("timestamp"))}) },
                j.getString("state"),j.getString("stage"),j.getDouble("progress"),j.getJSONArray("warnings").let { a->(0 until a.length()).map{a.getString(it)} },
                if(j.isNull("error"))null else j.getString("error"),j.optBoolean("sample"))
        }
    }
}
