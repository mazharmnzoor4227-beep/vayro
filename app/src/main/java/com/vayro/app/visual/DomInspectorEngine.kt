package com.vayro.app.visual

import org.json.JSONObject

object DomInspectorEngine {

    const val PREFIX_DOM_ELEMENT = "MH_DOM_ELEMENT:"
    const val PREFIX_CONSOLE = "MH_CONSOLE:"
    const val PREFIX_NETWORK = "MH_NETWORK:"

    fun buildInspectorScript(enabled: Boolean): String {
        return """
            (function() {
                var overlayId = '__mh_inspector_overlay';
                var prevOverlay = document.getElementById(overlayId);
                if (prevOverlay) { prevOverlay.remove(); }

                if (!$enabled) {
                    window.__mh_inspect_active = false;
                    console.log('$PREFIX_CONSOLE' + JSON.stringify({
                        level: 'INFO',
                        message: 'DOM Inspector deactivated',
                        timestamp: Date.now()
                    }));
                    return;
                }

                window.__mh_inspect_active = true;
                
                var overlay = document.createElement('div');
                overlay.id = overlayId;
                overlay.style.position = 'fixed';
                overlay.style.pointerEvents = 'none';
                overlay.style.zIndex = '2147483647';
                overlay.style.border = '2px solid #22c55e';
                overlay.style.backgroundColor = 'rgba(34, 197, 94, 0.15)';
                overlay.style.display = 'none';
                overlay.style.transition = 'all 0.1s ease-out';
                document.body.appendChild(overlay);

                function getSelector(el) {
                    if (!el || el === document.body) return 'body';
                    var path = [];
                    while (el && el.nodeType === Node.ELEMENT_NODE && el !== document.documentElement) {
                        var selector = el.tagName.toLowerCase();
                        if (el.id) {
                            selector += '#' + el.id;
                            path.unshift(selector);
                            break;
                        } else {
                            var sib = el, nth = 1;
                            while (sib = sib.previousElementSibling) {
                                if (sib.tagName.toLowerCase() === selector) nth++;
                            }
                            if (nth > 1) selector += ':nth-of-type(' + nth + ')';
                            path.unshift(selector);
                        }
                        el = el.parentElement;
                    }
                    return path.join(' > ');
                }

                function getAttributes(el) {
                    var attrs = {};
                    if (!el || !el.attributes) return attrs;
                    for (var i = 0; i < el.attributes.length; i++) {
                        var a = el.attributes[i];
                        if (a.name !== 'style') {
                            attrs[a.name] = a.value;
                        }
                    }
                    return attrs;
                }

                function getComputedStyles(el) {
                    var s = window.getComputedStyle(el);
                    return {
                        color: s.color || '',
                        backgroundColor: s.backgroundColor || '',
                        fontSize: s.fontSize || '',
                        fontWeight: s.fontWeight || '',
                        display: s.display || '',
                        position: s.position || '',
                        visibility: s.visibility || '',
                        opacity: s.opacity || '',
                        margin: s.margin || '',
                        padding: s.padding || '',
                        width: s.width || '',
                        height: s.height || ''
                    };
                }

                document.addEventListener('click', function(e) {
                    if (!window.__mh_inspect_active) return;
                    e.preventDefault();
                    e.stopPropagation();

                    var target = e.target;
                    if (!target || target.id === overlayId) return;

                    var r = target.getBoundingClientRect();
                    overlay.style.left = r.left + 'px';
                    overlay.style.top = r.top + 'px';
                    overlay.style.width = r.width + 'px';
                    overlay.style.height = r.height + 'px';
                    overlay.style.display = 'block';

                    var classList = [];
                    if (target.classList) {
                        for (var i = 0; i < target.classList.length; i++) {
                            classList.push(target.classList[i]);
                        }
                    }

                    var payload = {
                        tag: target.tagName ? target.tagName.toLowerCase() : '',
                        id: target.id || null,
                        classes: classList,
                        selector: getSelector(target),
                        innerText: (target.innerText || '').slice(0, 120).trim(),
                        attributes: getAttributes(target),
                        computedStyles: getComputedStyles(target),
                        rect: {
                            left: r.left,
                            top: r.top,
                            width: r.width,
                            height: r.height
                        }
                    };

                    console.log('$PREFIX_DOM_ELEMENT' + JSON.stringify(payload));
                }, true);

                console.log('$PREFIX_CONSOLE' + JSON.stringify({
                    level: 'INFO',
                    message: 'DOM Inspector activated: Tap any element to inspect',
                    timestamp: Date.now()
                }));
            })();
        """.trimIndent()
    }

