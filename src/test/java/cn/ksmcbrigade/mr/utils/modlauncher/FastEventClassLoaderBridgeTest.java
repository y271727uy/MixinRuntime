package cn.ksmcbrigade.mr.utils.modlauncher;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastEventClassLoaderBridgeTest {

    @Test
    void connectsGamePackagesToTransformingLoader() throws ReflectiveOperationException {
        TestModuleClassLoader eventBusLoader = new TestModuleClassLoader();
        TestTransformingClassLoader transformingLoader = new TestTransformingClassLoader();
        transformingLoader.addPackage("zank.mods.fast_event");
        transformingLoader.addPackage("net.minecraftforge.registries");

        int connected = FastEventClassLoaderBridge.connectPackages(eventBusLoader, transformingLoader);

        assertEquals(2, connected);
        assertSame(transformingLoader, eventBusLoader.parentLoader("zank.mods.fast_event"));
        assertSame(transformingLoader, eventBusLoader.parentLoader("net.minecraftforge.registries"));
    }

    @Test
    void preservesExistingPackageRoutes() throws ReflectiveOperationException {
        TestModuleClassLoader eventBusLoader = new TestModuleClassLoader();
        TestTransformingClassLoader transformingLoader = new TestTransformingClassLoader();
        ClassLoader existingLoader = new ClassLoader() {
        };
        eventBusLoader.addParentLoader("zank.mods.fast_event", existingLoader);
        transformingLoader.addPackage("zank.mods.fast_event");
        transformingLoader.addPackage("example.mod");

        int connected = FastEventClassLoaderBridge.connectPackages(eventBusLoader, transformingLoader);

        assertEquals(1, connected);
        assertSame(existingLoader, eventBusLoader.parentLoader("zank.mods.fast_event"));
        assertSame(transformingLoader, eventBusLoader.parentLoader("example.mod"));
    }

    @Test
    void detectsWhetherFastEventIsPresent() throws ReflectiveOperationException {
        TestTransformingClassLoader transformingLoader = new TestTransformingClassLoader();

        assertFalse(FastEventClassLoaderBridge.hasPackage(
                transformingLoader, "zank.mods.fast_event"));

        transformingLoader.addPackage("zank.mods.fast_event");

        assertTrue(FastEventClassLoaderBridge.hasPackage(
                transformingLoader, "zank.mods.fast_event"));
    }

    public static final class TestModuleClassLoader extends ClassLoader {
        private final Map<String, ClassLoader> parentLoaders = new HashMap<>();

        void addParentLoader(String packageName, ClassLoader loader) {
            parentLoaders.put(packageName, loader);
        }

        ClassLoader parentLoader(String packageName) {
            return parentLoaders.get(packageName);
        }
    }

    public static final class TestTransformingClassLoader extends ClassLoader {
        private final Map<String, Object> packageLookup = new HashMap<>();

        void addPackage(String packageName) {
            packageLookup.put(packageName, new Object());
        }
    }
}
