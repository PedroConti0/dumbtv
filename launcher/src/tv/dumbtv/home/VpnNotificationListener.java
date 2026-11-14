package tv.dumbtv.home;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Handler;
import android.os.Looper;

/** Reads NordVPN's active country notification, including one already posted before home starts. */
public final class VpnNotificationListener extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkDisconnected = this::refreshFromActiveNotifications;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        refreshFromActiveNotifications();
    }

    private void refreshFromActiveNotifications() {
        StatusBarNotification[] active = getActiveNotifications();
        boolean foundStatus = false;
        if (active == null) active = new StatusBarNotification[0];
        for (StatusBarNotification notification : active) {
            if (!VpnStatus.isNordVpn(notification)) continue;
            VpnStatus.readNotification(this, notification);
            foundStatus |= VpnStatus.hasConnectionStatus(notification);
        }
        if (!foundStatus) VpnStatus.useHomeCountry(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (VpnStatus.isNordVpn(notification)) handler.removeCallbacks(checkDisconnected);
        VpnStatus.readNotification(this, notification);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        if (!VpnStatus.isNordVpn(notification)) return;
        handler.removeCallbacks(checkDisconnected);
        // NordVPN can replace its status notification during a reconnect. Give the new one
        // time to post before treating removal of the old connected notification as a disconnect.
        handler.postDelayed(checkDisconnected, 1200);
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(checkDisconnected);
        super.onListenerDisconnected();
    }
}