    fun buildDiagnosticsInterceptorScript(): String {
        return """
            (function() {
                if (window.__mh_diagnostics_installed) return;
                window.__mh_diagnostics_installed = true;

                // 1. Console Override
                var origLog = console.log;
                var origWarn = console.warn;
                var origError = console.error;
                var origInfo = console.info;

                function formatArgs(args) {
                    var str = [];
                    for (var i = 0; i < args.length; i++) {
                        var a = args[i];
                        if (typeof a === 'string') {
                            str.push(a);
                        } else {
                            try { str.push(JSON.stringify(a)); } catch (_) { str.push(String(a)); }
                        }
                    }
                    return str.join(' ');
                }

                console.log = function() {
                    var text = formatArgs(arguments);
                    origLog.apply(console, arguments);
                    if (!text.startsWith('MH_')) {
                        origLog('$PREFIX_CONSOLE' + JSON.stringify({ level: 'INFO', message: text, timestamp: Date.now() }));
                    }
                };

                console.info = function() {
                    var text = formatArgs(arguments);
                    origInfo.apply(console, arguments);
                    if (!text.startsWith('MH_')) {
                        origLog('$PREFIX_CONSOLE' + JSON.stringify({ level: 'INFO', message: text, timestamp: Date.now() }));
                    }
                };

                console.warn = function() {
                    var text = formatArgs(arguments);
                    origWarn.apply(console, arguments);
                    origLog('$PREFIX_CONSOLE' + JSON.stringify({ level: 'WARN', message: text, timestamp: Date.now() }));
                };

                console.error = function() {
                    var text = formatArgs(arguments);
                    origError.apply(console, arguments);
                    origLog('$PREFIX_CONSOLE' + JSON.stringify({ level: 'ERROR', message: text, timestamp: Date.now() }));
                };

                // 2. Global Error Handling
                window.addEventListener('error', function(e) {
                    var msg = e.message || 'Script error';
                    var src = e.filename || null;
                    var line = e.lineno || null;
                    origLog('$PREFIX_CONSOLE' + JSON.stringify({
                        level: 'ERROR',
                        message: msg,
                        source: src,
                        lineNumber: line,
                        timestamp: Date.now()
                    }));
                });

                window.addEventListener('unhandledrejection', function(e) {
                    var reason = e.reason ? (e.reason.message || String(e.reason)) : 'Unhandled Promise rejection';
                    origLog('$PREFIX_CONSOLE' + JSON.stringify({
                        level: 'ERROR',
                        message: 'Unhandled Promise Rejection: ' + reason,
                        timestamp: Date.now()
                    }));
                });

                // 3. Network Fetch Monkey-patching
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var start = Date.now();
                        var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                        var method = (init && init.method) ? init.method.toUpperCase() : 'GET';
                        return origFetch.apply(window, arguments).then(function(res) {
                            var duration = Date.now() - start;
                            if (!res.ok) {
                                origLog('$PREFIX_NETWORK' + JSON.stringify({
                                    method: method,
                                    url: url,
                                    statusCode: res.status,
                                    statusText: res.statusText,
                                    durationMs: duration,
                                    isFailed: true,
                                    timestamp: Date.now()
                                }));
                            }
                            return res;
                        }).catch(function(err) {
                            var duration = Date.now() - start;
                            origLog('$PREFIX_NETWORK' + JSON.stringify({
                                method: method,
                                url: url,
                                error: err ? err.message : 'Network error',
                                durationMs: duration,
                                isFailed: true,
                                timestamp: Date.now()
                            }));
                            throw err;
                        });
                    };
                }

                // 4. XMLHttpRequest Monkey-patching
                if (window.XMLHttpRequest) {
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__mh_method = method ? method.toUpperCase() : 'GET';
                        this.__mh_url = url;
                        this.__mh_start = Date.now();
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        var self = this;
                        this.addEventListener('load', function() {
                            if (self.status >= 400) {
                                var duration = Date.now() - (self.__mh_start || Date.now());
                                origLog('$PREFIX_NETWORK' + JSON.stringify({
                                    method: self.__mh_method || 'GET',
                                    url: self.__mh_url || '',
                                    statusCode: self.status,
                                    statusText: self.statusText,
                                    durationMs: duration,
                                    isFailed: true,
                                    timestamp: Date.now()
                                }));
                            }
                        });
                        this.addEventListener('error', function() {
                            var duration = Date.now() - (self.__mh_start || Date.now());
                            origLog('$PREFIX_NETWORK' + JSON.stringify({
                                method: self.__mh_method || 'GET',
                                url: self.__mh_url || '',
                                error: 'XHR network failed',
                                durationMs: duration,
                                isFailed: true,
                                timestamp: Date.now()
                            }));
                        });
                        return origSend.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
    }

    fun parseConsoleMessage(rawMessage: String): ConsoleLogEntry? {
        if (!rawMessage.startsWith(PREFIX_CONSOLE)) return null
        val payload = rawMessage.removePrefix(PREFIX_CONSOLE).trim()
        return runCatching {
            val json = JSONObject(payload)
            ConsoleLogEntry.fromJson(json)
        }.getOrNull()
    }

    fun parseNetworkMessage(rawMessage: String): NetworkLogEntry? {
        if (!rawMessage.startsWith(PREFIX_NETWORK)) return null
        val payload = rawMessage.removePrefix(PREFIX_NETWORK).trim()
        return runCatching {
            val json = JSONObject(payload)
            NetworkLogEntry.fromJson(json)
        }.getOrNull()
    }

    fun parseDomElementMessage(rawMessage: String): DomElementInfo? {
        if (!rawMessage.startsWith(PREFIX_DOM_ELEMENT)) return null
        val payload = rawMessage.removePrefix(PREFIX_DOM_ELEMENT).trim()
        return runCatching {
            val json = JSONObject(payload)
            DomElementInfo.fromJson(json)
        }.getOrNull()
    }
}
