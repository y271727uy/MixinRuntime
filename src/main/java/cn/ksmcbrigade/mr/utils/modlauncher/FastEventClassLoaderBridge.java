package cn.ksmcbrigade.mr.utils.modlauncher;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class FastEventClassLoaderBridge {

    private static final String TRANSFORMING_LOADER_PROPERTY = "transforming_class_loader";
    private static final String FAST_EVENT_PACKAGE = "zank.mods.fast_event";
    private static final String EVENT_BUS_PACKAGE_PREFIX = "net.minecraftforge.eventbus.";
    private static final String PACKAGE_LOOKUP_FIELD = "packageLookup";
    private static final String PARENT_LOADERS_FIELD = "parentLoaders";

    private FastEventClassLoaderBridge() {
    }

    public static void install(Instrumentation instrumentation) {
        Object candidate = System.getProperties().get(TRANSFORMING_LOADER_PROPERTY);
        if (!(candidate instanceof ClassLoader transformingLoader)) {
            return;
        }

        try {
            Class<?> eventBusClass = findLoadedEventBusClass(instrumentation, transformingLoader);
            if (eventBusClass == null) {
                return;
            }
            ClassLoader eventBusLoader = eventBusClass.getClassLoader();

            openClassLoaderPackage(instrumentation, eventBusLoader.getClass());
            if (!hasPackage(transformingLoader, FAST_EVENT_PACKAGE)) {
                return;
            }
            addEventBusReads(instrumentation, eventBusClass.getModule());
            int connectedPackages = connectPackages(eventBusLoader, transformingLoader);
            System.out.println("[MixinRuntimeAgent] Connected " + connectedPackages
                    + " transformed game packages to the EventBus class loader");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[MixinRuntimeAgent] Failed to connect the FastEvent class loader");
            e.printStackTrace(System.err);
        }
    }

    private static Class<?> findLoadedEventBusClass(
            Instrumentation instrumentation, ClassLoader transformingLoader) {
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass.getName().startsWith(EVENT_BUS_PACKAGE_PREFIX)) {
                ClassLoader loader = loadedClass.getClassLoader();
                if (loader != null && loader != transformingLoader) {
                    return loadedClass;
                }
            }
        }
        return null;
    }

    private static void addEventBusReads(Instrumentation instrumentation, Module eventBusModule) {
        Set<Module> runtimeModules = new HashSet<>();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            Module module = loadedClass.getModule();
            if (module != eventBusModule) {
                runtimeModules.add(module);
            }
        }

        instrumentation.redefineModule(
                eventBusModule,
                runtimeModules,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }

    private static void openClassLoaderPackage(Instrumentation instrumentation, Class<?> loaderClass) {
        Module loaderModule = loaderClass.getModule();
        Module agentModule = FastEventClassLoaderBridge.class.getModule();
        String packageName = loaderClass.getPackageName();

        if (loaderModule.isOpen(packageName, agentModule)) {
            return;
        }

        instrumentation.redefineModule(
                loaderModule,
                Collections.emptySet(),
                Collections.emptyMap(),
                Map.of(packageName, Set.of(agentModule)),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }

    static int connectPackages(ClassLoader sourceLoader, ClassLoader targetLoader)
            throws ReflectiveOperationException {
        Map<String, ClassLoader> parentLoaders = readMap(sourceLoader, PARENT_LOADERS_FIELD);
        Map<?, ?> packageLookup = readMap(targetLoader, PACKAGE_LOOKUP_FIELD);
        int connectedPackages = 0;

        for (Object packageName : packageLookup.keySet()) {
            if (packageName instanceof String name && parentLoaders.putIfAbsent(name, targetLoader) == null) {
                connectedPackages++;
            }
        }
        return connectedPackages;
    }

    static boolean hasPackage(ClassLoader loader, String packageName)
            throws ReflectiveOperationException {
        return readMap(loader, PACKAGE_LOOKUP_FIELD).containsKey(packageName);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> readMap(Object owner, String fieldName)
            throws ReflectiveOperationException {
        Field field = findField(owner.getClass(), fieldName);
        field.setAccessible(true);
        Object value = field.get(owner);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(fieldName + " is not a Map");
        }
        return (Map<K, V>) value;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
