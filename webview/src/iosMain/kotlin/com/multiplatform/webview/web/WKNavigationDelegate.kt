package com.multiplatform.webview.web

import com.multiplatform.webview.request.RequestData
import com.multiplatform.webview.request.RequestResult
import com.multiplatform.webview.util.KLogger
import platform.Foundation.NSError
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * Created By Kevin Zou On 2023/9/13
 */

/**
 * Navigation delegate for the WKWebView
 */
@Suppress("CONFLICTING_OVERLOADS")
class WKNavigationDelegate(
    private val state: WebViewState,
    private val navigator: WebViewNavigator,
) : NSObject(), WKNavigationDelegateProtocol {
    /**
     * Called when the web view begins to receive web content.
     */
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: WKNavigation?,
    ) {
        state.loadingState = LoadingState.Loading(0f)
        state.lastLoadedUrl = webView.URL.toString()
        state.errorsForCurrentRequest.clear()
        KLogger.info {
            "didStartProvisionalNavigation"
        }
    }

    /**
     * Called when the web view receives a server redirect.
     */
    override fun webView(
        webView: WKWebView,
        didCommitNavigation: WKNavigation?,
    ) {
        @Suppress("ktlint:standard:max-line-length")
        val script = "var meta = document.createElement('meta');meta.setAttribute('name', 'viewport');meta.setAttribute('content', 'width=device-width, initial-scale=${state.webSettings.zoomLevel}, maximum-scale=10.0, minimum-scale=0.1,user-scalable=yes');document.getElementsByTagName('head')[0].appendChild(meta);"
        webView.evaluateJavaScript(script) { _, _ -> }
        KLogger.info { "didCommitNavigation" }
    }

    /**
     * Called when the web view finishes loading.
     */
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        state.pageTitle = webView.title
        state.lastLoadedUrl = webView.URL.toString()
        state.loadingState = LoadingState.Finished
        navigator.canGoBack = webView.canGoBack
        navigator.canGoForward = webView.canGoForward
        KLogger.info { "didFinishNavigation" }
    }

    /**
     * Called when the web view fails to load content.
     */
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        KLogger.e {
            "WebView Loading Failed with error: ${withError.localizedDescription}"
        }
        state.errorsForCurrentRequest.add(
            WebViewError(
                withError.code.toInt(),
                withError.localizedDescription,
            ),
        )
        KLogger.e {
            "didFailNavigation"
        }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        KLogger.d {
            "WebView decidePolicyForNavigationAction: $decidePolicyForNavigationAction"
        }

        val data =
            RequestData(
                url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: "",
                isForMainFrame = decidePolicyForNavigationAction.targetFrame != null,
                isRedirect = false,
                method = "GET",
                requestHeaders = emptyMap(),
            )

        val result = navigator.requestInterceptor(data)
        KLogger.d {
            "WKWebView request interceptor result: $result"
        }

        when (result) {
            is RequestResult.Allow -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            is RequestResult.AllowInMainFrame -> {
                val request = decidePolicyForNavigationAction.request
                webView.loadRequest(request)
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            is RequestResult.Modify -> {
                // TODO: Modify the request
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            is RequestResult.Reject -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
        }
    }
}
