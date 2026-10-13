// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
public final class ServerLifecycleProbe {
    public interface HandleAccess { Object getServer(); }
    public static final class Handle {
        boolean stopping, fail, error; int calls; boolean[] flags;
        public boolean hasStopped() { if (error) throw new IllegalStateException("test"); return stopping; }
        public boolean saveEverything(boolean silent, boolean flush, boolean force) {
            calls++; flags = new boolean[]{silent,flush,force};
            if (error) throw new IllegalStateException("test");
            return !fail;
        }
    }
    public static final class WrongSignature {
        public String hasStopped() { return "no"; }
        public String saveEverything(boolean a, boolean b, boolean c) { return "no"; }
    }
    interface Checked { void run() throws Exception; }
    static int checks;
    static void check(boolean b,String name) { if(!b) throw new AssertionError(name); checks++; }
    static IOException rejects(Checked c) throws Exception {
        try { c.run(); throw new AssertionError("expected rejection"); }
        catch(IOException e) { checks++; return e; }
    }
    public static void main(String[] args) throws Exception {
        Handle normal = new Handle(); Object[] handle={normal}; boolean[] main={true};
        Server proxy=(Server)Proxy.newProxyInstance(ServerLifecycleProbe.class.getClassLoader(),new Class<?>[]{Server.class,HandleAccess.class},(p,m,a)->switch(m.getName()) {
            case "getServer" -> handle[0]; case "isPrimaryThread" -> main[0];
            case "getLogger" -> Logger.getLogger("LifecycleProbe");
            case "getName" -> "Spigot"; case "getVersion", "getBukkitVersion" -> "26.2-R0.1-SNAPSHOT";
            case "toString" -> "LifecycleProbe"; case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==a[0]; default -> null;
        });
        Bukkit.setServer(proxy);
        ServerLifecycle.configureLanguage("en_US");
        ServerLifecycle.verifyCompatibility(); check(normal.calls==0,"verify has no save side effect");
        check(!ServerLifecycle.isStopping(),"running false"); normal.stopping=true;
        check(ServerLifecycle.isStopping(),"stopping true");
        ServerLifecycle.flushAll(); check(normal.calls==1 && normal.flags[0] && normal.flags[1] && normal.flags[2],"flush flags");
        normal.fail=true; check(rejects(ServerLifecycle::flushAll).getMessage().contains("successful"),"false result English");
        ServerLifecycle.configureLanguage("ja_JP"); check(rejects(ServerLifecycle::flushAll).getMessage().contains("保存"),"false result Japanese");
        normal.fail=false; normal.error=true; rejects(ServerLifecycle::flushAll);
        check(!ServerLifecycle.isStopping(),"throwing stop indicator fails closed");normal.error=false;
        main[0]=false;int calls=normal.calls; rejects(ServerLifecycle::flushAll);check(normal.calls==calls,"offthread no save");main[0]=true;
        handle[0]=new WrongSignature();rejects(ServerLifecycle::verifyCompatibility);rejects(ServerLifecycle::flushAll);check(!ServerLifecycle.isStopping(),"wrong signature closed");
        handle[0]=new Object();rejects(ServerLifecycle::verifyCompatibility);check(!ServerLifecycle.isStopping(),"missing method closed");
        handle[0]=null;rejects(ServerLifecycle::verifyCompatibility);check(!ServerLifecycle.isStopping(),"null handle closed");
        System.out.println("PASS: "+checks+" lifecycle adapter assertions");
    }
}
