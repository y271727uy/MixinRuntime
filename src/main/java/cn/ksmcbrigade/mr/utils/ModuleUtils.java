package cn.ksmcbrigade.mr.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.stream.Collectors;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ModuleLayerHandler;

public class ModuleUtils {

    /**
     * ModLauncher puts Forge and mod modules in completed game layers rather
     * than in Java's boot layer. Include both sets when changing module access.
     */
    private static Set<Module> allRuntimeModules() {
        Set<Module> modules = new HashSet<>(ModuleLayer.boot().modules());
        try {
            ModuleLayerHandler handler = UnsafeUtils.getFieldValue(
                    Launcher.INSTANCE, "moduleLayerHandler", ModuleLayerHandler.class);
            EnumMap<?, ?> completedLayers = UnsafeUtils.getFieldValue(
                    handler, "completedLayers", EnumMap.class);
            if (completedLayers != null) {
                for (Object layerInfo : completedLayers.values()) {
                    ModuleLayer layer = UnsafeUtils.getFieldValue(layerInfo, "layer", ModuleLayer.class);
                    if (layer != null) {
                        modules.addAll(layer.modules());
                    }
                }
            }
        } catch (Throwable ignored) {
            // The boot layer is still useful on launchers without completed game layers.
        }
        return modules;
    }

    private static final MethodHandles.Lookup IMPL_LOOKUP =
            UnsafeUtils.getFieldValue(MethodHandles.Lookup.class, "IMPL_LOOKUP", MethodHandles.Lookup.class);

    private static MethodHandle implAddOpens;
    private static MethodHandle implAddReads;
    private static MethodHandle implAddExports;

    static {
        try {
            implAddOpens = IMPL_LOOKUP.findVirtual(Module.class, "implAddOpens",
                    MethodType.methodType(void.class, String.class, Module.class));
            implAddReads = IMPL_LOOKUP.findVirtual(Module.class, "implAddReads",
                    MethodType.methodType(void.class, Module.class));
            implAddExports = IMPL_LOOKUP.findVirtual(Module.class, "implAddExports",
                    MethodType.methodType(void.class, String.class, Module.class));
        } catch (Throwable e) {
            System.err.println("[ModuleUtils] Failed to initialize method handles: " + e.getMessage());
        }
    }

    /**
     * 给所有模块添加对指定模块的 reads 和 opens
     */
    public static void openAllModulesToModule(Module targetModule) {
        allRuntimeModules().forEach(module -> {
            if (module != targetModule) {
                addReads(module, targetModule);

                // 开放所有包
                module.getDescriptor().packages().forEach(pkg -> {
                    addOpens(module, pkg, targetModule);
                    addExports(module, pkg, targetModule);
                });
            }
        });
    }

    /**
     * 给指定模块添加对所有模块的 reads 和 opens
     */
    public static void openModuleToAllModules(Module sourceModule) {
        allRuntimeModules().forEach(module -> {
            if (module != sourceModule) {
                addReads(sourceModule, module);

                // 读取目标模块的所有包
                module.getDescriptor().packages().forEach(pkg -> {
                    addOpens(sourceModule, pkg, module);
                    addExports(sourceModule, pkg, module);
                });
            }
        });
    }

    /**
     * 给所有模块互相开放所有包（最暴力的方式）
     */
    public static void openAllModules() {
        Set<Module> allModules = allRuntimeModules();

        for (Module sourceModule : allModules) {
            for (Module targetModule : allModules) {
                if (sourceModule != targetModule) {
                    addReads(sourceModule, targetModule);

                    sourceModule.getDescriptor().packages().forEach(pkg -> {
                        addOpens(sourceModule, pkg, targetModule);
                        addExports(sourceModule, pkg, targetModule);
                    });
                }
            }
        }

        System.out.println("[ModuleUtils] All modules opened to each other");
    }

    /**
     * 给指定包开放访问权限
     */
    public static void openPackage(String sourceModuleName, String packageName, String targetModuleName) {
        ModuleLayer.boot().findModule(sourceModuleName).ifPresent(sourceModule -> {
            ModuleLayer.boot().findModule(targetModuleName).ifPresent(targetModule -> {
                addOpens(sourceModule, packageName, targetModule);
                addExports(sourceModule, packageName, targetModule);
            });
        });
    }

