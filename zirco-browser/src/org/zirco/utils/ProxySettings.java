package org.zirco.utils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.zirco.R;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Utility class for setting WebKit proxy used by Android WebView
 *
 */
public class ProxySettings 
{
	private static ContentResolver _contentResolver;
	private static String _proxyString;
	private static String _proxyAddress;
	private static Integer _proxyPort;
	private static Boolean _proxyStatus = null;
	
	private static String getSystemProxyAddress(Context ctx)
    {
    	if (isSystemProxyValid(ctx))
    	{
    		_proxyAddress = _proxyString.split(":")[0];
    		return _proxyAddress;
    	}
    	else
    		return null;
    }

    private static Integer getSystemProxyPort(Context ctx)
    {
    	if (isSystemProxyValid(ctx))
    	{
    		_proxyPort = Integer.parseInt(_proxyString.split(":")[1]);
    		return _proxyPort;
    	}
    	else
    		return null;
    }
    
    private static boolean isSystemProxyValid(Context ctx)
    {
    	_contentResolver = ctx.getContentResolver();
		_proxyString = Settings.Secure.getString(_contentResolver,Settings.Secure.HTTP_PROXY);
		if (_proxyString != null && _proxyString != "" && _proxyString.contains(":"))
		{
			return true;
		}
		else
		{
			return false;
		}
    }
    
    private static boolean isSystemProxyReachable(Context ctx)
    {
    	int exitValue;
    	Runtime runtime = Runtime.getRuntime();
        Process proc;
		
        try 
		{
			proc = runtime.exec("ping -c 1   " + getSystemProxyAddress(ctx));
			proc.waitFor();
			exitValue = proc.exitValue();
			
			Log.d("ProxySettings","Ping exit value: "+ exitValue);
			
			if (exitValue == 0)
				return true;
			else
				return false;
		} 
		catch (IOException e) 
		{
			e.printStackTrace();
		} 
		catch (InterruptedException e) 
		{
			e.printStackTrace();
		}

		return false;   
    }
    
    private static boolean isInternetReachable(Context ctx)
    {
		DefaultHttpClient httpclient = new DefaultHttpClient();		
        HttpHost proxy = new HttpHost(getSystemProxyAddress(ctx),getSystemProxyPort(ctx));
        httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
		
        HttpGet request;
        HttpResponse response;
        
		try 
		{
			request = new HttpGet("http://www.google.com");
			response = httpclient.execute(request);
			
			Log.d("ProxySettings", "Is internet reachable : " + response.getStatusLine().toString());
			if (response != null && response.getStatusLine().getStatusCode() == 200)
			{
				return true;
			}
			else
				return false;
		} 
		catch (ClientProtocolException e) 
		{
			e.printStackTrace();
		} 
		catch (IOException e) 
		{
			e.printStackTrace();
		}
		
		return false;
    }
    
	private static boolean testSystemProxy(Context ctx)
	{
		String message = "";
		
		if (!isSystemProxyValid(ctx))
		{
			_proxyStatus = false;
			message = ctx.getResources().getString(R.string.ProxySettings_ErrorProxySettingsNotValid);
		}
		else if (!isSystemProxyReachable(ctx))
		{
			_proxyStatus = false;
			message = ctx.getResources().getString(R.string.ProxySettings_ErrorProxyServerNotReachable);
		}
		else if (!isInternetReachable(ctx))
		{
			_proxyStatus = false;
			message = ctx.getResources().getString(R.string.ProxySettings_ErrorProxyInternetNotReachable);
		}
		else
		{
			_proxyStatus = true;
		}

		if (!_proxyStatus)
			Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
		
		return _proxyStatus;
	}
	
	
	public static void setLocalProxy(Context ctx, int port)
	{
	    // PSIPHON: don't show this message, we're always proxied 
        //Toast.makeText(ctx, ctx.getResources().getString(R.string.ProxySettings_EnablingProxySettings), Toast.LENGTH_SHORT).show();
        setProxy(ctx,"localhost",port);
	}
	
