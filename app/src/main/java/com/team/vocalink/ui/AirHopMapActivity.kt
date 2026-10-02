package com.team.vocalink.ui
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.team.vocalink.mesh.AirHopNode
import com.team.vocalink.mesh.NodePresenceDirectory
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
class AirHopMapActivity : AppCompatActivity() {
 private lateinit var mapView: RelayMapView
 private lateinit var count: TextView
 private val scope = MainScope()
 private lateinit var directory: NodePresenceDirectory
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  directory = com.team.vocalink.service.AirHopMeshService.instance?.nodePresenceDirectory ?: NodePresenceDirectory(this)
  val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xFF05070A.toInt()); setPadding(16,16,16,16) }
  root.addView(TextView(this).apply { text="AirHop Map"; textSize=24f; setTextColor(0xFFFFFFFF.toInt()) })
  count=TextView(this).apply { textSize=13f; setTextColor(0xFF69D7FF.toInt()) }; root.addView(count)
  mapView=RelayMapView().apply { layoutParams=LinearLayout.LayoutParams(-1,0,1f) }; root.addView(mapView); setContentView(root)
  scope.launch { directory.nodes.collectLatest { mapView.nodes=it; count.text="Authenticated relay nodes visible: ${it.size} • local, time-limited presence" } }
 }
 override fun onDestroy(){ scope.cancel(); super.onDestroy() }
 private inner class RelayMapView: View(this@AirHopMapActivity) {
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  var nodes: List<AirHopNode> = emptyList(); set(value){field=value;invalidate()}
  override fun onDraw(c:Canvas){
   super.onDraw(c); paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=0xFF1B3340.toInt()
   for(x in 0..10)c.drawLine(width*x/10f,0f,width*x/10f,height.toFloat(),paint)
   for(y in 0..10)c.drawLine(0f,height*y/10f,width.toFloat(),height*y/10f,paint)
   if(nodes.isEmpty()){paint.style=Paint.Style.FILL;paint.color=0xFF8A98A8.toInt();paint.textSize=18f;c.drawText("Waiting for authenticated nearby relay beacons…",24f,height/2f,paint);return}
   val minLat=nodes.minOf{it.lat};val maxLat=nodes.maxOf{it.lat};val minLon=nodes.minOf{it.lon};val maxLon=nodes.maxOf{it.lon}
   val latSpan=(maxLat-minLat).coerceAtLeast(0.001);val lonSpan=(maxLon-minLon).coerceAtLeast(0.001);paint.style=Paint.Style.FILL
   nodes.forEach{n->val x=((n.lon-minLon)/lonSpan*(width-48)+24).toFloat();val y=((maxLat-n.lat)/latSpan*(height-48)+24).toFloat();paint.color=0xFF55E6A5.toInt();c.drawCircle(x,y,9f,paint);paint.color=0xFFFFFFFF.toInt();paint.textSize=12f;c.drawText("%08X".format(n.nodeId),x+14,y+4,paint)}
  }
 }
}
