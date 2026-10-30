package com.jannek.jarvis;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Holt Wetter (Open-Meteo) und Schlagzeilen (Tagesschau, Google News) für den Home-Screen. */
final class InfoFeed {

    static final String CITY = "ESSEN";

    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=51.4556&longitude=7.0116"
                    + "&current=temperature_2m,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&timezone=Europe%2FBerlin&forecast_days=1";
    private static final String[] DE_URLS = {
            "https://www.tagesschau.de/index~rss2.xml",
            "https://www.tagesschau.de/xml/rss2/"};
    private static final String LOCAL_URL =
            "https://news.google.com/rss/search?q=Essen&hl=de&gl=DE&ceid=DE:de";

    static final class Result {
        boolean weatherOk;
        String temp = "";
        String desc = "";
        String range = "";
        String rain = "";
        List<String> de = new ArrayList<String>();
        List<String> local = new ArrayList<String>();
    }

    private InfoFeed() {
    }

    static Result fetch() {
        Result r = new Result();
        try {
            parseWeather(get(WEATHER_URL), r);
        } catch (Exception ignored) {
        }
        for (String u : DE_URLS) {
            try {
                r.de = parseRss(get(u), 8);
            } catch (Exception ignored) {
            }
            if (!r.de.isEmpty()) break;
        }
        try {
            r.local = parseRss(get(LOCAL_URL), 8);
        } catch (Exception ignored) {
        }
        return r;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "JarvisHome/0.2");
        InputStream in = con.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 600000) break;
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
            con.disconnect();
        }
    }

    private static void parseWeather(String json, Result r) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        JSONObject day = o.getJSONObject("daily");
        int t = (int) Math.round(cur.getDouble("temperature_2m"));
        int code = cur.getInt("weather_code");
        int hi = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
        int lo = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
        JSONArray pa = day.getJSONArray("precipitation_probability_max");
        int pr = pa.optInt(0, 0);
        r.temp = t + "\u00B0";
        r.desc = describe(code);
        r.range = "TAG " + lo + "\u00B0 / " + hi + "\u00B0";
        r.rain = "REGEN " + pr + " %";
        r.weatherOk = true;
    }

    private static List<String> parseRss(String xml, int max) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        List<String> out = new ArrayList<String>();
        boolean inItem = false;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT && out.size() < max) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("item".equals(n)) {
                    inItem = true;
                } else if (inItem && "title".equals(n)) {
                    String s = p.nextText().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            } else if (ev == XmlPullParser.END_TAG && "item".equals(p.getName())) {
                inItem = false;
            }
            ev = p.next();
        }
        return out;
    }

    /** WMO-Wettercode in kurzes Deutsch. */
    private static String describe(int c) {
        if (c == 0) return "KLAR";
        if (c == 1) return "ÜBERWIEGEND KLAR";
        if (c == 2) return "WOLKIG";
        if (c == 3) return "BEDECKT";
        if (c == 45 || c == 48) return "NEBEL";
        if (c >= 51 && c <= 57) return "NIESELREGEN";
        if (c >= 61 && c <= 67) return "REGEN";
        if (c >= 71 && c <= 77) return "SCHNEE";
        if (c >= 80 && c <= 82) return "REGENSCHAUER";
        if (c == 85 || c == 86) return "SCHNEESCHAUER";
        if (c >= 95) return "GEWITTER";
        return "WECHSELHAFT";
    }
}
