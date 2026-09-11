package org.osgi.framework;

public interface ServiceReference<T> {
    /** Service property that lists the registered object classes (String[]). */
    String OBJECTCLASS = "objectClass";

    T getService();

    Object getProperty(String key);
}
