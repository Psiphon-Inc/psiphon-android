package com.mopub.mobileads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.mopub.common.util.Json;

import android.text.TextUtils;
import android.util.Log;


public class AerServPluginUtil {
	public static final String LOG_TAG = AerServPluginUtil.class.getSimpleName();
	
	public static String getString(String key,
			Map<String, Object> localExtras, Map<String, String> serverExtras) {
		if (serverExtras != null
				&& serverExtras.get(key) != null
				&& !TextUtils.isEmpty(serverExtras.get(key))) {
			return serverExtras.get(key);
		}
		if (localExtras != null
				&& localExtras.get(key) != null
				&& localExtras.get(key) instanceof String
				&& !TextUtils.isEmpty((String) localExtras.get(key))) {
			return (String) localExtras.get(key);
		}
		return null;
	}

	public static Integer getInteger(String key,
			Map<String, Object> localExtras, Map<String, String> serverExtras) {
		if (serverExtras != null
				&& serverExtras.get(key) != null) {
			try {
				return Integer.parseInt(serverExtras.get(key));
			} catch (NumberFormatException e) {
				Log.d(LOG_TAG, "Cannot parse '" + serverExtras.get(key) + "'"
						+ " in serverExtras to Integer.  Trying from localExtras instead.");
			}
		}
		if (localExtras != null
				&& localExtras.get(key) != null
				&& localExtras.get(key) instanceof Integer) {
			return (Integer) localExtras.get(key);
		}
		return null;
	}
	
	public static List<String> getStringList(String key,
			Map<String, Object> localExtras, Map<String, String> serverExtras) {
		if (serverExtras != null
				&& serverExtras.get(key) != null) {
			try {
				String[] keywordArray = Json.jsonArrayToStringArray(serverExtras.get(key));
				return new ArrayList<String>(Arrays.asList(keywordArray));
			} catch (Exception e) {
				Log.d(LOG_TAG, "Cannot parse '" + serverExtras.get(key) + "'"
						+ " in serverExtras to List<String>.  Trying from localExtras instead.");
			}
		}
		if (localExtras != null
				&& localExtras.get(key) != null
				&& localExtras.get(key) instanceof List<?>) {
			List<String> list = new ArrayList<String>();
			for (Object item : (List<?>) localExtras.get(key)) {
				if (item != null && item instanceof String) {
					list.add((String) item);
				}
			}
			return list;
		}
		return null;
	}

	public static boolean isEmpty(String key,
			Map<String, Object> localExtras, Map<String, String> serverExtras) {
		if ((serverExtras == null || serverExtras.get(key) == null)
				&& (localExtras == null || localExtras.get(key) == null)) {
			return true;
		} else {
			return false;
		}
	}
}
