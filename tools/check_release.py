#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check the two release JARs, then load and verify their actual classes in Java 25.

The plugin JAR must run on two server APIs, so its classes are loaded twice: all of them with
the Paper API on the class path, and all but the Paper implementation with the Spigot API.
This does not replace the restore harnesses or a test on a real server.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_MAIN = "com.vaultbackup.plugin.VaultBackupPlugin"
RESTORE_MAIN = "com.vaultbackup.restore.RestoreMain"
PAPER_ONLY = ("com.vaultbackup.plugin.PaperPlatform", "com.vaultbackup.plugin.PaperCommands")
LICENSE_FILES = ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md")
UNCHANGED_RESOURCES = ("config.yml", "messages/ja_JP.properties", "messages/en_US.properties")
VERSIONED_RESOURCES = ("plugin.yml", "revision.txt")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def inspect_jar(path: Path, version: str, helper: bool) -> tuple[dict, list[str]]:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), f"Duplicate ZIP entries in {path.name}")
        require(jar.testzip() is None, f"Corrupt ZIP entry in {path.name}")
        leaked = [n for n in names if n.endswith((".java", ".map", ".kts", ".gradle")) or "mapping" in Path(n).name]
        require(not leaked, f"Source or build files leaked into {path.name}: {leaked}")
        class_entries = [n for n in names if n.endswith(".class")]
        require(class_entries, f"No classes in {path.name}")
        require(all(n.startswith("com/vaultbackup/") for n in class_entries),
                f"Classes of another project are packaged in {path.name}")
        if helper:
            require(all(n.startswith(("com/vaultbackup/core/", "com/vaultbackup/restore/")) for n in class_entries),
                    "Standalone helper includes plugin or server classes")
        for entry in class_entries:
            header = jar.read(entry)[:8]
            require(header[:4] == b"\xca\xfe\xba\xbe", f"Bad class magic: {entry}")
            require(int.from_bytes(header[6:8], "big") == 69, f"Not Java 25 bytecode: {entry}")
            require(int.from_bytes(header[4:6], "big") == 0, f"Preview bytecode is not allowed: {entry}")
        for name in LICENSE_FILES:
            require(jar.read("META-INF/" + name) == (ROOT / name).read_bytes(),
                    f"License/notice mismatch: {path.name}/{name}")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n")
        if helper:
            require(f"Main-Class: {RESTORE_MAIN}\n" in manifest, "Wrong helper Main-Class")
            require(not [n for n in names if n.endswith((".yml", ".properties"))], "Helper carries plugin resources")
        else:
            require("Main-Class:" not in manifest, "The plugin JAR must not be an executable JAR")
            for name in UNCHANGED_RESOURCES:
                require(jar.read(name) == (ROOT / "src/main/resources" / name).read_bytes(),
                        f"Changed or missing resource: {name}")
            for name in VERSIONED_RESOURCES:
                text = jar.read(name).decode("utf-8")
                require("${" not in text, f"Unexpanded placeholder in {name}")
                require(version in text, f"{name} does not carry version {version}")
            plugin_yml = jar.read("plugin.yml").decode("utf-8")
            require(f"version: '{version}'" in plugin_yml, "plugin.yml version")
            require(f"main: {PLUGIN_MAIN}" in plugin_yml, "plugin.yml main class")
            require("vaultbackup-restore.jar" in names, "Embedded restore helper is missing")
            nested = [n for n in names if n.endswith(".jar")]
            require(nested == ["vaultbackup-restore.jar"], f"Unexpected nested JARs: {nested}")
    classes = sorted(n[:-len(".class")].replace("/", ".") for n in class_entries)
    report = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "bytes": path.stat().st_size, "classes": len(classes), "java_class_major": 69}
    return report, classes


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def jvm_probe(java: Path, jar: Path, classpath: str, classes: list[str], plugin: bool, paper: bool) -> str:
    """Loads every class from the release JAR itself (never from build/classes) with full bytecode verification."""
    literals = ",\n".join(java_string(name) for name in classes)
    plugin_checks = ""
    if plugin:
        plugin_checks = f"""
        Class<?> pluginClass = Class.forName({java_string(PLUGIN_MAIN)}, false, loader);
        check(Modifier.isPublic(pluginClass.getConstructor().getModifiers()), "plugin constructor");
        check(Modifier.isPublic(pluginClass.getDeclaredMethod("onEnable").getModifiers()), "onEnable");
        check(Modifier.isPublic(pluginClass.getDeclaredMethod("onDisable").getModifiers()), "onDisable");
        event({java_string(PLUGIN_MAIN)}, "worldLoaded", "org.bukkit.event.world.WorldLoadEvent");
        event("com.vaultbackup.plugin.BukkitPlatform", "worldSaved", "org.bukkit.event.world.WorldSaveEvent");
        event("com.vaultbackup.plugin.BackupMenus", "click", "org.bukkit.event.inventory.InventoryClickEvent");
        event("com.vaultbackup.plugin.BackupMenus", "drag", "org.bukkit.event.inventory.InventoryDragEvent");
        event("com.vaultbackup.plugin.BackupMenus", "close", "org.bukkit.event.inventory.InventoryCloseEvent");
        event("com.vaultbackup.plugin.BackupMenus", "quit", "org.bukkit.event.player.PlayerQuitEvent");
        Class<?> commands = Class.forName("com.vaultbackup.plugin.BackupCommands", false, loader);
        method(commands, "onCommand", boolean.class, "org.bukkit.command.CommandSender", "org.bukkit.command.Command", "java.lang.String", "[Ljava.lang.String;");
        method(commands, "onTabComplete", java.util.List.class, "org.bukkit.command.CommandSender", "org.bukkit.command.Command", "java.lang.String", "[Ljava.lang.String;");
        method(Class.forName("com.vaultbackup.plugin.BackupMenus$Menu", false, loader), "getInventory", Class.forName("org.bukkit.inventory.Inventory", false, loader));
        Class<?> platform = Class.forName("com.vaultbackup.plugin.ServerPlatform", false, loader);
        check(platform.isAssignableFrom(Class.forName("com.vaultbackup.plugin.SpigotPlatform", false, loader)), "Spigot platform type");
        Method available = platform.getDeclaredMethod("paperApiAvailable"); available.setAccessible(true);
        check(available.invoke(null).equals({str(paper).lower()}), "Paper API detection must be {str(paper).lower()} on this class path");
        Class<?> messages = Class.forName("com.vaultbackup.plugin.Messages", true, loader);
        Method text = messages.getDeclaredMethod("text", String.class, String.class, Object[].class);
        text.setAccessible(true);
        Object en = text.invoke(null, "en_US", "common.yes", new Object[0]);
        Object ja = text.invoke(null, "ja_JP", "common.yes", new Object[0]);
        check(en instanceof String && !((String) en).isBlank(), "English resource lookup");
        check(ja instanceof String && !((String) ja).isBlank() && !ja.equals(en), "Japanese resource lookup");
        """
        if paper:
            plugin_checks += """
        Class<?> paperPlatform = Class.forName("com.vaultbackup.plugin.PaperPlatform", false, loader);
        check(platform.isAssignableFrom(paperPlatform), "Paper platform type");
        paperPlatform.getDeclaredConstructor(pluginClass);
        Class.forName("com.vaultbackup.plugin.PaperCommands", true, loader);
        """
    source = f"""
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public final class VerifyRelease {{
    private static final ClassLoader loader = ClassLoader.getSystemClassLoader();
    private static void check(boolean value, String message) {{ if (!value) throw new AssertionError(message); }}
    private static Method method(Class<?> owner, String name, Class<?> result, String... arguments) throws Exception {{
        Class<?>[] types = new Class<?>[arguments.length];
        for (int i=0;i<types.length;i++) types[i]=Class.forName(arguments[i],false,loader);
        Method m=owner.getDeclaredMethod(name,types);
        check(Modifier.isPublic(m.getModifiers()) && m.getReturnType()==result,"callback "+name); return m;
    }}
    private static void event(String owner, String name, String argument) throws Exception {{
        Method m=method(Class.forName(owner,false,loader),name,void.class,argument);
        check(Arrays.stream(m.getDeclaredAnnotations()).anyMatch(a->a.annotationType().getName().equals("org.bukkit.event.EventHandler")),"event annotation "+name);
    }}
    private static void recordRoundTrip(String name, Object... values) throws Exception {{
        Class<?> c=Class.forName(name,true,loader);
        Class<?>[] types=Arrays.stream(c.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Constructor<?> constructor=c.getDeclaredConstructor(types); constructor.setAccessible(true);
        Object first=constructor.newInstance(values), second=constructor.newInstance(values);
        check(first.equals(second) && first.hashCode()==second.hashCode(),"record equals/hashCode "+name);
        for(int i=0;i<types.length;i++) {{ Method accessor=c.getRecordComponents()[i].getAccessor(); accessor.setAccessible(true);
            check(Objects.equals(accessor.invoke(first),values[i]),"record value accessor "+name); }}
    }}
    public static void main(String[] ignored) throws Exception {{
        String[] names = {{{literals}}}; int records=0, enums=0;
        for(String name:names) {{
            // Resolving the members links the class against the server API on the class path.
            Class<?> c=Class.forName(name,false,loader);
            c.getDeclaredConstructors(); c.getDeclaredFields(); c.getDeclaredMethods(); c.getDeclaredAnnotations();
            if(c.isRecord()) records++;
            if(c.isEnum()) {{ enums++; check(c.getEnumConstants()!=null,"enum constants "+name); }}
        }}
        Method main=Class.forName({java_string(RESTORE_MAIN)},false,loader).getDeclaredMethod("main",String[].class);
        check(main.getReturnType()==void.class && Modifier.isPublic(main.getModifiers()) && Modifier.isStatic(main.getModifiers()),"helper main");
        recordRoundTrip("com.vaultbackup.core.BackupRecord","probe",Instant.EPOCH,"label",Path.of("archive.zip"),Path.of("metadata.properties"),1L,2L,1,"digest",0);
        recordRoundTrip("com.vaultbackup.restore.RestoreArchive$Entry","world/probe.dat",false,2L,"digest");
        {plugin_checks}
        System.out.println("VERIFIED classes="+names.length+" records="+records+" enums="+enums);
    }}
}}
"""
    with tempfile.TemporaryDirectory(prefix="vaultbackup-release-check-") as directory:
        path = Path(directory) / "VerifyRelease.java"
        path.write_text(source, encoding="utf-8")
        cp = str(jar) + (os.pathsep + classpath if classpath else "")
        environment = {k: v for k, v in os.environ.items() if k not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
        result = subprocess.run([str(java), "-Xverify:all", "--class-path", cp, str(path)],
                                capture_output=True, text=True, timeout=180, env=environment)
        require(result.returncode == 0,
                f"Release JAR JVM check failed for {jar.name}:\n{result.stdout}\n{result.stderr}")
        return next(line for line in result.stdout.splitlines() if line.startswith("VERIFIED classes="))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    parser.add_argument("--spigot-classpath", required=True, help="Resolved Spigot API class path")
    parser.add_argument("--paper-classpath", required=True, help="Resolved Paper API class path")
    parser.add_argument("--report", type=Path, default=ROOT / "build/reports/release-verification.json")
    args = parser.parse_args()
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    require(java.is_file(), "--java-home / JAVA_HOME must identify a JDK 25 installation")
    plugin, helper = args.plugin.resolve(), args.helper.resolve()
    require(plugin.name == f"VaultBackupPro-{args.version}.jar", f"Unexpected plugin file name: {plugin.name}")
    require(helper.name == f"vaultbackup-restore-{args.version}.jar", f"Unexpected helper file name: {helper.name}")
    plugin_report, plugin_classes = inspect_jar(plugin, args.version, False)
    helper_report, helper_classes = inspect_jar(helper, args.version, True)
    require(all(name in plugin_classes for name in helper_classes), "Plugin lacks classes the helper has")
    for name in PAPER_ONLY:
        require(name in plugin_classes, f"Paper implementation class is missing: {name}")
    with zipfile.ZipFile(plugin) as jar:
        require(jar.read("vaultbackup-restore.jar") == helper.read_bytes(),
                "Embedded helper is not byte-identical to the standalone helper")
    common = [name for name in plugin_classes if not name.startswith(PAPER_ONLY)]
    result = {"plugin": plugin_report, "helper": helper_report, "embedded_helper_identical": True}
    result["helper"]["jvm_probe"] = jvm_probe(java, helper, "", helper_classes, False, False)
    result["plugin"]["jvm_probe_paper_api"] = jvm_probe(java, plugin, args.paper_classpath, plugin_classes, True, True)
    result["plugin"]["jvm_probe_spigot_api"] = jvm_probe(java, plugin, args.spigot_classpath, common, True, False)
    environment = {k: v for k, v in os.environ.items() if k not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    smoke = subprocess.run([str(java), "-Xverify:all", "-jar", str(helper)],
                           capture_output=True, text=True, timeout=30, env=environment)
    require(smoke.returncode == 2 and "--job" in smoke.stderr and "RESTORE REFUSED OR FAILED" in smoke.stderr,
            f"Standalone helper did not fail closed on missing arguments: {smoke.returncode}\n{smoke.stderr}")
    result["helper"]["no_argument_exit"] = 2
    result["scope"] = ("Release JAR structure, JVM bytecode verification and linking against the Paper and Spigot APIs, "
                       "entry points and resources. The restore harnesses and real-server tests are separate.")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
