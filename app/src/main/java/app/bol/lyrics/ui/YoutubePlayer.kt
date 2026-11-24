package app.bol.lyrics.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class PlayerController {
    internal var web: WebView? = null
    fun play() = run("player.playVideo()")
    fun pause() = run("player.pauseVideo()")
    fun seek(seconds: Float) = run("player.seekTo($seconds,true)")
    private fun run(js: String) {
        web?.post { web?.evaluateJavascript("try{$js}catch(e){}", null) }
    }
}

private class Bridge(
    val onTime: (Float, Float) -> Unit,
    val onState: (Int) -> Unit,
    val onError: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun time(t: Double, d: Double) {
        main.post { onTime(t.toFloat(), d.toFloat()) }
    }

    @JavascriptInterface
    fun state(s: Int) {
        main.post { onState(s) }
    }

    @JavascriptInterface
    fun error(code: Int) {
        main.post { onError(code) }
    }
}

private const val ORIGIN = "https://bol-player.local"

private fun html(videoId: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$videoId',
    playerVars:{playsinline:1,rel:0,modestbranding:1,enablejsapi:1,origin:'$ORIGIN'},
    events:{onStateChange:function(e){Bol.state(e.data)},onError:function(e){Bol.error(e.data)}}});
}
setInterval(function(){
  if(player&&player.getCurrentTime){Bol.time(player.getCurrentTime(),player.getDuration()||0)}
},200);
</script></body></html>
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubePlayer(
    videoId: String,
    controller: PlayerController,
    onTime: (Float, Float) -> Unit,
    onState: (Int) -> Unit,
    onError: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                addJavascriptInterface(Bridge(onTime, onState, onError), "Bol")
                // Page ko asli https origin se serve karte hain taaki YouTube ko Referer mile.
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.toString().startsWith("$ORIGIN/player")) {
                            return WebResourceResponse("text/html", "utf-8", html(videoId).byteInputStream())
                        }
                        return null
                    }
                }
                loadUrl("$ORIGIN/player")
                controller.web = this
            }
        },
        onRelease = {
            if (controller.web === it) controller.web = null
            it.destroy()
        },
    )
}
