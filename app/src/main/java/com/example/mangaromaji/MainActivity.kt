package com.example.mangaromaji

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val projectionLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        if(result.resultCode==RESULT_OK && result.data!=null) {
            startForegroundService(Intent(this,OverlayService::class.java).putExtra("data",result.data))
            Toast.makeText(this,"Open manga and tap RO. Drag RO to move it.",Toast.LENGTH_LONG).show()
        } else Toast.makeText(this,"Capture permission cancelled",Toast.LENGTH_SHORT).show()
    }
    private val notificationLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()) {askProjection()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(28,48,28,24)}
        root.addView(TextView(this).apply {text="Manga Romaji Preview\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})";textSize=23f})
        root.addView(TextView(this).apply {
            text="Offline Japanese → romaji. This preview installs alongside the original app. Stop its capture before starting this one.\n\nOpen manga, tap RO, then scroll normally. Drag RO to move it; hold and release to stop. Screenshots remain available."
            textSize=16f;setPadding(0,24,0,24)
        })
        root.addView(Button(this).apply {text="Start screen capture";setOnClickListener {startCapture()}})
        root.addView(Button(this).apply {text="Settings";setOnClickListener {settings()}})
        setContentView(ScrollView(this).apply {addView(root)})
    }
    private fun startCapture() {
        if(!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            Toast.makeText(this,"Allow the overlay, then tap Start again",Toast.LENGTH_LONG).show();return
        }
        stopService(Intent(this,OverlayService::class.java))
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askProjection()
    }
    private fun askProjection() {
        val manager=getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
    }
    private fun showText(title: String,value: String,copy: Boolean=false) {
        val view=TextView(this).apply {text=value;textSize=if(copy) 13f else 18f;setPadding(24,16,24,16);setTextIsSelectable(true)}
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply {addView(view)}).setNegativeButton("Close",null)
        if(copy) dialog.setPositiveButton("Copy") {_,_ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title,value))}
        dialog.show()
    }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("Preview settings").setItems(arrayOf("Caption size","Read full latest result","Diagnostics","Stop preview capture")) {_,which ->
            when(which) {
                0 -> captionSize()
                1 -> showText("Full reading",LatestReading.text)
                2 -> showText("Diagnostics",getSharedPreferences("diagnostics",MODE_PRIVATE).getString("status","No capture session yet.") ?: "No capture session yet.",true)
                3 -> stopService(Intent(this,OverlayService::class.java))
            }
        }.setNegativeButton("Close",null).show()
    }
    private fun captionSize() {
        val prefs=getSharedPreferences("ui",MODE_PRIVATE)
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        val label=TextView(this)
        val slider=SeekBar(this).apply {max=8;progress=prefs.getInt("captionSp",12)-10}
        fun update() {label.text="${slider.progress+10} sp. Overflow uses …; the full result remains in Settings."}
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?,progress: Int,fromUser: Boolean)=update()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        update();box.addView(label);box.addView(slider)
        AlertDialog.Builder(this).setTitle("Caption size").setView(box).setPositiveButton("Save") {_,_ ->
            prefs.edit().putInt("captionSp",slider.progress+10).apply()
            startService(Intent(this,OverlayService::class.java).setAction("appearance"))
        }.setNegativeButton("Cancel",null).show()
    }
}
