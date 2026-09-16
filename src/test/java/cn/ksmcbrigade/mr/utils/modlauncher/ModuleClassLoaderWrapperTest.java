package cn.ksmcbrigade.mr.utils.modlauncher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleClassLoaderWrapperTest {

    private static final String LOADER_PROPERTY = "transforming_class_loader";

    @BeforeEach
    @AfterEach
    void resetWrapper() throws ReflectiveOperationException {
        System.getProperties().remove(LOADER_PROPERTY);
        setStaticField("cachedLoader", null);
        setStaticField("cachedMethod", null);

        Field ownerField = ModuleClassLoaderWrapper.class.getDeclaredField("TRANSFORM_OWNER");
        ownerField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Thread> owner = (AtomicReference<Thread>) ownerField.get(null);
        owner.set(null);

        Field failureField = ModuleClassLoaderWrapper.class.getDeclaredField("FAILURE_REPORTED");
        failureField.setAccessible(true);
        ((AtomicBoolean) failureField.get(null)).set(false);
    }

    @Test
    void retriesLoaderLookupAfterEarlyCall() {
        byte[] original = {1};
        assertArrayEquals(original,
                ModuleClassLoaderWrapper.maybeTransformClassBytes(original, "early.Class", null));

        TransformingLoader loader = new TransformingLoader();
        System.getProperties().put(LOADER_PROPERTY, loader);

        assertArrayEquals(new byte[]{2},
                ModuleClassLoaderWrapper.maybeTransformClassBytes(original, "ready.Class", null));
        assertEquals(1, loader.calls.get());
    }

    @Test
    void sameThreadReentryFailsOpen() {
        ReentrantLoader loader = new ReentrantLoader();
        System.getProperties().put(LOADER_PROPERTY, loader);

        assertArrayEquals(new byte[]{9},
                ModuleClassLoaderWrapper.maybeTransformClassBytes(new byte[]{1}, "outer.Class", null));
        assertEquals(1, loader.calls.get());
    }

    @Test
    void nullTransformerResultPreservesOriginalBytes() {
        System.getProperties().put(LOADER_PROPERTY, new NullReturningLoader());
        byte[] original = {4};

        assertArrayEquals(original,
                ModuleClassLoaderWrapper.maybeTransformClassBytes(original, "unchanged.Class", null));
    }

    @Test
    void concurrentTransformationDoesNotWaitWhileHoldingAClassLoadingLock() throws Exception {
        BlockingLoader loader = new BlockingLoader();
        System.getProperties().put(LOADER_PROPERTY, loader);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<byte[]> first = executor.submit(() ->
                    ModuleClassLoaderWrapper.maybeTransformClassBytes(new byte[]{1}, "first.Class", null));
            assertTrue(loader.entered.await(5, TimeUnit.SECONDS));

            byte[] second = ModuleClassLoaderWrapper.maybeTransformClassBytes(
                    new byte[]{3}, "second.Class", null);
            assertArrayEquals(new byte[]{3}, second);

            loader.release.countDown();
            assertArrayEquals(new byte[]{2}, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, loader.calls.get());
        } finally {
            loader.release.countDown();
            executor.shutdownNow();
        }
    }

    private static void setStaticField(String name, Object value) throws ReflectiveOperationException {
        Field field = ModuleClassLoaderWrapper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    static class TransformingLoader {
        final AtomicInteger calls = new AtomicInteger();

        protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
            calls.incrementAndGet();
            return new byte[]{2};
        }
    }

    static final class ReentrantLoader extends TransformingLoader {
        @Override
        protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
            calls.incrementAndGet();
            return ModuleClassLoaderWrapper.maybeTransformClassBytes(new byte[]{9}, "nested.Class", context);
        }
    }

    static final class BlockingLoader extends TransformingLoader {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test transformer was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return new byte[]{2};
        }
    }

    static final class NullReturningLoader extends TransformingLoader {
        @Override
        protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
            calls.incrementAndGet();
            return null;
        }
    }
}
