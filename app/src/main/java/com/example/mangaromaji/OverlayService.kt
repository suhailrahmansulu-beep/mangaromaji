package com.example.mangaromaji

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlin.math.*

/** Frame observation remains active during the single-pass ML Kit task. */
class OverlayService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val frames=Channel<Bitmap>(Channel.CONFLATED,onUndeliveredElement={it.recycle()})
    private val gate=PageGate(250)
    private lateinit var wm: WindowManager
    private lateinit var report: Report
    private var projection: MediaProjection?=null
    private var capture: ProjectionCapture?=null
    private var layer: BubbleLayer?=null
    private var button: TextView?=null
    private var sample: OverlaySample?=null
    private var clean: Bitmap?=null
    private var debounce: Job?=null
    private var romanizer: Romanizer?=null
    private var recognizer: com.google.mlkit.vision.text.TextRecognizer?=null
    private var on=false
    private var busy=false
    private var dragging=false
    private var destroyed=false
    private var w=0;private var h=0
    private var ack=-1
    private var requestedAt=0L;private var startedAt=0L;private var ocrStarted=0L
    private var completed=0L;private var stale=0L
    private var ocrMs=0L;private var romajiMs=0L;private var totalMs=0L
    private var state="STARTING"
    private data class Result(val bubbles: List<BubbleLayer.Bubble>,val blocks: Int,val japanese: Int,val ocrMs: Long,val romajiMs: Long)
    private data class Pending(val ticket: Long,val result: Result,val elapsed: Long)
    private var pending: Pending?=null
    private val callback=object : MediaProjection.Callback() {
        override fun onStop() { if(!destroyed) { publish("PROJECTION_STOPPED","Android ended capture");stopSelf() } }
        override fun onCapturedContentResize(width: Int,height: Int) {
            if(destroyed || width<=0 || height<=0) return
            invalidatePage();capture?.resize(width,height)
        }
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate();wm=getSystemService(WindowManager::class.java);report=Report(this)
        scope.launch { for(bitmap in frames) accept(bitmap) }
    }
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        when(intent?.action) {
            "stop" -> {publish("STOPPED","Stopped by user");stopSelf();return START_NOT_STICKY}
            "rescan" -> {if(projection!=null) resume() else stopSelf();return START_NOT_STICKY}
            "appearance" -> {layer?.let {present(it.bubbles)};if(projection==null) stopSelf();return START_NOT_STICKY}
        }
        if(projection!=null || destroyed) return START_NOT_STICKY
        val data=intent?.getParcelableExtra<Intent>("data") ?: run {stopSelf();return START_NOT_STICKY}
        try {
            notification()
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
            projection!!.registerCallback(callback,handler)
            addOverlays()
            recognizer=TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            capture=ProjectionCapture(projection!!,resources.configuration.densityDpi,
                deliver={if(frames.trySend(it).isFailure) it.recycle()},
                error={code,error -> handler.post {if(!destroyed) {
                    on=false;invalidatePage();button?.text="!";publish(code,"${error.javaClass.simpleName}: ${error.message}",true)
                    if(code=="CAPTURE_START_FAILED" || code=="CAPTURE_RESIZE_FAILED") stopSelf()
                }} })
            val size=displaySize();startedAt=SystemClock.elapsedRealtime();capture!!.start(size.first,size.second)
            publish("READY","Open manga, then tap RO. Dictionary loading in background.")
            scope.launch {
                try {
                    val value=withContext(NonCancellable+Dispatchers.Default) {Romanizer()}
                    if(!destroyed) {romanizer=value;publish("DICTIONARY_READY","Offline reader ready");schedule()}
                } catch(e: Exception) {if(!destroyed && e !is CancellationException) publish("DICTIONARY_FAILED",e.javaClass.simpleName,true)}
            }
            scope.launch {while(isActive) {delay(2000);health()}}
        } catch(e: Exception) {publish("CAPTURE_START_FAILED","${e.javaClass.simpleName}: ${e.message}",true);stopSelf()}
        return START_NOT_STICKY
    }
    private fun notification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("mr","Manga Romaji Preview",NotificationManager.IMPORTANCE_LOW))
        fun action(value: String,id: Int)=PendingIntent.getService(this,id,Intent(this,OverlayService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"mr").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Manga Romaji Preview ${BuildConfig.VERSION_NAME}")
            .setContentText("Tap RO to read/pause; drag to move; hold to stop").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Rescan",action("rescan",1)).build())
            .addAction(Notification.Action.Builder(null,"Stop",action("stop",2)).build()).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1,n)
    }
    private fun addOverlays() {
        val flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        layer=BubbleLayer(this).also {view ->
            wm.addView(view,WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT
                val maximum=if(Build.VERSION.SDK_INT>=31) getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f
                check(FrameProtocol.WINDOW_ALPHA<=maximum) {"Overlay opacity limit $maximum"};alpha=FrameProtocol.WINDOW_ALPHA
                if(Build.VERSION.SDK_INT>=30) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            })
            view.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ -> if(!destroyed) invalidatePage()}
        }
        val d=resources.displayMetrics.density;val size=(52*d).toInt();val screen=displaySize()
        val prefs=getSharedPreferences("ui",MODE_PRIVATE)
        val lp=WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=(prefs.getFloat("buttonX",0f)*(screen.first-size).coerceAtLeast(0)).toInt()
            y=(prefs.getFloat("buttonY",0.3f)*(screen.second-size).coerceAtLeast(0)).toInt()
        }
        button=TextView(this).apply {
            text="RO ▶";textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;background=circle(Color.DKGRAY)
            contentDescription="Tap to pause or resume manga reading. Drag to move. Hold to stop."
            setOnClickListener {if(on) pause() else resume()}
            var downX=0f;var downY=0f;var startX=0;var startY=0;var at=0L
            val slop=ViewConfiguration.get(this@OverlayService).scaledTouchSlop
            setOnTouchListener {view,e ->
                when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {downX=e.rawX;downY=e.rawY;startX=lp.x;startY=lp.y;at=SystemClock.elapsedRealtime();dragging=false}
                    MotionEvent.ACTION_MOVE -> {
                        val dx=e.rawX-downX;val dy=e.rawY-downY
                        if(!dragging && (abs(dx)>slop || abs(dy)>slop)) {dragging=true;invalidatePage()}
                        if(dragging) {val bounds=displaySize();lp.x=(startX+dx.toInt()).coerceIn(0,(bounds.first-size).coerceAtLeast(0));lp.y=(startY+dy.toInt()).coerceIn((24*d).toInt(),(bounds.second-size-24*d).toInt().coerceAtLeast((24*d).toInt()));wm.updateViewLayout(view,lp);sample=null}
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                        if(dragging) {
                            val bounds=displaySize();prefs.edit().putFloat("buttonX",lp.x.toFloat()/(bounds.first-size).coerceAtLeast(1)).putFloat("buttonY",lp.y.toFloat()/(bounds.second-size).coerceAtLeast(1)).apply()
                            dragging=false;invalidatePage()
                        } else if(e.actionMasked==MotionEvent.ACTION_UP) {
                            if(SystemClock.elapsedRealtime()-at>700) {publish("STOPPED","Stopped by user");stopSelf()} else view.performClick()
                        }
                    }
                };true
            }
        }.also {wm.addView(it,lp)}
    }
    private fun circle(color: Int)=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(color)}
    private fun displaySize(): Pair<Int,Int> {
        if(Build.VERSION.SDK_INT>=30) return wm.maximumWindowMetrics.bounds.let {it.width() to it.height()}
        @Suppress("DEPRECATION") val m=android.util.DisplayMetrics().also {wm.defaultDisplay.getRealMetrics(it)}
        return m.widthPixels to m.heightPixels
    }
    private fun present(value: List<BubbleLayer.Bubble>) {
        layer?.present(value);sample=null;ack=-1;requestedAt=SystemClock.elapsedRealtime()
    }
    private fun invalidatePage() {
        gate.invalidate();debounce?.cancel();pending=null;clean?.recycle();clean=null;present(emptyList())
    }
    private fun pause() {on=false;invalidatePage();button?.text="RO ▶";button?.background=circle(Color.DKGRAY);publish("PAUSED","Capture continues; recognition paused")}
    private fun resume() {on=true;invalidatePage();button?.text="…";button?.background=circle(Color.rgb(216,50,42));publish("WAITING_CLEAN_FRAME","Waiting for a confirmed frame")}
    private fun ignored(): List<Rect> {
        val result=mutableListOf<Rect>()
        button?.let {v -> val p=IntArray(2).also {v.getLocationOnScreen(it)};result+=Rect(p[0]-6,p[1]-6,p[0]+v.width+6,p[1]+v.height+6)}
        if(Build.VERSION.SDK_INT>=30) layer?.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())?.let {result+=Rect(0,0,w,it.top);result+=Rect(0,h-it.bottom,w,h)}
        return result
    }
    private fun accept(bitmap: Bitmap) {
        var retained=false
        try {
            val view=layer ?: return
            if(destroyed || view.width==0 || view.height==0) return
            if(w!=bitmap.width || h!=bitmap.height) {w=bitmap.width;h=bitmap.height;invalidatePage()}
            val model=sample ?: OverlaySample(view,bitmap.width,bitmap.height,ignored()).also {sample=it}
            if(!model.matches(bitmap)) return
            ack=model.revision
            if(!on || dragging) return
            val moved=gate.observe(model.signature(bitmap),SystemClock.elapsedRealtime())
            if(moved) {
                publish("PAGE_CHANGED","Generation ${gate.generation}; waiting to settle")
                if(view.bubbles.isNotEmpty()) {clean?.recycle();clean=null;present(emptyList());return}
            }
            pending?.let {ready ->
                pending=null
                if(gate.complete(ready.ticket)) {
                    completed++;ocrMs=ready.result.ocrMs;romajiMs=ready.result.romajiMs;totalMs=ready.elapsed
                    val position=IntArray(2).also {view.getLocationOnScreen(it)}
                    present(ready.result.bubbles.map {it.copy(rect=RectF(it.rect).apply {offset(-position[0].toFloat(),-position[1].toFloat())})})
                    button?.text="RO ${ready.result.bubbles.size}"
                    LatestReading.text=ready.result.bubbles.joinToString("\n\n") {it.text}.ifBlank {"No Japanese regions found."}
                    publish(if(ready.result.japanese==0) "OCR_ZERO_JAPANESE" else "CAPTIONS_READY","${ready.result.blocks} text blocks; ${ready.result.japanese} Japanese regions; OCR=$ocrMs ms; romaji=$romajiMs ms; total=$totalMs ms")
                    return
                } else {stale++;publish("STALE_DROPPED","Generation ${ready.ticket}; current ${gate.generation}")}
            }
            if(view.bubbles.isEmpty()) {clean?.recycle();clean=bitmap;retained=true;if(moved || debounce==null) schedule()}
        } catch(e: Exception) {publish("FRAME_ANALYSIS_FAILED","${e.javaClass.simpleName}: ${e.message}",true)}
        finally {if(!retained) bitmap.recycle()}
    }
    private fun schedule() {debounce?.cancel();debounce=scope.launch {delay(280);debounce=null;recognizeIfReady()}}
    private fun recognizeIfReady() {
        val rom=romanizer ?: return
        if(!on || busy || pending!=null || destroyed) return
        val ticket=gate.ticket(SystemClock.elapsedRealtime()) ?: return
        val input=clean ?: return
        clean=null;busy=true;ocrStarted=SystemClock.elapsedRealtime();button?.text="OCR";publish("OCR_RUNNING","Generation $ticket; single pass")
        scope.launch {
            var phase="OCR_FAILED"
            try {
                val result=withContext(NonCancellable+Dispatchers.Default) {
                    val begin=SystemClock.elapsedRealtime()
                    val text=recognizer!!.process(InputImage.fromBitmap(input,0)).await()
                    val detectedMs=SystemClock.elapsedRealtime()-begin
                    phase="ROMAJI_FAILED"
                    val convert=SystemClock.elapsedRealtime();var japanese=0
                    val bubbles=text.textBlocks.mapNotNull {block ->
                        val box=block.boundingBox ?: return@mapNotNull null
                        if(!JP.containsMatchIn(block.text)) return@mapNotNull null
                        japanese++
                        val vertical=block.lines.isNotEmpty() && block.lines.count {val r=it.boundingBox;r!=null && r.height()>r.width()*1.3f}>block.lines.size/2
                        val lines=if(vertical) block.lines.sortedByDescending {it.boundingBox?.centerX() ?: 0} else block.lines
                        val source=lines.joinToString(if(vertical) "" else "\n") {it.text}.replace(GAP,"")
                        val value=rom.convert(source)
                        if(value.isBlank()) return@mapNotNull null
                        val r=RectF(box)
                        if(vertical) {val target=max(r.width(),min(r.height()*0.8f,input.width*0.4f));val cx=r.centerX();r.left=cx-target/2;r.right=cx+target/2}
                        r.inset(-6f,-6f);r.left=max(0f,r.left);r.top=max(0f,r.top);r.right=min(input.width.toFloat(),r.right);r.bottom=min(input.height.toFloat(),r.bottom)
                        BubbleLayer.Bubble(r,value)
                    }
                    Result(bubbles,text.textBlocks.size,japanese,detectedMs,SystemClock.elapsedRealtime()-convert)
                }
                if(destroyed || !on || !gate.current(ticket)) {stale++;if(!destroyed) publish("STALE_DROPPED","Generation $ticket; current ${gate.generation}")}
                else {pending=Pending(ticket,result,SystemClock.elapsedRealtime()-ocrStarted);present(emptyList());publish("VALIDATING_PAGE","Waiting for compositor confirmation after OCR")}
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                if(!destroyed) {publish(phase,"Generation $ticket; ${e.javaClass.simpleName}: ${e.message}",true);if(gate.current(ticket)) {gate.complete(ticket);button?.text="!"}}
            } finally {
                input.recycle();busy=false
                if(destroyed) {recognizer?.close();recognizer=null} else if(on) schedule()
                report.refresh(counters())
            }
        }
    }
    private fun counters()="frames=${capture?.received ?: 0}, copied=${capture?.copied ?: 0}, frameAgeMs=${capture?.let {if(it.lastFrameAt==0L) -1 else SystemClock.elapsedRealtime()-it.lastFrameAt}}\n"+
        "size=${w}x$h, generation=${gate.generation}, overlay=${layer?.revision}, ack=$ack\n"+
        "running=$on, ocrBusy=$busy, ocrAgeMs=${if(busy) SystemClock.elapsedRealtime()-ocrStarted else 0}, completed=$completed, staleDropped=$stale\n"+
        "lastCompleted: ocrMs=$ocrMs, romajiMs=$romajiMs, totalMs=$totalMs; singlePass=true\n"+
        "nativeHeapMbNow=${Debug.getNativeHeapAllocatedSize()/1048576}, processPssMbNow=${Debug.getPss()/1024}"
    private fun publish(code: String,detail: String,failed: Boolean=false) {state=code;report.publish(code,detail,counters(),failed)}
    private fun health() {
        if(!destroyed && capture?.received==0L && SystemClock.elapsedRealtime()-startedAt>6000 && state!="CAPTURE_NO_FRAMES") publish("CAPTURE_NO_FRAMES","No ImageReader images; stop and grant entire-display capture again",true)
        else if(!destroyed && on && ack!=layer?.revision && SystemClock.elapsedRealtime()-requestedAt>5000 && state!="CAPTURE_UNCONFIRMED") publish("CAPTURE_UNCONFIRMED","Frames lack the overlay marker; choose Entire screen",true)
        report.refresh(counters())
    }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config);invalidatePage();val size=displaySize();capture?.resize(size.first,size.second)
        button?.let {view ->
            val lp=view.layoutParams as WindowManager.LayoutParams
            lp.x=lp.x.coerceIn(0,(size.first-view.width).coerceAtLeast(0))
            lp.y=lp.y.coerceIn(0,(size.second-view.height).coerceAtLeast(0));wm.updateViewLayout(view,lp)
        }
    }
    override fun onDestroy() {
        destroyed=true;on=false;gate.invalidate();pending=null;debounce?.cancel();clean?.recycle();clean=null
        capture?.close();frames.cancel();scope.cancel()
        button?.let {runCatching {wm.removeView(it)}};layer?.let {runCatching {wm.removeView(it)}}
        projection?.unregisterCallback(callback);projection?.stop();projection=null
        if(!busy) {recognizer?.close();recognizer=null}
        publish("STOPPED","Preview capture ended");stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        private val JP=Regex("[\u3040-\u30ff\u3400-\u9fff]")
        private val GAP=Regex("(?<=[^\\x00-\\x7F])\\s+(?=[^\\x00-\\x7F])")
    }
}
