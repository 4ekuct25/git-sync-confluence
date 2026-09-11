package com.gitlab.sync.confluence;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class BundleContextHolder implements BundleActivator {
    private static volatile BundleContext context;

    public void start(BundleContext ctx) {
        context = ctx;
    }

    public void stop(BundleContext ctx) {
        context = null;
    }

    public static BundleContext get() {
        return context;
    }
}
