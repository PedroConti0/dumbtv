package tv.dumbtv.home;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only NordVPN's connection notification and shares its country with the home screen. */
final class VpnStatus {
    static final String ACTION = "tv.dumbtv.home.VPN_LOCATION_CHANGED";
    static final String EXTRA = "country";
    private static final String TAG = "dumbtv-vpn";
    private static final String NORDVPN = "com.nordvpn.android";
    private static final String HOME_COUNTRY = "Netherlands";
    private static final String PREFS = "vpn-status";
    private static final String COUNTRY = "country";
    private static final Pattern CONNECTED = Pattern.compile(
            "(?i)connected\\s+to\\s+(.+?)(?:\\s+#\\S+|$)");

    private VpnStatus() {}

    static String location(Context context) {
        String country = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(COUNTRY, HOME_COUNTRY);
        return country == null || country.trim().isEmpty() ? HOME_COUNTRY : country;
    }

    static void readNotification(Context context, StatusBarNotification status) {
        String text = notificationText(status);
        if (text != null) update(context, text);
    }

    static boolean isNordVpn(StatusBarNotification status) {
        return status != null && NORDVPN.equals(status.getPackageName());
    }

    static boolean hasConnectionStatus(StatusBarNotification status) {
        String text = notificationText(status);
        return text != null && country(text) != null;
    }

    static void useHomeCountry(Context context) {
        save(context, HOME_COUNTRY);
    }

    private static String notificationText(StatusBarNotification status) {
        if (!isNordVpn(status)) return null;
        Notification notification = status.getNotification();
        StringBuilder text = new StringBuilder();
        if (notification != null) {
            Bundle extras = notification.extras;
            if (extras != null) {
                append(text, extras.getCharSequence(Notification.EXTRA_TITLE));
                append(text, extras.getCharSequence(Notification.EXTRA_TEXT));
                append(text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
                append(text, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
                append(text, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            }
            append(text, notification.tickerText);
        }
        return text.toString();
    }

    static void readAccessibilityEvent(Context context, AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !NORDVPN.contentEquals(packageName)) return;
        StringBuilder text = new StringBuilder();
        for (CharSequence part : event.getText()) append(text, part);
        Object data = event.getParcelableData();
        if (data instanceof Notification) {
            Notification notification = (Notification) data;
            Bundle extras = notification.extras;
            if (extras != null) {
                append(text, extras.getCharSequence(Notification.EXTRA_TITLE));
                append(text, extras.getCharSequence(Notification.EXTRA_TEXT));
                append(text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            }
        }
        update(context, text.toString());
    }

    private static void update(Context context, String text) {
        String country = country(text);
        if (country != null) save(context, country);
    }

    private static String country(String text) {
        Matcher connected = CONNECTED.matcher(text);
        if (connected.find()) return connected.group(1).trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("disconnected") || normalized.contains("not connected")) {
            return HOME_COUNTRY;
        }
        return null;
    }

    private static void save(Context context, String country) {
        if (country == null || country.isEmpty()) return;
        String previous = location(context);
        if (country.equals(previous)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(COUNTRY, country).apply();
        Intent changed = new Intent(ACTION).setPackage(context.getPackageName());
        changed.putExtra(EXTRA, country);
        context.sendBroadcast(changed);
        Log.i(TAG, "VPN country updated to " + country);
    }

    private static void append(StringBuilder into, CharSequence value) {
        if (value == null || value.length() == 0) return;
        if (into.length() > 0) into.append(' ');
        into.append(value);
    }
}
