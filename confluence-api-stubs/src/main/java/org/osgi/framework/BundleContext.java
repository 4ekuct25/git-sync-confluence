package org.osgi.framework;

public interface BundleContext {
    Bundle getBundle();
    ServiceReference<?> getServiceReference(String clazz);
    ServiceReference<?>[] getAllServiceReferences(String clazz, String filter) throws InvalidSyntaxException;
    Object getService(String clazz);
    Object getService(ServiceReference<?> reference);
    void ungetService(ServiceReference<?> reference);
}
