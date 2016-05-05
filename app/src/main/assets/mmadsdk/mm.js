/*! Ad-SDK-JS-Bridge - 1.0.0 - 1580ec2 - 2015-06-15 */
(function(window) {
    var GENERIC_NAMESPACE = null;
    function capitalizeFirstLetter(string) {
        if (string) {
            return string.charAt(0).toUpperCase() + string.slice(1);
        } else {
            return "";
        }
    }
    function copyObject(object) {
        var newObject = {};
        Object.keys(object).forEach(function(key) {
            newObject[key] = object[key];
        });
        return newObject;
    }
    var getIframe = function() {
        var iframe;
        return function() {
            if (!iframe) {
                iframe = document.createElement("iframe");
                iframe.style.display = "none";
                document.body.appendChild(iframe);
            }
            return iframe;
        };
    }();
    function callNativeLayer(apiModule, action, parameters) {
        log.debug("Calling into the native layer with apiModule %s, action %s, and parameters %s", apiModule, action, parameters);
        var i;
        var injectedNamespace = window["MmInjectedFunctions" + capitalizeFirstLetter(apiModule)];
        if (injectedNamespace) {
            log.debug("Selected to communicate with native layer using an injected bridge function");
            var parameterMap = {};
            if (parameters && parameters.length > 0) {
                for (i = 0; i < parameters.length; i++) {
                    if (parameters[i].value !== null) {
                        parameterMap[parameters[i].name] = parameters[i].value;
                    }
                }
            }
            if (injectedNamespace[action]) {
                injectedNamespace[action](JSON.stringify(parameterMap));
            } else {
                log.error("The action %s is not available", action);
            }
        } else {
            log.debug("Selected to communicate with native layer using an iframe");
            var scheme = apiModule ? apiModule : "mmsdk";
            var url = scheme + "://" + action;
            if (parameters && parameters.length > 0) {
                var paramsAddedToUrl = 0;
                var value;
                for (i = 0; i < parameters.length; i++) {
                    value = parameters[i].value;
                    if (value !== null && typeof value == "object") {
                        value = JSON.stringify(value);
                    }
                    if (value !== null) {
                        if (paramsAddedToUrl === 0) {
                            url += "?";
                        } else {
                            url += "&";
                        }
                        url += encodeURIComponent(parameters[i].name) + "=" + encodeURIComponent(value);
                        paramsAddedToUrl++;
                    }
                }
            }
            iframe = document.createElement("iframe");
            iframe.style.display = "none";
            iframe.src = url;
            document.body.appendChild(iframe);
            document.body.removeChild(iframe);
        }
        log.debug("Bottom of callNativeLayer");
    }
    var hasCommonLoaded = !!window.MmJsBridge;
    if (!hasCommonLoaded) {
        (function() {
            function getPopupFunction(name) {
                return function() {
                    log.error("Calling the function %s is not allowed", name);
                };
            }
            window.mmHiddenAlert = window.alert;
            Object.defineProperties(window, {
                alert: {
                    value: getPopupFunction("alert")
                },
                confirm: {
                    value: getPopupFunction("confirm")
                },
                prompt: {
                    value: getPopupFunction("prompt")
                }
            });
        })();
        window.MmJsBridge = {};
        (function() {
            var LOG_LEVELS = {
                ERROR: {
                    text: "ERROR",
                    level: 0
                },
                WARN: {
                    text: "WARN",
                    level: 1
                },
                INFO: {
                    text: "INFO",
                    level: 2
                },
                DEBUG: {
                    text: "DEBUG",
                    level: 3
                }
            };
            var $logLevel = LOG_LEVELS.INFO;
            var loggingSupported = window.console && console.log;
            function genericLog(args, logLevel) {
                if (loggingSupported && logLevel.level <= $logLevel.level) {
                    var message = args[0];
                    if (args.length > 1) {
                        for (var i = 1; i < args.length; i++) {
                            var replacement = args[i];
                            if (!exists(replacement)) {
                                replacement = "";
                            } else if (isObject(replacement)) {
                                replacement = JSON.stringify(replacement);
                            } else if (isFunction(replacement)) {
                                replacement = replacement.toString();
                            }
                            message = message.replace("%s", replacement);
                        }
                    }
                    console.log(logLevel.text + ": " + message);
                }
            }
            MmJsBridge.logging = {
                setLogLevel: function(logLevelString) {
                    if (LOG_LEVELS.hasOwnProperty(logLevelString)) {
                        $logLevel = LOG_LEVELS[logLevelString];
                    }
                },
                log: {
                    error: function() {
                        genericLog(arguments, LOG_LEVELS.ERROR);
                    },
                    warn: function() {
                        genericLog(arguments, LOG_LEVELS.WARN);
                    },
                    info: function() {
                        genericLog(arguments, LOG_LEVELS.INFO);
                    },
                    debug: function() {
                        genericLog(arguments, LOG_LEVELS.DEBUG);
                    }
                }
            };
        })();
        MmJsBridge.callbackManager = function() {
            var callbacks = [];
            return {
                callCallback: function(callbackId) {
                    log.debug("MmJsBridge.callbackManager.callCallback called with callbackId %s", callbackId);
                    var callbackIdNum = parseInt(callbackId, 10);
                    if (isNumber(callbackIdNum) && !isNaN(callbackIdNum) && callbackIdNum >= 0 && callbackIdNum < callbacks.length) {
                        var callback = callbacks[callbackIdNum];
                        var argsArray = Array.prototype.slice.call(arguments, 1);
                        log.debug("Found callback. Calling %s with arguments %s", callback, argsArray);
                        callback.apply(window, argsArray);
                    } else {
                        log.warn("Unable to call callback with id %s because it could not be found", callbackId);
                    }
                    log.debug("Bottom of MmJsBridge.callbackManager.callCallback");
                },
                generateCallbackId: function(callback) {
                    var callbackId;
                    var index = callbacks.indexOf(callback);
                    if (index >= 0) {
                        callbackId = index;
                    } else {
                        callbacks.push(callback);
                        callbackId = callbacks.length - 1;
                    }
                    log.debug("Callback id %s for callback %s", callbackId, callback);
                    return callbackId;
                }
            };
        }();
    }
    var log = MmJsBridge.logging.log;
    function generateCallbackId(callback) {
        return MmJsBridge.callbackManager.generateCallbackId(callback);
    }
    function generateParameterObject(name, value) {
        return {
            name: name,
            value: defined(value) ? value : null
        };
    }
    function generateParameterArrayFromObject(obj) {
        var parameterArray = [];
        Object.keys(obj).forEach(function(key) {
            parameterArray.push(generateParameterObject(key, obj[key]));
        });
        return parameterArray;
    }
    function defined(variable) {
        return variable !== undefined;
    }
    function is(variable, type) {
        return typeof variable == type;
    }
    function isNumber(variable) {
        return is(variable, "number");
    }
    function isBoolean(variable) {
        return is(variable, "boolean");
    }
    function isString(variable) {
        return is(variable, "string");
    }
    function isFunction(variable) {
        return is(variable, "function");
    }
    function isObject(variable) {
        return is(variable, "object");
    }
    function exists(param) {
        return param !== undefined && param !== null && param !== "";
    }
    var ListenerManager = function() {
        this._listeners = {};
    };
    ListenerManager.prototype = {
        constructor: ListenerManager,
        addEventListener: function(event, listener) {
            var that = this;
            if (!that._listeners[event]) {
                that._listeners[event] = [];
            }
            if (that._listeners[event].indexOf(listener) < 0) {
                that._listeners[event].push(listener);
            }
        },
        removeEventListener: function(event, listener) {
            var that = this;
            if (!that._listeners[event]) {
                return;
            }
            if (!defined(listener)) {
                delete that._listeners[event];
                return;
            }
            var index = that._listeners[event].indexOf(listener);
            if (index >= 0) {
                that._listeners[event].splice(index, 1);
            }
        },
        callListeners: function(event, args) {
            if (this._listeners[event]) {
                this._listeners[event].forEach(function(listener) {
                    listener.apply(null, args);
                });
            }
        }
    };
    var MMJS_API_MODULE = "mmjs";
    window.MMJS = {};
    MMJS.device = {
        openInBrowser: function(url, callback) {
            log.debug("MMJS.device.openInBrowser called with url %s and callback %s", url, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "openInBrowser", [ generateParameterObject("url", url), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.openInBrowser");
        },
        isSchemeAvailable: function(name, callback) {
            log.debug("MMJS.device.isSchemeAvailable called with name %s and callback %s", name, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "isSchemeAvailable", [ generateParameterObject("name", name), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of isSchemeAvailable");
        },
        isPackageAvailable: function(name, callback) {
            log.debug("MMJS.device.isPackageAvailable called with name %s and callback %s", name, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "isPackageAvailable", [ generateParameterObject("name", name), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.isPackageAvailable");
        },
        call: function(number, callback) {
            log.debug("MMJS.device.call called with number %s and callback %s", number, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "call", [ generateParameterObject("number", number), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.call");
        },
        composeSms: function(recipients, message, callback) {
            log.debug("MMJS.device.composeSms with recipients %s, message %s, and callback %s", recipients, message, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "sms", [ generateParameterObject("recipients", recipients), generateParameterObject("message", message), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.composeSms");
        },
        composeEmail: function(options, callback) {
            log.debug("MMJS.device.composeEmail called with options %s and callback %s", options, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "email", [ generateParameterObject("recipients", options.recipients), generateParameterObject("subject", options.subject), generateParameterObject("message", options.message), generateParameterObject("type", options.type), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.composeEmail");
        },
        openMap: function() {
            log.debug("MMJS.device.openMap called with arguments %s", arguments);
            function getCallbackId(args, expectedCallbackIndex) {
                return args.length > expectedCallbackIndex && isFunction(args[expectedCallbackIndex]) ? generateCallbackId(args[expectedCallbackIndex]) : null;
            }
            var callbackId;
            var args = arguments;
            if (args.length > 0 && isString(args[0])) {
                var address = args[0];
                log.debug("Trying to use address to open map. Address: %s", address);
                callbackId = getCallbackId(args, 1);
                callNativeLayer(MMJS_API_MODULE, "openMap", [ generateParameterObject("address", address), generateParameterObject("callbackId", callbackId) ]);
            } else if (args.length > 1 && isNumber(args[0]) && isNumber(args[1])) {
                var latitude = args[0];
                var longitude = args[1];
                log.debug("Trying to use lat and long to open map. Lat: %s, Long: %s", latitude, longitude);
                callbackId = getCallbackId(args, 2);
                callNativeLayer(MMJS_API_MODULE, "openMap", [ generateParameterObject("latitude", latitude), generateParameterObject("longitude", longitude), generateParameterObject("callbackId", callbackId) ]);
            }
            log.debug("Bottom of MMJS.device.openMap");
        },
        openAppStore: function() {
            log.debug("MMJS.device.openAppStore called with arguments %s", arguments);
            var appId, affiliateId, campaignId, callbackId;
            var args = arguments;
            if (args.length > 0) {
                appId = args[0];
            }
            if (args.length > 1 && isFunction(args[1])) {
                callbackId = generateCallbackId(args[1]);
            } else {
                if (args.length > 1) {
                    affiliateId = args[1];
                }
                if (args.length > 2) {
                    campaignId = args[2];
                }
                if (args.length > 3 && isFunction(args[3])) {
                    callbackId = generateCallbackId(args[3]);
                }
            }
            log.debug("appId: %s, affiliateId: %s, campaignId: %s", appId, affiliateId, campaignId);
            callNativeLayer(MMJS_API_MODULE, "openAppStore", [ generateParameterObject("appId", appId), generateParameterObject("affiliateId", affiliateId), generateParameterObject("campaignId", campaignId), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.openAppStore");
        },
        getLocation: function(callback) {
            log.debug("MMJS.device.getLocation called with callback %s", callback);
            var callbackId = generateCallbackId(callback);
            callNativeLayer(MMJS_API_MODULE, "location", [ generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.device.getLocation");
        }
    };
    MMJS.media = {
        isSourceTypeAvailable: function(sourceType, callback) {
            log.debug("MMJS.media.isSourceTypeAvailable called with sourceType %s and callback %s", sourceType, callback);
            var callbackId = generateCallbackId(callback);
            callNativeLayer(MMJS_API_MODULE, "isSourceTypeAvailable", [ generateParameterObject("sourceType", sourceType), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.media.isSourceTypeAvailable");
        },
        getAvailableSourceTypes: function(callback) {
            log.debug("MMJS.media.getAvailableSourceTypes called with callback %s", callback);
            var callbackId = generateCallbackId(callback);
            callNativeLayer(MMJS_API_MODULE, "getAvailableSourceTypes", [ generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.media.getAvailableSourceTypes");
        },
        getPictureFromPhotoLibrary: function(size, callback) {
            log.debug("MMJS.media.getPictureFromPhotoLibrary called with size %s and callback %s", size, callback);
            var callbackId = generateCallbackId(callback);
            callNativeLayer(MMJS_API_MODULE, "getPictureFromPhotoLibrary", [ generateParameterObject("size", size), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.media.getPictureFromPhotoLibrary");
        },
        openCamera: function(preferredCamera, size, callback) {
            log.debug("MMJS.media.openCamera called with preferredCamera %s, size %s, and callback %s", preferredCamera, size, callback);
            var callbackId = generateCallbackId(callback);
            callNativeLayer(MMJS_API_MODULE, "openCamera", [ generateParameterObject("preferredCamera", preferredCamera), generateParameterObject("size", size), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.media.openCamera");
        },
        savePictureToPhotoLibrary: function() {
            log.debug("MMJS.media.savePictureToPhotoLibrary called with arguments %s", arguments);
            var url, name, description, callbackId;
            var args = arguments;
            if (args.length > 0) {
                url = args[0];
            }
            if (args.length > 1 && isFunction(args[1])) {
                callbackId = generateCallbackId(args[1]);
            } else {
                if (args.length > 1) {
                    name = args[1];
                }
                if (args.length > 2) {
                    description = args[2];
                }
                if (args.length > 3 && isFunction(args[3])) {
                    callbackId = generateCallbackId(args[3]);
                }
            }
            log.debug("url: %s, name: %s, description %s", url, name, description);
            callNativeLayer(MMJS_API_MODULE, "savePictureToPhotoLibrary", [ generateParameterObject("url", url), generateParameterObject("name", name), generateParameterObject("description", description), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.media.savePictureToPhotoLibrary");
        }
    };
    MMJS.calendar = {
        addEvent: function(options, callback) {
            log.debug("MMJS.calendar.addEvent called with options %s and callback %s", options, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "addCalendarEvent", [ generateParameterObject("options", options), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.calendar.addEvent");
        },
        addReminder: function(options, callback) {
            log.debug("MMJS.calendar.addReminder called with options %s and callback %s", options, callback);
            var callbackId = isFunction(callback) ? generateCallbackId(callback) : null;
            callNativeLayer(MMJS_API_MODULE, "addReminder", [ generateParameterObject("options", options), generateParameterObject("callbackId", callbackId) ]);
            log.debug("Bottom of MMJS.calendar.addReminder");
        }
    };
    MMJS.notification = {
        vibrate: function(pattern, onStart, onFinish) {
            log.debug("MMJS.notification.vibrate called with pattern %s, onStart %s, and onFinish %s", pattern, onStart, onFinish);
            var onStartCallbackId = isFunction(onStart) ? generateCallbackId(onStart) : null;
            var onFinishCallbackId = isFunction(onFinish) ? generateCallbackId(onFinish) : null;
            callNativeLayer(MMJS_API_MODULE, "vibrate", [ generateParameterObject("pattern", pattern), generateParameterObject("onStartCallbackId", onStartCallbackId), generateParameterObject("onFinishCallbackId", onFinishCallbackId) ]);
            log.debug("Bottom of MMJS.notification.vibrate");
        }
    };
    (function() {
        var INLINE_VIDEO_API_MODULE = "inlineVideo";
        var STATES = {
            IDLE: "idle",
            LOADING: "loading",
            READY_TO_START: "readyToStart",
            PLAYING: "playing",
            PAUSED: "paused",
            COMPLETE: "complete",
            STOPPED: "stopped",
            REMOVED: "removed"
        };
        var EVENTS = {
            STATE_CHANGE: "stateChange",
            DURATION_CHANGE: "durationChange",
            REPOSITION: "reposition",
            EXPAND: "expand",
            COLLAPSE: "collapse",
            UPDATE_VIDEO_URL: "updateVideoURL",
            ERROR: "error",
            MUTE: "mute",
            SEEK: "seek",
            TIME_UDPATE: "timeUpdate",
            CLICK: "click"
        };
        MMJS.InlineVideo = function(options, callback) {
            log.debug("MMJS.InlineVideo constructor called with options %s and callback %s", options, callback);
            if (!exists(options.width) || !exists(options.height) || !exists(options.x) || !exists(options.y) || !exists(options.url)) {
                log.error("Not all required values provided to InlineVideo constructor. width, height, x, and y are required.");
                return;
            }
            var that = this;
            var $videoId;
            var $listenerManager = new ListenerManager();
            var $duration;
            var $url = options.url;
            var $state = "idle";
            var $position = {
                width: options.width,
                height: options.height,
                x: options.x,
                y: options.y
            };
            var $muted = !!options.muted;
            var $expanded = false;
            var $callbackId = options.callbackId = MmJsBridge.callbackManager.generateCallbackId(function(videoId, event) {
                if (!$videoId) {
                    $videoId = videoId;
                    if (callback) {
                        callback(that);
                    }
                }
                var params = Array.prototype.slice.call(arguments, 2);
                handleInlineVideoEvent(event, params);
            });
            Object.defineProperty(that, "duration", {
                get: function() {
                    log.debug("Returning duration of %s", $duration);
                    return $duration;
                }
            });
            Object.defineProperty(that, "url", {
                get: function() {
                    log.debug("Returning url of %s", $url);
                    return $url;
                }
            });
            Object.defineProperty(that, "state", {
                get: function() {
                    log.debug("Returning state of %s", $state);
                    return $state;
                }
            });
            Object.defineProperty(that, "position", {
                get: function() {
                    log.debug("Returning position of %s", $position);
                    return copyObject($position);
                }
            });
            Object.defineProperty(that, "muted", {
                get: function() {
                    log.debug("Returning muted of %s", $muted);
                    return $muted;
                }
            });
            Object.defineProperty(that, "expanded", {
                get: function() {
                    log.debug("Returning expanded of %s", $expanded);
                    return $expanded;
                }
            });
            function handleInlineVideoEvent(event, params) {
                switch (event) {
                  case EVENTS.STATE_CHANGE:
                    var newState = params[0];
                    if (newState == $state || $state == STATES.REMOVED) {
                        return;
                    }
                    $state = newState;
                    break;

                  case EVENTS.DURATION_CHANGE:
                    var newDuration = params[0];
                    if (newDuration == $duration) {
                        return;
                    }
                    $duration = newDuration;
                    break;

                  case EVENTS.REPOSITION:
                    var newPosition = {
                        width: params[0],
                        height: params[1],
                        x: params[2],
                        y: params[3]
                    };
                    if (newPosition.width == $position.width && newPosition.height == $position.height && newPosition.x == $position.x && newPosition.y == $position.y) {
                        return;
                    }
                    $position = newPosition;
                    break;

                  case EVENTS.UPDATE_VIDEO_URL:
                    var newURL = params[0];
                    if (newURL == $url) {
                        return;
                    }
                    $url = newURL;
                    break;

                  case EVENTS.MUTE:
                    var muted = params[0];
                    if (muted == $muted) {
                        return;
                    }
                    $muted = muted;
                    break;
                }
                $listenerManager.callListeners(event, params);
            }
            function inlineVideoCallNativeLayer(action, parameters) {
                if (defined($videoId)) {
                    if (!parameters) {
                        parameters = [];
                    }
                    parameters.unshift(generateParameterObject("videoId", $videoId));
                    callNativeLayer(INLINE_VIDEO_API_MODULE, action, parameters);
                } else {
                    log.warn("You cannot call functions on the video player before the state is changed from idle");
                }
            }
            that.play = function() {
                log.debug("MMJS.InlineVideo.play called");
                inlineVideoCallNativeLayer("play");
            };
            that.pause = function() {
                log.debug("MMJS.InlineVideo.pause called");
                inlineVideoCallNativeLayer("pause");
            };
            that.stop = function() {
                log.debug("MMJS.InlineVideo.stop called");
                inlineVideoCallNativeLayer("stop");
            };
            that.seek = function(time) {
                log.debug("MMJS.InlineVideo.seek called with time %s", time);
                inlineVideoCallNativeLayer("seek", [ generateParameterObject("time", time) ]);
            };
            that.triggerTimeUpdate = function() {
                log.debug("MMJS.InlineVideo.triggerTimeUpdate called");
                inlineVideoCallNativeLayer("triggerTimeUpdate");
            };
            that.expandToFullScreen = function() {
                log.debug("MMJS.InlineVideo.expandToFullScreen called");
                inlineVideoCallNativeLayer("expandToFullScreen");
            };
            that.mute = function() {
                log.debug("MMJS.InlineVideo.mute called");
                inlineVideoCallNativeLayer("setMuted", [ generateParameterObject("mute", true) ]);
            };
            that.unmute = function() {
                log.debug("MMJS.InlineVideo.unmute called");
                inlineVideoCallNativeLayer("setMuted", [ generateParameterObject("mute", false) ]);
            };
            that.remove = function() {
                log.debug("MMJS.InlineVideo.remove called");
                inlineVideoCallNativeLayer("remove");
            };
            that.reposition = function(width, height, x, y) {
                log.debug("MMJS.InlineVideo.reposition called with width %s, height %s, x %s, y %s", width, height, x, y);
                inlineVideoCallNativeLayer("reposition", [ generateParameterObject("width", width), generateParameterObject("height", height), generateParameterObject("x", x), generateParameterObject("y", y) ]);
            };
            that.updateVideoURL = function(url) {
                log.debug("MMJS.InlineVideo.updateVideoURL called with url %s", url);
                inlineVideoCallNativeLayer("updateVideoURL", [ generateParameterObject("url", url) ]);
            };
            that.addEventListener = function(event, listener) {
                log.debug("MMJS.InlineVideo.addEventListener called with event %s and listener %s", event, listener);
                $listenerManager.addEventListener(event, listener);
            };
            that.removeEventListener = function(event, listener) {
                log.debug("MMJS.InlineVideo.removeEventListener called with event %s and listener %s", event, listener);
                $listenerManager.removeEventListener(event, listener);
            };
            that.canPlay = function() {
                return [ STATES.PLAYING, STATES.PAUSED, STATES.READY_TO_START, STATES.COMPLETE ].indexOf($state) !== -1;
            };
            that.invalid = function() {
                return [ STATES.STOPPED, STATES.REMOVED ].indexOf($state) !== -1;
            };
            callNativeLayer(INLINE_VIDEO_API_MODULE, "insert", generateParameterArrayFromObject(options));
            log.debug("Bottom of MMJS.InlineVideo constructor");
        };
    })();
    (function() {
        var VAST_API_MODULE = "vast";
        var $listenerManager = new ListenerManager();
        var $state = "loading";
        var $currentTime = 0;
        var $duration;
        var $webOverlayEnabled = false;
        MmJsBridge.vast = {
            enableWebOverlay: function(currentValues) {
                log.debug("MmJsBridge.vast.enableWebOverlay called with currentValues %s", currentValues);
                $webOverlayEnabled = true;
                if (currentValues) {
                    if (currentValues.state) {
                        $state = currentValues.state;
                    }
                    if (defined(currentValues.currentTime)) {
                        $currentTime = currentValues.currentTime;
                    }
                    if (defined(currentValues.duration)) {
                        $duration = currentValues.duration;
                    }
                }
            },
            setState: function(state) {
                log.debug("MmJsBridge.vast.setState called with state %s", state);
                $state = state;
                $listenerManager.callListeners("stateChange", [ $state ]);
            },
            setCurrentTime: function(currentTime) {
                log.debug("MmJsBridge.vast.setCurrentTime called with currentTime %s", currentTime);
                $currentTime = currentTime;
                $listenerManager.callListeners("timeUpdate", [ $currentTime ]);
            },
            setDuration: function(duration) {
                log.debug("MmJsBridge.vast.setDuration called with duration %s", duration);
                $duration = duration;
                $listenerManager.callListeners("durationChange", [ $duration ]);
            },
            fireErrorEvent: function(message) {
                log.debug("MmJsBridge.vast.fireErrorEvent called with message %s", message);
                $listenerManager.callListeners("error", [ message ]);
            }
        };
        MMJS.vast = {
            get duration() {
                log.debug("MMJS.vast.duration returning %s", $duration);
                return $duration;
            },
            get currentTime() {
                log.debug("MMJS.vast.currentTime returning %s", $currentTime);
                return $currentTime;
            },
            get state() {
                log.debug("MMJS.vast.state returning %s", $state);
                return $state;
            },
            addEventListener: function(event, listener) {
                log.debug("MMJS.vast.addEventListener called with event %s and listener %s", event, listener);
                $listenerManager.addEventListener(event, listener);
            },
            removeEventListener: function(event, listener) {
                log.debug("MMJS.vast.removeEventListener called with event %s and listener %s", event, listener);
                $listenerManager.removeEventListener(event, listener);
            },
            play: function() {
                log.debug("MMJS.vast.play called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "play");
                } else {
                    log.debug("Cannot call MMJS.vast.play because the web overlay is not enabled");
                }
            },
            pause: function() {
                log.debug("MMJS.vast.pause called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "pause");
                } else {
                    log.debug("Cannot call MMJS.vast.pause because the web overlay is not enabled");
                }
            },
            close: function() {
                log.debug("MMJS.vast.close called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "close");
                } else {
                    log.debug("Cannot call MMJS.vast.close because the web overlay is not enabled");
                }
            },
            skip: function() {
                log.debug("MMJS.vast.skip called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "skip");
                } else {
                    log.debug("Cannot call MMJS.vast.skip because the web overlay is not enabled");
                }
            },
            restart: function() {
                log.debug("MMJS.vast.restart called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "restart");
                } else {
                    log.debug("Cannot call MMJS.vast.restart because the web overlay is not enabled");
                }
            },
            seek: function(time) {
                log.debug("MMJS.vast.seek called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "seek", [ generateParameterObject("seekTime", time) ]);
                } else {
                    log.debug("Cannot call MMJS.vast.seek because the web overlay is not enabled");
                }
            },
            triggerTimeUpdate: function() {
                log.debug("MMJS.vast.triggerTimeUpdate called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "triggerTimeUpdate");
                } else {
                    log.debug("Cannot call MMJS.vast.triggerTimeUpdate because the web overlay is not enabled");
                }
            },
            setTimeInterval: function(time) {
                log.debug("MMJS.vast.setTimeInterval called");
                if ($webOverlayEnabled) {
                    callNativeLayer(VAST_API_MODULE, "setTimeInterval", [ generateParameterObject("timeInterval", time) ]);
                } else {
                    log.debug("Cannot call MMJS.vast.setTimeInterval because the web overlay is not enabled");
                }
            }
        };
    })();
    callNativeLayer(GENERIC_NAMESPACE, "fileLoaded", [ generateParameterObject("filename", "mm.js") ]);
})(window);