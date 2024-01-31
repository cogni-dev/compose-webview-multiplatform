package com.multiplatform.webview.web

import com.multiplatform.webview.request.RequestData
import com.multiplatform.webview.request.RequestResult
import com.multiplatform.webview.util.KLogger
import dev.datlag.kcef.KCEFBrowser
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.callback.CefCallback
import org.cef.handler.CefCookieAccessFilter
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.cef.network.CefURLRequest
import org.cef.security.CefSSLInfo
import kotlin.math.abs
import kotlin.math.ln

/**
 * Created By Kevin Zou On 2023/9/12
 */
internal fun CefBrowser.getCurrentUrl(): String? {
    return this.url
}

internal fun CefBrowser.addDisplayHandler(state: WebViewState) {
    this.client.addDisplayHandler(
        object : CefDisplayHandler {
            override fun onAddressChange(
                browser: CefBrowser?,
                frame: CefFrame?,
                url: String?,
            ) {
                KLogger.d { "onAddressChange: $url" }
                state.lastLoadedUrl = getCurrentUrl()
            }

            override fun onTitleChange(
                browser: CefBrowser?,
                title: String?,
            ) {
                // https://magpcss.org/ceforum/viewtopic.php?t=11491
                // https://github.com/KevinnZou/compose-webview-multiplatform/issues/46
                val givenZoomLevel = state.webSettings.zoomLevel
                val realZoomLevel =
                    if (givenZoomLevel >= 0.0) {
                        ln(abs(givenZoomLevel)) / ln(1.2)
                    } else {
                        -ln(abs(givenZoomLevel)) / ln(1.2)
                    }
                KLogger.d { "titleProperty: $title" }
                zoomLevel = realZoomLevel
                state.pageTitle = title
            }

            override fun onTooltip(
                browser: CefBrowser?,
                text: String?,
            ): Boolean {
                return false
            }

            override fun onStatusMessage(
                browser: CefBrowser?,
                value: String?,
            ) {
            }

            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int,
            ): Boolean {
                return false
            }

            override fun onCursorChange(
                browser: CefBrowser?,
                cursorType: Int,
            ): Boolean {
                return false
            }
        },
    )
}

internal fun KCEFBrowser.addRequestHandler(
    state: WebViewState,
    navigator: WebViewNavigator
) {

    client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser,
                frame: CefFrame,
                request: CefRequest,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String,
                disableDefaultHandling: BoolRef
            ) = object : CefResourceRequestHandlerAdapter() {
                override fun onBeforeResourceLoad(
                    browser: CefBrowser,
                    frame: CefFrame,
                    request: CefRequest
                ): Boolean {
                    val data = RequestData(
                        url = request.url.toString(),
                        isForMainFrame = frame.isMain,
                        isRedirect = false,
                        method = request.method,
                        requestHeaders = mutableMapOf<String, String>().also {
                            request.getHeaderMap(
                                it
                            )
                        }
                    )

                    val result =
                        if (request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME) navigator.requestInterceptor(
                            data
                        ) else return false
                    KLogger.d { "shouldOverrideUrlLoading: load new: $result" }

                    return when (result) {
                        RequestResult.Allow -> false
                        is RequestResult.Modify -> {
                            KLogger.d { "State is ${state.webView}" }

                            request.url = result.url
                            request.setHeaderMap(result.additionalHeaders)
                            false
                        }

                        RequestResult.Reject -> true
                    }
                }
            }.also { KLogger.d { "Created a handler" } }
        })
}

internal fun CefBrowser.addLoadListener(
    state: WebViewState,
    navigator: WebViewNavigator,
) {

    this.client.addLoadHandler(
        object : CefLoadHandler {
            private var lastLoadedUrl = "null"

            override fun onLoadingStateChange(
                browser: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                KLogger.d {
                    "onLoadingStateChange: $url, $isLoading $canGoBack $canGoForward"
                }
                if (isLoading) {
                    state.loadingState = LoadingState.Initializing
                } else {
                    state.loadingState = LoadingState.Finished
                    if (url != null && url != lastLoadedUrl) {
                        state.webView?.injectJsBridge()
                        lastLoadedUrl = url
                    }
                }
                navigator.canGoBack = canGoBack
                navigator.canGoForward = canGoForward
            }

            override fun onLoadStart(
                browser: CefBrowser?,
                frame: CefFrame?,
                transitionType: CefRequest.TransitionType?,
            ) {
                KLogger.d { "Load Start ${browser?.url}" }
                lastLoadedUrl = "null" // clean last loaded url for reload to work
                state.loadingState = LoadingState.Loading(0F)
                state.errorsForCurrentRequest.clear()
            }

            override fun onLoadEnd(
                browser: CefBrowser?,
                frame: CefFrame?,
                httpStatusCode: Int,
            ) {
                KLogger.d { "Load End ${browser?.url}" }
                state.loadingState = LoadingState.Finished
                navigator.canGoBack = canGoBack()
                navigator.canGoBack = canGoForward()
                state.lastLoadedUrl = getCurrentUrl()
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                state.loadingState = LoadingState.Finished
                KLogger.e {
                    "Failed to load url: ${failedUrl} $errorText $errorCode $failedUrl"
                }
                state.errorsForCurrentRequest.add(
                    WebViewError(
                        code = errorCode?.code ?: 404,
                        description = "Failed to load url: ${failedUrl}\n$errorText",
                    ),
                )
            }
        },
    )
}
