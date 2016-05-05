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
    (function() {
        var MRAID_API_MODULE = "mraid";
        var PLACEMENT_TYPES = {
            inline: "inline",
            interstitial: "interstitial"
        };
        var STATES = {
            loading: "loading",
            "default": "default",
            hidden: "hidden",
            resized: "resized",
            expanded: "expanded"
        };
        var EVENTS = {
            error: "error",
            ready: "ready",
            sizeChange: "sizeChange",
            stateChange: "stateChange",
            viewableChange: "viewableChange"
        };
        var FORCE_ORIENTATION = {
            portrait: "portrait",
            landscape: "landscape",
            none: "none"
        };
        var CUSTOM_CLOSE_POSITIONS = {
            "top-left": "top-left",
            "top-right": "top-right",
            center: "center",
            "bottom-left": "bottom-left",
            "bottom-right": "bottom-right",
            "top-center": "top-center",
            "bottom-center": "bottom-center"
        };
        var $screenSize = {
            width: window.screen.width,
            height: window.screen.height
        };
        var $placementType = "inline";
        var $maxSize = {
            width: $screenSize.width,
            height: $screenSize.height
        };
        var $state = "loading";
        var $viewable = false;
        var $supports = {};
        var $currentPosition = {
            x: 0,
            y: 0,
            width: $screenSize.width,
            height: $screenSize.height
        };
        var $defaultPosition = {
            x: 0,
            y: 0,
            width: $screenSize.width,
            height: $screenSize.height
        };
        var $listenerManager = new ListenerManager();
        var $expandProperties = {};
        var $orientationProperties = {
            allowOrientationChange: true,
            forceOrientation: "none"
        };
        var $resizeProperties = {};
        function fireSizeChangeEvent() {
            if ($state != STATES.loading) {
                MmJsBridge.mraid.fireMRAIDEvent("sizeChange", $currentPosition.width, $currentPosition.height);
            }
        }
        MmJsBridge.mraid = {
            fireMRAIDEvent: function(event) {
                var params = Array.prototype.slice.call(arguments, 1);
                log.debug("MRAID event fired. Event: %s, Parameters: %s", event, params);
                $listenerManager.callListeners(event, params);
            },
            throwMraidError: function(message, action) {
                MmJsBridge.mraid.fireMRAIDEvent("error", message, action);
                log.error("MRAID error thrown. message: %s. action: %s", message, action);
            },
            setPlacementType: function(newPlacementType) {
                log.debug("MmJsBridge.mraid.setPlacementType called with placementType %s", newPlacementType);
                $placementType = newPlacementType;
            },
            setPositions: function(positions) {
                log.debug("MmJsBridge.mraid.setPositions called with positions %s. Current values of position/size variables are: currentPosition: %s, maxSize: %s, screenSize: %s", positions, $currentPosition, $maxSize, $screenSize);
                var shouldFireSizeChangeEvent = false;
                var newCurrentPosition = positions.currentPosition;
                var newMaxSize = positions.maxSize;
                var newScreenSize = positions.screenSize;
                if (defined(newCurrentPosition) && (newCurrentPosition.width != $currentPosition.width || newCurrentPosition.height != $currentPosition.height || newCurrentPosition.x != $currentPosition.x || newCurrentPosition.y != $currentPosition.y)) {
                    $currentPosition = {
                        width: newCurrentPosition.width,
                        height: newCurrentPosition.height,
                        x: newCurrentPosition.x,
                        y: newCurrentPosition.y
                    };
                    log.debug("Updated current position to %s", $currentPosition);
                    if ($state == STATES.default) {
                        $defaultPosition = copyObject($currentPosition);
                        log.debug("Updated default position to %s", $defaultPosition);
                    }
                    shouldFireSizeChangeEvent = true;
                }
                if (defined(newMaxSize) && (newMaxSize.width != $maxSize.width || newMaxSize.height != $maxSize.height)) {
                    $maxSize = {
                        width: newMaxSize.width,
                        height: newMaxSize.height
                    };
                    log.debug("Updated max size to %s", $maxSize);
                    shouldFireSizeChangeEvent = true;
                }
                if (defined(newScreenSize) && (newScreenSize.width != $screenSize.width || newScreenSize.height != $screenSize.height)) {
                    $screenSize = {
                        width: newScreenSize.width,
                        height: newScreenSize.height
                    };
                    log.debug("Updated screen size to %s", $screenSize);
                    shouldFireSizeChangeEvent = true;
                }
                if (shouldFireSizeChangeEvent) {
                    fireSizeChangeEvent();
                }
                log.debug("Bottom of MmJsBridge.mraid.setPositions");
            },
            setState: function(newState, newCurrentPosition) {
                log.debug("MmJsBridge.mraid.setState called with newState %s and newCurrentPosition %s. Current state is %s and current position is %s", newState, newCurrentPosition, $state, $currentPosition);
                if ($state == newState) {
                    return;
                }
                var sizeChangeEventOccurred = false;
                if (defined(newCurrentPosition) && defined(newCurrentPosition.width) && defined(newCurrentPosition.height) && defined(newCurrentPosition.x) && defined(newCurrentPosition.y)) {
                    var currentPositionChanged = newCurrentPosition.width != $currentPosition.width || newCurrentPosition.height != $currentPosition.height || newCurrentPosition.x != $currentPosition.x || newCurrentPosition.y != $currentPosition.y;
                    var defaultPositionChanged = newState == STATES.default && (newCurrentPosition.width != $defaultPosition.width || newCurrentPosition.height != $defaultPosition.height || newCurrentPosition.x != $defaultPosition.x || newCurrentPosition.y != $defaultPosition.y);
                    if ($state != STATES.loading && (currentPositionChanged || defaultPositionChanged)) {
                        sizeChangeEventOccurred = true;
                    }
                    if (currentPositionChanged) {
                        $currentPosition = {
                            width: newCurrentPosition.width,
                            height: newCurrentPosition.height,
                            x: newCurrentPosition.x,
                            y: newCurrentPosition.y
                        };
                        log.debug("Updated current position to %s", $currentPosition);
                    }
                    if (defaultPositionChanged) {
                        $defaultPosition = {
                            width: newCurrentPosition.width,
                            height: newCurrentPosition.height,
                            x: newCurrentPosition.x,
                            y: newCurrentPosition.y
                        };
                        log.debug("Update default position to %s", $defaultPosition);
                    }
                }
                var fireReadyEvent = $state == STATES.loading;
                log.info("State changing from %s to %s", $state, newState);
                $state = newState;
                if (fireReadyEvent) {
                    MmJsBridge.mraid.fireMRAIDEvent("ready");
                } else {
                    if (sizeChangeEventOccurred) {
                        fireSizeChangeEvent();
                    }
                    MmJsBridge.mraid.fireMRAIDEvent("stateChange", $state);
                }
                log.debug("Bottom of MmJsBridge.mraid.setState");
            },
            setSupports: function(newSupports) {
                log.debug("MmJsBridge.mraid.setSupports called with object %s", newSupports);
                $supports = newSupports;
            },
            setViewable: function(newViewable) {
                log.debug("MmJsBridge.mraid.setViewable called with newViewable %s. Current value of viewable %s", newViewable, $viewable);
                if ($viewable != newViewable) {
                    $viewable = newViewable;
                    if ($state != STATES.loading) {
                        MmJsBridge.mraid.fireMRAIDEvent("viewableChange", $viewable);
                    }
                }
                log.debug("Bottom of MmJsBridge.mraid.setViewable");
            }
        };
        function throwMraidError(message, action) {
            MmJsBridge.mraid.throwMraidError(message, action);
        }
        window.mraid = {
            addEventListener: function(event, listener) {
                log.debug("mraid.addEventListener called with event %s and listener %s", event, listener);
                if (!EVENTS.hasOwnProperty(event)) {
                    var eventsAllowed = Object.keys(EVENTS).map(function(key) {
                        return '"' + key + '"';
                    }).join(", ");
                    throwMraidError("event must be one of the following case sensitive values: " + eventsAllowed, "addEventListener");
                    return;
                }
                if (!isFunction(listener)) {
                    throwMraidError("listener must be a function", "addEventListener");
                    return;
                }
                $listenerManager.addEventListener(event, listener);
                log.debug("Bottom of mraid.addEventListener");
            },
            close: function() {
                log.debug("Top of mraid.close");
                if ($placementType == PLACEMENT_TYPES.inline && $state != STATES.expanded && $state != STATES.resized || $placementType == PLACEMENT_TYPES.interstitial && $state != STATES.default) {
                    throwMraidError("mraid.close can only be called on inline placements in a resized or expanded state, or by interstitials in a default state", "close");
                    return;
                }
                log.info("Creative closing.");
                callNativeLayer(MRAID_API_MODULE, "close");
                log.debug("Bottom of mraid.close");
            },
            createCalendarEvent: function(parameters) {
                log.debug("mraid.createCalendarEvent called with parameters %s", parameters);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.createCalendarEvent", "createCalendarEvent");
                    return;
                }
                callNativeLayer(MRAID_API_MODULE, "createCalendarEvent", [ generateParameterObject("parameters", parameters) ]);
                log.debug("Bottom of mraid.createCalendarEvent");
            },
            expand: function(url) {
                log.debug("mraid.expand called with url %s. Expand Properties are %s", url, $expandProperties);
                if ($placementType == PLACEMENT_TYPES.interstitial || $state != STATES.default && $state != STATES.resized) {
                    throwMraidError("mraid.expand can only be called on inline placements in a default or resized state", "expand");
                    return;
                }
                if (defined(url) && !isString(url)) {
                    throwMraidError("The url passed to mraid.expand must be a string", "expand");
                    return;
                }
                log.info("Creative expanding. Expand properties: %s", $expandProperties);
                callNativeLayer(MRAID_API_MODULE, "expand", [ generateParameterObject("width", $expandProperties.width), generateParameterObject("height", $expandProperties.height), generateParameterObject("useCustomClose", $expandProperties.useCustomClose), generateParameterObject("url", url) ]);
                log.debug("Bottom of mraid.expand");
            },
            getCurrentPosition: function() {
                log.debug("mraid.getCurrentPosition returning %s", $currentPosition);
                return copyObject($currentPosition);
            },
            getDefaultPosition: function() {
                log.debug("mraid.getDefaultPosition returning %s", $defaultPosition);
                return copyObject($defaultPosition);
            },
            getExpandProperties: function() {
                var expandProperties = {
                    width: defined($expandProperties.width) ? $expandProperties.width : $maxSize.width,
                    height: defined($expandProperties.height) ? $expandProperties.height : $maxSize.height,
                    useCustomClose: defined($expandProperties.useCustomClose) ? $expandProperties.useCustomClose : false,
                    isModal: true
                };
                log.debug("mraid.getExpandProperties returning %s. The stored expand properties are %s", expandProperties, $expandProperties);
                return expandProperties;
            },
            getMaxSize: function() {
                log.debug("mraid.getMaxSize returning %s", $maxSize);
                return copyObject($maxSize);
            },
            getOrientationProperties: function() {
                log.debug("mraid.getOrientationProperties returning %s", $orientationProperties);
                return copyObject($orientationProperties);
            },
            getPlacementType: function() {
                log.debug("mraid.getPlacementType returning %s", $placementType);
                return $placementType;
            },
            getResizeProperties: function() {
                log.debug("mraid.getResizeProperties returning %s", $resizeProperties);
                return copyObject($resizeProperties);
            },
            getScreenSize: function() {
                log.debug("mraid.getScreenSize returning %s", $screenSize);
                return copyObject($screenSize);
            },
            getState: function() {
                log.debug("mraid.getState returning %s", $state);
                return $state;
            },
            getVersion: function() {
                log.debug("mraid.getVersion returning 2.0");
                return "2.0";
            },
            isViewable: function() {
                log.debug("mraid.isViewable returning %s", $viewable);
                return $viewable;
            },
            open: function(url) {
                log.debug("mraid.open called with url %s", url);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.open", "open");
                    return;
                }
                if (!isString(url)) {
                    throwMraidError("The url passed to mraid.open must be a string", "open");
                    return;
                }
                callNativeLayer(MRAID_API_MODULE, "open", [ generateParameterObject("url", url) ]);
                log.debug("Bottom of mraid.open");
            },
            playVideo: function(url) {
                log.debug("mraid.playVideo called with url %s", url);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.playVideo", "playVideo");
                    return;
                }
                if (!isString(url)) {
                    throwMraidError("The url passed to mraid.playVideo must be a string", "playVideo");
                    return;
                }
                callNativeLayer(MRAID_API_MODULE, "playVideo", [ generateParameterObject("url", url) ]);
                log.debug("Bottom of mraid.playVideo", url);
            },
            removeEventListener: function(event, listener) {
                log.debug("mraid.removeEventListener called with event %s and listener %s", event, listener);
                $listenerManager.removeEventListener(event, listener);
                log.debug("Bottom of mraid.removeEventListener");
            },
            resize: function() {
                log.debug("mraid.resize called. Resize properties are %s", $resizeProperties);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.resize", "resize");
                    return;
                }
                var resizePropertiesSet = Object.keys($resizeProperties).length > 0;
                if (!resizePropertiesSet) {
                    throwMraidError("mraid.setResizeProperties must be called before calling mraid.resize", "resize");
                    return;
                }
                if ($state == STATES.expanded || $placementType == PLACEMENT_TYPES.interstitial) {
                    throwMraidError("mraid.resize() cannot be called in an expanded state", "resize");
                    return;
                }
                log.info("Creative resizing. Resize properties: %s", $resizeProperties);
                callNativeLayer(MRAID_API_MODULE, "resize", [ generateParameterObject("width", $resizeProperties.width), generateParameterObject("height", $resizeProperties.height), generateParameterObject("customClosePosition", $resizeProperties.customClosePosition), generateParameterObject("offsetX", $resizeProperties.offsetX), generateParameterObject("offsetY", $resizeProperties.offsetY), generateParameterObject("allowOffscreen", $resizeProperties.allowOffscreen) ]);
                log.debug("Bottom of mraid.resize");
            },
            setExpandProperties: function(properties) {
                log.debug("mraid.setExpandProperties called with properties %s", properties);
                var shouldHaveWidth = false, shouldHaveHeight = false;
                if (defined(properties.width)) {
                    if (isNumber(properties.width) && properties.width >= 50) {
                        shouldHaveWidth = true;
                        $expandProperties.width = properties.width;
                    } else {
                        throwMraidError("width must be a number greater than or equal to 50", "setExpandProperties");
                    }
                } else {
                    log.debug("properties.width was not defined in expand properties");
                }
                if (!shouldHaveWidth && $expandProperties.hasOwnProperty("width")) {
                    delete $expandProperties.width;
                }
                if (defined(properties.height)) {
                    if (isNumber(properties.height) && properties.height >= 50) {
                        shouldHaveHeight = true;
                        $expandProperties.height = properties.height;
                    } else {
                        throwMraidError("height must be a number greater than or equal to 50", "setExpandProperties");
                    }
                } else {
                    log.debug("properties.height was not defined in expand properties");
                }
                if (!shouldHaveHeight && $expandProperties.hasOwnProperty("height")) {
                    delete $expandProperties.height;
                }
                if (defined(properties.useCustomClose)) {
                    if (isBoolean(properties.useCustomClose)) {
                        $expandProperties.useCustomClose = properties.useCustomClose;
                    } else {
                        throwMraidError("useCustomClose must be a boolean", setExpandProperties);
                    }
                } else {
                    log.debug("properties.useCustomClose was not defined in expand properties");
                }
                log.debug("Bottom of setExpandProperties. Stored expand properties are %s", $expandProperties);
            },
            setOrientationProperties: function(properties) {
                log.debug("mraid.setResizeProperties called with properties %s", properties);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.setOrientationProperties", "setOrientationProperties");
                    return;
                }
                $orientationProperties = {};
                if (defined(properties.allowOrientationChange)) {
                    if (isBoolean(properties.allowOrientationChange)) {
                        $orientationProperties.allowOrientationChange = properties.allowOrientationChange;
                    } else {
                        throwMraidError("allowOrientationChange must be a boolean", "setOrientationProperties");
                    }
                } else {
                    log.debug("properties.allowOrientationChange was not defined in orientation properties");
                }
                if (!defined($orientationProperties.allowOrientationChange)) {
                    $orientationProperties.allowOrientationChange = true;
                }
                if (defined(properties.forceOrientation)) {
                    if (FORCE_ORIENTATION.hasOwnProperty(properties.forceOrientation)) {
                        $orientationProperties.forceOrientation = properties.forceOrientation;
                    } else {
                        var forceOrientationValuesAllowed = Object.keys(FORCE_ORIENTATION).map(function(key) {
                            return '"' + key + '"';
                        }).join(", ");
                        throwMraidError("forceOrientation must be one of the following case sensitive values: " + forceOrientationValuesAllowed, "setOrientationProperties");
                    }
                } else {
                    log.debug("properties.forceOrienation was not defined in orientation properties");
                }
                if (!defined($orientationProperties.forceOrientation)) {
                    $orientationProperties.forceOrientation = FORCE_ORIENTATION.none;
                }
                callNativeLayer(MRAID_API_MODULE, "setOrientationProperties", [ generateParameterObject("allowOrientationChange", $orientationProperties.allowOrientationChange), generateParameterObject("forceOrientation", $orientationProperties.forceOrientation) ]);
                log.debug("Bottom of mraid.setOrientationProperties. Stored orientation properties are %s", $orientationProperties);
            },
            setResizeProperties: function(properties) {
                log.debug("mraid.setResizeProperties called with properties %s", properties);
                $resizeProperties = {};
                if (!isNumber(properties.width) || properties.width < 50 || !isNumber(properties.height) || properties.height < 50 || !isNumber(properties.offsetX) || !isNumber(properties.offsetY)) {
                    throwMraidError("width, height, offsetX, and offsetY are required when calling mraid.setResizeProperties() and they must be numbers. Width and height must be greater than or equal to 50", "setResizeProperties");
                    return;
                }
                var newResizeProperties = {
                    width: properties.width,
                    height: properties.height,
                    offsetX: properties.offsetX,
                    offsetY: properties.offsetY
                };
                if (defined(properties.customClosePosition)) {
                    if (CUSTOM_CLOSE_POSITIONS.hasOwnProperty(properties.customClosePosition)) {
                        newResizeProperties.customClosePosition = properties.customClosePosition;
                    } else {
                        var customCloseValuesAllowed = Object.keys(CUSTOM_CLOSE_POSITIONS).map(function(key) {
                            return '"' + key + '"';
                        }).join(", ");
                        throwMraidError("customClosePosition must be one of the following case sensitive values: " + customCloseValuesAllowed, "setResizeProperties");
                    }
                } else {
                    log.debug("properties.customClosePosition was not defined in resize properties");
                }
                if (defined(properties.allowOffscreen)) {
                    if (isBoolean(properties.allowOffscreen)) {
                        newResizeProperties.allowOffscreen = properties.allowOffscreen;
                    } else {
                        throwMraidError("allowOffscreen must be a boolean", "setResizeProperties");
                    }
                } else {
                    log.debug("properties.allowOffscreen was not defined in resize properties");
                }
                var allowOffscreen = defined(newResizeProperties.allowOffscreen) ? newResizeProperties.allowOffscreen : true;
                log.debug("Resize property values: allowOffscreen: %s, resizeProperties: %s, maxSize: %s", allowOffscreen, newResizeProperties, $maxSize);
                if (!allowOffscreen && (newResizeProperties.width > $maxSize.width || newResizeProperties.height > $maxSize.height)) {
                    throwMraidError("allowOffscreen was false but the width or height was bigger than the max width or height", "setResizeProperties");
                    return;
                } else if (allowOffscreen) {
                    var closeButtonPosition = defined(newResizeProperties.customClosePosition) ? newResizeProperties.customClosePosition : "top-right";
                    var closeButtonX;
                    var closeButtonY;
                    if (closeButtonPosition == "top-right") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX + newResizeProperties.width - 50;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY;
                    } else if (closeButtonPosition == "top-left") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY;
                    } else if (closeButtonPosition == "bottom-right") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX + newResizeProperties.width - 50;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY + newResizeProperties.height - 50;
                    } else if (closeButtonPosition == "bottom-left") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY + newResizeProperties.height - 50;
                    } else if (closeButtonPosition == "top-center") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX + newResizeProperties.width / 2 - 25;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY;
                    } else if (closeButtonPosition == "bottom-center") {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX + newResizeProperties.width / 2 - 25;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY + newResizeProperties.height - 50;
                    } else {
                        closeButtonX = $defaultPosition.x + newResizeProperties.offsetX + newResizeProperties.width / 2 - 25;
                        closeButtonY = $defaultPosition.y + newResizeProperties.offsetY + newResizeProperties.height / 2 - 25;
                    }
                    if (closeButtonX < 0 || closeButtonX > $maxSize.width - 50 || closeButtonY < 0 || closeButtonY > $maxSize.height - 50) {
                        throwMraidError("The close button will appear offscreen", "setResizeProperties");
                        return;
                    }
                }
                $resizeProperties = newResizeProperties;
                log.debug("Bottom of mraid.resize. Stored resize properties are %s", $resizeProperties);
            },
            storePicture: function(url) {
                log.debug("mraid.storePicture called with url %s", url);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.storePicture", "storePicture");
                    return;
                }
                if (!isString(url)) {
                    throwMraidError("The url passed to mraid.storePicture must be a string", "storePicture");
                    return;
                }
                callNativeLayer(MRAID_API_MODULE, "storePicture", [ generateParameterObject("url", url) ]);
                log.debug("Bottom of mraid.storePicture");
            },
            supports: function(feature) {
                log.debug("mraid.supports called with feature %s", feature);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.supports", "supports");
                    return false;
                }
                var supports = defined($supports[feature]) ? $supports[feature] : false;
                log.debug("mraid.supports returning %s", supports);
                return supports;
            },
            useCustomClose: function(useCustomClose) {
                log.debug("mraid.useCustomClose called with useCustomClose %s", useCustomClose);
                if ($state == STATES.loading) {
                    throwMraidError("You must wait for mraid.ready before calling mraid.useCustomClose", "useCustomClose");
                    return;
                }
                if (!isBoolean(useCustomClose)) {
                    throwMraidError("The parameter passed to mraid.useCustomClose must be a boolean", "useCustomClose");
                    return;
                }
                $expandProperties.useCustomClose = useCustomClose;
                callNativeLayer(MRAID_API_MODULE, "useCustomClose", [ generateParameterObject("useCustomClose", useCustomClose) ]);
                log.debug("Bottom of mraid.useCustomClose. Stored expand properties are %s", $expandProperties);
            }
        };
    })();
    callNativeLayer(GENERIC_NAMESPACE, "fileLoaded", [ generateParameterObject("filename", "mraid.js") ]);
})(window);