package com.gitlab.sync.confluence;

import com.atlassian.confluence.core.ContentPermissionManager;
import com.atlassian.confluence.mail.ConfluenceMailServerManager;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.mail.server.MailServerManager;
import com.atlassian.confluence.user.UserAccessor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-demand access to Confluence OSGi services from SAL-managed code.
 *
 * <p>The REST resources are instantiated by SAL via plain reflection, so
 * they cannot receive OSGi service injections. The bundle activator
 * ({@link BundleContextHolder}) keeps the {@link BundleContext} available,
 * and these lookups resolve the services per request — robust to the OSGi
 * start order, because nothing is resolved at bundle start.</p>
 */
public final class GitsyncServices {

    private static final Logger LOG = LoggerFactory.getLogger(GitsyncServices.class);

    private GitsyncServices() {
    }

    private static <T> T lookup(Class<T> type) {
        BundleContext ctx = BundleContextHolder.get();
        if (ctx == null) {
            return null;
        }
        ServiceReference<?> ref = ctx.getServiceReference(type.getName());
        if (ref == null) {
            return null;
        }
        Object service = ctx.getService(ref);
        return service == null ? null : type.cast(service);
    }

    public static UserAccessor userAccessor() {
        return lookup(UserAccessor.class);
    }

    public static PageManager pageManager() {
        return lookup(PageManager.class);
    }

    public static ContentPermissionManager contentPermissionManager() {
        return lookup(ContentPermissionManager.class);
    }

    /**
     * Confluence's mail server manager, as the atlassian-mail
     * {@link MailServerManager} interface.
     *
     * <p>The bean is a concrete class
     * ({@code com.atlassian.confluence.mail.ConfluenceMailServerManager})
     * and is NOT registered under its own class name in OSGi, so the
     * lookup goes in three stages: (1) direct lookup by the interface
     * name, (2) a full scan of service objectClasses for the interface or
     * the concrete class name, (3) a scan of the registered Spring OSGi
     * application contexts by bean name (last resort, reflection only —
     * the Spring API is not a compile-time dependency).</p>
     */
    public static MailServerManager mailServerManager() {
        BundleContext ctx = BundleContextHolder.get();
        if (ctx == null) {
            com.gitlab.sync.confluence.mail.MailDiagnostics
                    .record("mailServerManager: no BundleContext");
            return null;
        }
        String ifaceName = MailServerManager.class.getName();
        String implName = ConfluenceMailServerManager.class.getName();
        try {
            ServiceReference<?> ref = ctx.getServiceReference(ifaceName);
            if (ref != null) {
                Object svc = ctx.getService(ref);
                try {
                    if (svc instanceof MailServerManager) {
                        return (MailServerManager) svc;
                    }
                } finally {
                    if (svc != null) {
                        try {
                            ctx.ungetService(ref);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            ServiceReference<?>[] refs = ctx.getAllServiceReferences(null, null);
            if (refs != null) {
                for (ServiceReference<?> r : refs) {
                    Object objectClass = r.getProperty(ServiceReference.OBJECTCLASS);
                    boolean match = false;
                    if (objectClass instanceof String[]) {
                        for (String name : (String[]) objectClass) {
                            if (name.equals(ifaceName) || name.equals(implName)) {
                                match = true;
                                break;
                            }
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    Object svc = ctx.getService(r);
                    if (svc == null) {
                        continue;
                    }
                    try {
                        if (svc instanceof MailServerManager) {
                            return (MailServerManager) svc;
                        }
                    } catch (Throwable t) {
                        com.gitlab.sync.confluence.mail.MailDiagnostics
                                .record("mailServerManager: objectClass match but no instanceof: " + t);
                    } finally {
                        try {
                            ctx.ungetService(r);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            MailServerManager fromSpring = fromSpringContexts(ctx);
            if (fromSpring != null) {
                return fromSpring;
            }
            dumpOsgiServices(ctx);
            return null;
        } catch (Throwable t) {
            LOG.warn("MailServerManager lookup failed: {}", t.toString());
            com.gitlab.sync.confluence.mail.MailDiagnostics
                    .record("mailServerManager lookup failed: " + t);
            return null;
        }
    }

    /**
     * Last resort: iterate the registered Spring-OSGi application
     * contexts and ask each for a bean whose name looks like a mail
     * server manager. Reflection only — the plugin has no compile-time
     * dependency on the Spring API.
     */
    private static MailServerManager fromSpringContexts(BundleContext ctx) {
        ServiceReference<?>[] refs;
        try {
            refs = ctx.getAllServiceReferences(null, null);
        } catch (Throwable t) {
            return null;
        }
        if (refs == null) {
            return null;
        }
        for (ServiceReference<?> r : refs) {
            Object svc = ctx.getService(r);
            if (svc == null) {
                continue;
            }
            try {
                String cn;
                try {
                    cn = svc.getClass().getName();
                } catch (Throwable t) {
                    continue;
                }
                if (!cn.contains("ApplicationContext")) {
                    continue;
                }
                Object[] beanNames;
                try {
                    beanNames = (Object[]) svc.getClass()
                            .getMethod("getBeanDefinitionNames").invoke(svc);
                } catch (Throwable t) {
                    continue;
                }
                for (Object beanNameObj : beanNames) {
                    String beanName = String.valueOf(beanNameObj);
                    if (!beanName.toLowerCase(java.util.Locale.ROOT).contains("mailserver")) {
                        continue;
                    }
                    try {
                        Object bean = svc.getClass().getMethod("getBean", String.class)
                                .invoke(svc, beanName);
                        if (bean instanceof MailServerManager) {
                            com.gitlab.sync.confluence.mail.MailDiagnostics
                                    .record("mailServerManager: resolved from Spring context "
                                            + cn + " bean '" + beanName + "'");
                            return (MailServerManager) bean;
                        }
                    } catch (Throwable t) {
                        com.gitlab.sync.confluence.mail.MailDiagnostics
                                .record("mailServerManager: Spring bean '" + beanName
                                        + "' lookup failed: " + t);
                    }
                }
            } finally {
                try {
                    ctx.ungetService(r);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Last-resort diagnostics: append every registered OSGi service's class
     * name so a missing registration can be told apart from a classloader
     * mismatch (the dump shows the runtime names Confluence actually uses).
     */
    private static void dumpOsgiServices(BundleContext ctx) {
        try {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            ServiceReference<?>[] refs = ctx.getAllServiceReferences(null, null);
            if (refs != null) {
                for (ServiceReference<?> ref : refs) {
                    Object svc = ctx.getService(ref);
                    if (svc == null) {
                        continue;
                    }
                    total++;
                    try {
                        sb.append(svc.getClass().getName()).append(';');
                    } catch (Throwable t) {
                        sb.append("?;");
                    } finally {
                        try {
                            ctx.ungetService(ref);
                        } catch (Throwable ignored) {
                            // already unregistered
                        }
                    }
                    if (sb.length() > 60_000) {
                        break;
                    }
                }
            }
            com.gitlab.sync.confluence.mail.MailDiagnostics
                    .record("mailServerManager: not found among " + total
                            + " registered services: " + sb);
        } catch (Throwable t) {
            com.gitlab.sync.confluence.mail.MailDiagnostics
                    .record("mailServerManager: service dump failed: " + t);
        }
    }
}
