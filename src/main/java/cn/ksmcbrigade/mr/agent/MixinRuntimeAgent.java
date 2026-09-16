package cn.ksmcbrigade.mr.agent;

import java.lang.instrument.Instrumentation;

public class MixinRuntimeAgent {

    public static void agentmain(String args, Instrumentation inst){
        MixinRuntimeAgentBootstrap.agentmain(args, inst);
    }

    public static void premain(String args,Instrumentation inst){
        agentmain(args, inst);
    }
}