    /**
     * 修复 LWJGL 和 Mixin 之间的模块访问
     */
    public static void fixLwjglMixinAccess() {
        System.out.println("[ModuleUtils] Fixing LWJGL <-> Mixin module access...");

        // 需要修复的 LWJGL 模块
        Set<Module> allModules = allRuntimeModules();
        List<Module> lwjglModules = allModules.stream()
                .filter(module -> module.getName() != null && module.getName().startsWith("org.lwjgl"))
                .collect(Collectors.toList());

        // Mixin 相关模块
        List<Module> mixinModules = allModules.stream()
                .filter(module -> module.getName() != null
                        && (module.getName().contains("mixin") || module.getName().contains("sponge")))
                .collect(Collectors.toList());

        // 互相开放
        for (Module lwjglModule : lwjglModules) {
            for (Module mixinModule : mixinModules) {
                addReads(lwjglModule, mixinModule);
                lwjglModule.getDescriptor().packages().forEach(pkg -> {
                    addOpens(lwjglModule, pkg, mixinModule);
                    addExports(lwjglModule, pkg, mixinModule);
                });
                System.out.println("[ModuleUtils] Opened " + lwjglModule.getName() + " -> " + mixinModule.getName());
            }
        }

        // 也给当前模块开放所有模块
        Module currentModule = ModuleUtils.class.getModule();
        allModules.forEach(module -> {
            if (module != currentModule) {
                addReads(currentModule, module);
                addReads(module, currentModule);
            }
        });
    }

    /**
     * 添加模块读取权限
     */
    public static void addReads(Module sourceModule, Module targetModule) {
        try {
            if (implAddReads != null) {
                implAddReads.invoke(sourceModule, targetModule);
            } else {
                // 备用方案：直接修改内部字段
                addReadsFallback(sourceModule, targetModule);
            }
        } catch (Throwable e) {
            try {
                addReadsFallback(sourceModule, targetModule);
            } catch (Throwable ignored) {

            }
        }
    }

    /**
     * 添加包打开权限
     */
    public static void addOpens(Module sourceModule, String packageName, Module targetModule) {
        try {
            if (implAddOpens != null) {
                implAddOpens.invoke(sourceModule, packageName, targetModule);
            } else {
                addOpensFallback(sourceModule, packageName, targetModule);
            }
        } catch (Throwable e) {
            try {
                addOpensFallback(sourceModule, packageName, targetModule);
            } catch (Throwable ignored) {

            }
        }
    }

    /**
     * 添加包导出权限
     */
    public static void addExports(Module sourceModule, String packageName, Module targetModule) {
        try {
            if (implAddExports != null) {
                implAddExports.invoke(sourceModule, packageName, targetModule);
            } else {
                addExportsFallback(sourceModule, packageName, targetModule);
            }
        } catch (Throwable e) {
            try {
                addExportsFallback(sourceModule, packageName, targetModule);
            } catch (Throwable ignored) {

            }
        }
    }

    /**
     * 备用方案：通过内部字段添加 reads
     */
    private static void addReadsFallback(Module sourceModule, Module targetModule) {
        try {
            Map<String, Set<Module>> extraReads = UnsafeUtils.getFieldValue(
                    sourceModule, "extraReads", Map.class);
            if (extraReads == null) {
                extraReads = new HashMap<>();
                UnsafeUtils.setFieldValue(sourceModule, "extraReads", extraReads);
            }
            extraReads.computeIfAbsent(targetModule.getName(), k -> new HashSet<>()).add(targetModule);
        } catch (Exception ignored) {

        }
    }

    /**
     * 备用方案：通过内部字段添加 opens
     */
    private static void addOpensFallback(Module sourceModule, String packageName, Module targetModule) {
        try {
            Map<String, Set<Module>> openPackages = UnsafeUtils.getFieldValue(
                    sourceModule, "openPackages", Map.class);
            if (openPackages == null) {
                openPackages = new HashMap<>();
                UnsafeUtils.setFieldValue(sourceModule, "openPackages", openPackages);
            }
            openPackages.computeIfAbsent(packageName, k -> new HashSet<>()).add(targetModule);
        } catch (Exception ignored) {

        }
    }

    /**
     * 备用方案：通过内部字段添加 exports
     */
    private static void addExportsFallback(Module sourceModule, String packageName, Module targetModule) {
        try {
            Map<String, Set<Module>> extraExports = UnsafeUtils.getFieldValue(
                    sourceModule, "extraExports", Map.class);
            if (extraExports == null) {
                extraExports = new HashMap<>();
                UnsafeUtils.setFieldValue(sourceModule, "extraExports", extraExports);
            }
            extraExports.computeIfAbsent(packageName, k -> new HashSet<>()).add(targetModule);
        } catch (Exception ignored) {}
    }
}
