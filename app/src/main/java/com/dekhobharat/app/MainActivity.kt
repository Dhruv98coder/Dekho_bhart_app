package com.dekhobharat.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        pendingGeoCallback?.let { callback ->
            val origin = pendingGeoOrigin ?: ""
            val allowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            callback.invoke(origin, allowed, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startNativeSpeechInternal() else notifyJsError("Microphone permission denied")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }

        webView = WebView(this)
        setContentView(webView)
        configureWebView()
        webView.loadUrl(BuildConfig.BASE_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " DekhoBharatAndroid/1.0"

        webView.addJavascriptInterface(AndroidBridge(), "AndroidSpeech")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return openUrlIfExternal(request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return openUrlIfExternal(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAndroidSpeechFallbacks()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin == null || callback == null) return
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }
    }

    private fun openUrlIfExternal(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme == "http" || scheme == "https") {
            val baseHost = Uri.parse(BuildConfig.BASE_URL).host
            if (uri.host == baseHost) return false
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun injectAndroidSpeechFallbacks() {
        val js = """
            (function(){
              if(window.__dekhoAndroidInjected)return;
              window.__dekhoAndroidInjected=true;
              window.SpeechSynthesisUtterance = window.SpeechSynthesisUtterance || function(text){
                this.text=text||''; this.lang='en-US'; this.rate=1; this.pitch=1; this.volume=1;
                this.onstart=null; this.onend=null; this.onerror=null;
              };
              if(!('speechSynthesis' in window)){
                window.speechSynthesis={speaking:false,cancel:function(){AndroidSpeech.stopSpeaking();this.speaking=false;},speak:function(u){
                  this.speaking=true;
                  try{if(u.onstart)u.onstart();}catch(e){}
                  AndroidSpeech.speak(String(u.text||''),String(u.lang||'en-US'),Number(u.rate||1),Number(u.pitch||1));
                  window.__dekhoTtsEnd=function(){window.speechSynthesis.speaking=false;try{if(u.onend)u.onend();}catch(e){}};
                }};
              }
              window.__dekhoAndroidSpeechStart=function(){AndroidSpeech.startListening();};
              window.__dekhoAndroidSpeechStop=function(){AndroidSpeech.stopListening();};
              window.__dekhoAndroidSpeechResult=function(text){
                var tb=document.getElementById('textBox'); if(tb){tb.value=text;}
                try{if(typeof autoTranslate==='function'){clearTimeout(window.translationTimer);window.translationTimer=setTimeout(autoTranslate,200);}}catch(e){}
              };
              window.__dekhoAndroidSpeechError=function(msg){console.warn(msg);};
              var btn=document.getElementById('voiceBtn');
              if(btn && !(window.SpeechRecognition||window.webkitSpeechRecognition)){
                btn.disabled=false; btn.textContent='🎤 Speak';
                if(!btn.__androidBound){btn.__androidBound=true;btn.addEventListener('click',function(){
                  if(btn.classList.contains('listening')){AndroidSpeech.stopListening();return;}
                  AndroidSpeech.startListening();
                });}
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class AndroidBridge {
        @android.webkit.JavascriptInterface
        fun startListening() {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else startNativeSpeechInternal()
            }
        }

        @android.webkit.JavascriptInterface
        fun stopListening() {
            runOnUiThread { speechRecognizer?.stopListening() }
        }

        @android.webkit.JavascriptInterface
        fun speak(text: String, language: String, rate: Double, pitch: Double) {
            runOnUiThread {
                if (!ttsReady) return@runOnUiThread
                tts?.language = if (language.startsWith("hi", true)) Locale("hi", "IN") else Locale.US
                tts?.setSpeechRate(rate.toFloat().coerceIn(0.5f, 2f))
                tts?.setPitch(pitch.toFloat().coerceIn(0.5f, 2f))
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dekho-${System.currentTimeMillis()}")
                Handler(Looper.getMainLooper()).postDelayed({ webView.evaluateJavascript("window.__dekhoTtsEnd&&window.__dekhoTtsEnd()", null) }, 500)
            }
        }

        @android.webkit.JavascriptInterface
        fun stopSpeaking() { runOnUiThread { tts?.stop() } }
    }

    private fun startNativeSpeechInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notifyJsError("Speech recognition is not available on this device")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setListeningState(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = setListeningState(false)
            override fun onError(error: Int) { setListeningState(false); notifyJsError("Speech recognition error: $error") }
            override fun onResults(results: Bundle?) {
                setListeningState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                val escaped = org.json.JSONObject.quote(text)
                runOnUiThread { webView.evaluateJavascript("window.__dekhoAndroidSpeechResult($escaped)", null) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                val escaped = org.json.JSONObject.quote(text)
                runOnUiThread { webView.evaluateJavascript("window.__dekhoAndroidSpeechResult($escaped)", null) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun setListeningState(listening: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("""
                (function(){var b=document.getElementById('voiceBtn');if(!b)return;
                b.classList.toggle('listening',$listening);b.textContent=${if (listening) "'🛑 Stop Listening'" else "'🎤 Speak'"};})()
            """.trimIndent(), null)
        }
    }

    private fun notifyJsError(message: String) {
        val escaped = org.json.JSONObject.quote(message)
        runOnUiThread { webView.evaluateJavascript("window.__dekhoAndroidSpeechError($escaped)", null) }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
