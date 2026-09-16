package cn.ksmcbrigade.mr.agent;

import cn.ksmcbrigade.mr.utils.modlauncher.FastEventClassLoaderBridge;

import java.lang.instrument.Instrumentation;

public final class MixinRuntimeAgentBootstrap {

    private MixinRuntimeAgentBootstrap() {
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        System.getProperties().put("inst", instrumentation);
        FastEventClassLoaderBridge.install(instrumentation);
    }

    public static void premain(String args, Instrumentation instrumentation) {
        agentmain(args, instrumentation);
    }
}
