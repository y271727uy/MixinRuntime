package cn.ksmcbrigade.mr.utils.modlauncher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ModuleClassLoaderWrapper {

    private static volatile Object cachedLoader;
    private static volatile Method cachedMethod;
    private static final AtomicReference<Thread> TRANSFORM_OWNER = new AtomicReference<>();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private static Object getTransformingClassLoader() {
        if (cachedLoader != null) {
            return cachedLoader;
        }

        Object loader = System.getProperties().get("transforming_class_loader");

        if (loader != null) {
            cachedLoader = loader;
            return cachedLoader;
        }

        return cachedLoader;
    }

    //ModuleClassLoaderTransformer
    public static byte[] maybeTransformClassBytes(final byte[] bytes, final String name, final String context) {
        Object transformingClassLoader = getTransformingClassLoader();

        if (transformingClassLoader == null) {
            return bytes;
        }

        /*
         * ModuleClassLoader invokes this method while holding a per-class loading lock.
         * Waiting for another transformation here can invert two class-loading locks:
         * each thread owns one class and the shared MixinProcessor needs the other.
         * Fail open during either same-thread recursion or cross-thread contention.
         */
        Thread currentThread = Thread.currentThread();
        if (!TRANSFORM_OWNER.compareAndSet(null, currentThread)) {
            return bytes;
        }

        try {
            Method method = cachedMethod;
            if (method == null || !method.getDeclaringClass().isInstance(transformingClassLoader)) {
                method = transformingClassLoader.getClass().getDeclaredMethod(
                        "maybeTransformClassBytes", byte[].class, String.class, String.class);
                method.setAccessible(true);
                cachedMethod = method;
            }

            byte[] transformed = (byte[]) method.invoke(
                    transformingClassLoader,
                    bytes,
                    name,
                    context != null ? context : "classloading"
            );
            return transformed != null ? transformed : bytes;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException e) {
            reportFailure(name, e instanceof InvocationTargetException && e.getCause() != null
                    ? e.getCause()
                    : e);
            return bytes;
        } finally {
            TRANSFORM_OWNER.compareAndSet(currentThread, null);
        }
    }

    private static void reportFailure(String name, Throwable error) {
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println("[MixinRuntimeAgent] Failed to transform " + name + "; using original bytes.");
            error.printStackTrace(System.err);
        }
    }
}
