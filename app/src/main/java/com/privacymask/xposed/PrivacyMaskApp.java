package com.privacymask.xposed;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Holds the single XposedService connection for PrivacyMask's own process.
 *
 * XposedServiceHelper.registerListener() is documented as "should only be called once", so it
 * lives here (Application.onCreate, called exactly once per process) rather than in
 * MainActivity, which can be created and destroyed many times in that same process's lifetime
 * (screen rotation, task recreation, etc).
 */
public class PrivacyMaskApp extends Application {

    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }

    private static volatile XposedService service;
    private static final CopyOnWriteArrayList<ServiceStateListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                for (ServiceStateListener l : listeners) l.onServiceStateChanged(bound);
            }

            @Override
            public void onServiceDied(XposedService dead) {
                service = null;
                for (ServiceStateListener l : listeners) l.onServiceStateChanged(null);
            }
        });
    }

    public static XposedService getService() {
        return service;
    }

    /** @param sticky if true and a service is already bound, fires immediately with it. */
    public static void addServiceStateListener(ServiceStateListener listener, boolean sticky) {
        listeners.add(listener);
        if (sticky && service != null) listener.onServiceStateChanged(service);
    }

    public static void removeServiceStateListener(ServiceStateListener listener) {
        listeners.remove(listener);
    }
}
