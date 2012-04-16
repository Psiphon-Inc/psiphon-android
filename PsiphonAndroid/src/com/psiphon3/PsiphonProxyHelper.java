package com.psiphon3;

import org.apache.http.HttpHost;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PsiphonProxyHelper {
    public static boolean setWebkitProxy(Context ctx, String host, int port) {
        try {
                Class webViewCoreClass = Class.forName("android.webkit.WebViewCore");
                Class proxyPropertiesClass = Class.forName("android.net.ProxyProperties");
                if (webViewCoreClass != null && proxyPropertiesClass != null) {
                    Method m = webViewCoreClass.getDeclaredMethod("sendStaticMessage", Integer.TYPE, Object.class);
                    Constructor c = proxyPropertiesClass.getConstructor(String.class, Integer.TYPE, String.class);
                    m.setAccessible(true);
                    c.setAccessible(true);
                    Object properties = c.newInstance(host, port, null);
                    
                    // android.webkit.WebViewCore.EventHub.PROXY_CHANGED = 193;
                    m.invoke(null, 193, properties);
                    return true;
                }
                Object requestQueueObject = getRequestQueue(ctx);
                if (requestQueueObject != null) {
                    //Create Proxy config object and set it into request Q
                    HttpHost httpHost = new HttpHost(host, port, "http");
                   // HttpHost httpsHost = new HttpHost(host, port, "https");
    
                    setDeclaredField(requestQueueObject, "mProxyHost", httpHost);
                    return true;
                }
        } catch (Exception e) {
            Log.e(PsiphonConstants.TAG, "error setting up webkit proxying", e);
        }
        return false;
    }
    public static void resetProxy(Context ctx) throws Exception {
        Object requestQueueObject = getRequestQueue(ctx);
        if (requestQueueObject != null) {
            setDeclaredField(requestQueueObject, "mProxyHost", null);
        }
    }

    public static Object getRequestQueue(Context ctx) throws Exception {
        Object ret = null;
        Class networkClass = Class.forName("android.webkit.Network");
        if (networkClass != null) {
            Object networkObj = invokeMethod(networkClass, "getInstance", new Object[]{ctx}, Context.class);
            if (networkObj != null) {
                ret = getDeclaredField(networkObj, "mRequestQueue");
            }
        }
        return ret;
    }

    private static Object getDeclaredField(Object obj, String name)
            throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object out = f.get(obj);
        //System.out.println(obj.getClass().getName() + "." + name + " = "+ out);
        return out;
    }

    private static void setDeclaredField(Object obj, String name, Object value)
            throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }
    
    private static Object invokeMethod(Object object, String methodName, Object[] params, Class... types) throws Exception {
        Object out = null;
        Class c = object instanceof Class ? (Class) object : object.getClass();
        if (types != null) {
            Method method = c.getMethod(methodName, types);
            out = method.invoke(object, params);
        } else {
            Method method = c.getMethod(methodName);
            out = method.invoke(object);
        }
        //System.out.println(object.getClass().getName() + "." + methodName + "() = "+ out);
        return out;
    }

}
