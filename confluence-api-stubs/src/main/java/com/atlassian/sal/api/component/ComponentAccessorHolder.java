package com.atlassian.sal.api.component;

public final class ComponentAccessorHolder {
    private static volatile ComponentAccessor instance;

    private ComponentAccessorHolder() {}

    public static void setComponentAccessor(ComponentAccessor accessor) {
        instance = accessor;
    }

    public static ComponentAccessor getComponentAccessor() {
        return instance;
    }
}