    /* 
    Proxy setting code taken directly from Orweb, with some modifications.
    (...And some of the Orweb code was taken from an earlier version of our code.)
    See: https://github.com/guardianproject/Orweb/blob/master/src/org/torproject/android/OrbotHelper.java#L39
    Note that we tried and abandoned doing feature detection by trying the 
    newer (>= ICS) proxy setting, catching, and then failing over to the older
    approach. The problem was that on Android 3.0, an exception would be thrown
    *in another thread*, so we couldn't catch it and the whole app would force-close.
    Orweb has always been doing an explicit version check, and it seems to work,
    so we're so going to switch to that approach.
    */
    public static boolean setProxy (Context ctx, String host, int port)
    {
        boolean worked = false;

        if (Build.VERSION.SDK_INT < 14) 
        {
            worked = setWebkitProxyGingerbread(ctx, host, port);
        }
        else
        {
            worked = setWebkitProxyICS(ctx, host, port);
        }
        
        return worked;
    }

    private static boolean setWebkitProxyGingerbread(Context ctx, String host, int port)
    {
        try
        {
            Object requestQueueObject = getRequestQueue(ctx);
            if (requestQueueObject != null) {
                //Create Proxy config object and set it into request Q
                HttpHost httpHost = new HttpHost(host, port, "http");   
                setDeclaredField(requestQueueObject, "mProxyHost", httpHost);
                
                return true;
            }
        }
        catch (Throwable e)
        {
            // Failed. Fall through to false return.
        }
        
        return false;
    }
    
    private static boolean setWebkitProxyICS(Context ctx, String host, int port)
    {
        // PSIPHON: added support for Android 4.x WebView proxy
        try 
        {
            Class webViewCoreClass = Class.forName("android.webkit.WebViewCore");
           
            Class proxyPropertiesClass = Class.forName("android.net.ProxyProperties");
            if (webViewCoreClass != null && proxyPropertiesClass != null) 
            {
                Method m = webViewCoreClass.getDeclaredMethod("sendStaticMessage", Integer.TYPE, Object.class);
                Constructor c = proxyPropertiesClass.getConstructor(String.class, Integer.TYPE, String.class);
                
                if (m != null && c != null)
                {
                    m.setAccessible(true);
                    c.setAccessible(true);
                    Object properties = c.newInstance(host, port, null);
                
                    // android.webkit.WebViewCore.EventHub.PROXY_CHANGED = 193;
                    m.invoke(null, 193, properties);
                    return true;
                }
            }
        }
        catch (Exception e) 
        {
            Log.e("ProxySettings","Exception setting WebKit proxy through android.net.ProxyProperties: " + e.toString());
        }
        catch (Error e) 
        {
            Log.e("ProxySettings","Exception setting WebKit proxy through android.webkit.Network: " + e.toString());
        }
        
        return false;
    }

    @SuppressWarnings("rawtypes")
	private static Object GetNetworkInstance(Context ctx) throws ClassNotFoundException
    {
        Class networkClass = Class.forName("android.webkit.Network");
        return networkClass;
    }
    
    private static Object getRequestQueue(Context ctx) throws Exception 
    {
        Object ret = null;
        Object networkClass = GetNetworkInstance(ctx);
        if (networkClass != null) 
        {
            Object networkObj = invokeMethod(networkClass, "getInstance", new Object[]{ctx}, Context.class);
            if (networkObj != null) 
            {
                ret = getDeclaredField(networkObj, "mRequestQueue");
            }
        }
        return ret;
    }

    private static Object getDeclaredField(Object obj, String name)
            throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException 
    {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object out = f.get(obj);
        return out;
    }

    private static void setDeclaredField(Object obj, String name, Object value)
            throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException 
    {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @SuppressWarnings("rawtypes")
	private static Object invokeMethod(Object object, String methodName, Object[] params, Class... types) throws Exception 
    {
        Object out = null;
        Class c = object instanceof Class ? (Class) object : object.getClass();
        
        if (types != null) 
        {
            Method method = c.getMethod(methodName, types);
            out = method.invoke(object, params);
        } 
        else 
        {
            Method method = c.getMethod(methodName);
            out = method.invoke(object);
        }
        return out;
    }
}
