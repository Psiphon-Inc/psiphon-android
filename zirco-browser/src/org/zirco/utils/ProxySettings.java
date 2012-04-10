package org.zirco.utils;

import java.io.IOException;
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
	
	public static void setSystemProxy(Context ctx)
	{
		Toast.makeText(ctx, ctx.getResources().getString(R.string.ProxySettings_EnablingProxySettings), Toast.LENGTH_SHORT).show();

		if (_proxyStatus == null || _proxyStatus == false)
			testSystemProxy(ctx);
		
		if (_proxyStatus)
		{
			setProxy(ctx,getSystemProxyAddress(ctx),getSystemProxyPort(ctx));
			Toast.makeText(ctx, ctx.getResources().getString(R.string.ProxySettings_ProxySettingsEnabled), Toast.LENGTH_LONG).show();
		}
		else
			resetSystemProxy(ctx);
	}
	
	public static void resetSystemProxy(Context ctx)
	{
		if (_proxyStatus != null && _proxyStatus == true)
		{
    		try
    		{
    			resetProxy(ctx);
    		}
    		catch (Exception e)
    		{
    			Log.e("ProxySettings","Exception resetting WebKit proxy settings: " + e.toString());
    		}
		}
	}

    private static boolean setProxy(Context ctx, String host, int port) 
    {
        boolean ret = false;
        try 
        {
            Object requestQueueObject = getRequestQueue(ctx);
            if (requestQueueObject != null) 
            {
                //Create Proxy config object and set it into request Q
                HttpHost httpHost = new HttpHost(host, port, "http");
                setDeclaredField(requestQueueObject, "mProxyHost", httpHost);
                //Log.d("Webkit Setted Proxy to: " + host + ":" + port);
                ret = true;
            }
        } 
        catch (Exception e) 
        {
        	Log.e("ProxySettings","Exception setting WebKit proxy settings: " + e.toString());
        }
        return ret;
    }

    private static void resetProxy(Context ctx) throws Exception 
    {
        Object requestQueueObject = getRequestQueue(ctx);
        if (requestQueueObject != null) 
        {
            setDeclaredField(requestQueueObject, "mProxyHost", null);
        }
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
