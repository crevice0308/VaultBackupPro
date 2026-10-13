#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check final distribution JARs, then verify/load their actual classes in Java 25.

This intentionally does not substitute for the restore assertion harness or real
server acceptance tests. Mapping files and the report stay under build/.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_MAIN = "com.vaultbackup.plugin.VaultBackupPlugin"
RESTORE_MAIN = "com.vaultbackup.restore.RestoreMain"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def mapping(path: Path) -> tuple[dict[str, str], dict[tuple[str, str], str]]:
    classes: dict[str, str] = {}
    members: dict[tuple[str, str], str] = {}
    owner = ""
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([^ #].*) -> (\S+):", line)
        if match:
            owner, renamed = match.groups()
            classes[owner] = renamed
        elif owner and " -> " in line:
            original, renamed = line.strip().split(" -> ", 1)
            original = re.sub(r"^\d+:\d+:", "", original)
            original = re.sub(r":\d+(?::\d+)?$", "", original)
            members[owner, original] = renamed
    require(bool(classes), f"No class mappings in {path}")
    return classes, members


def inspect_jar(path: Path, classes: dict[str, str], helper: bool) -> dict:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), f"Duplicate ZIP entries in {path.name}")
        require(jar.testzip() is None, f"Corrupt ZIP entry in {path.name}")
        forbidden = [n for n in names if n.endswith((".java", ".map")) or
                     Path(n).name in {"mapping.txt", "plugin-mapping.txt", "restore-mapping.txt"}]
        require(not forbidden, f"Source/mapping leaked into executable JAR: {forbidden}")
        class_names = [n for n in names if n.endswith(".class")]
        require(all(n.startswith("com/vaultbackup/") for n in class_names),
                f"Unexpected shaded/runtime classes in {path.name}")
        if helper:
            require(all(n.startswith(("com/vaultbackup/core/", "com/vaultbackup/restore/"))
                        for n in class_names), "Standalone helper includes plugin/server classes")
        for entry in class_names:
            header = jar.read(entry)[:8]
            require(header[:4] == b"\xca\xfe\xba\xbe", f"Bad class magic: {entry}")
            require(int.from_bytes(header[6:8], "big") == 69, f"Not Java 25 bytecode: {entry}")
            require(int.from_bytes(header[4:6], "big") == 0, f"Preview bytecode is not allowed: {entry}")
        for original, renamed in classes.items():
            require(renamed.replace(".", "/") + ".class" in names,
                    f"Mapped class missing from {path.name}: {original}")
        renamed_count = sum(original != renamed for original, renamed in classes.items())
        require(renamed_count > 0, f"No class obfuscation in {path.name}")
        require(any(k.startswith("com.vaultbackup.core.") and k != v for k, v in classes.items()),
                f"Core implementation was not obfuscated in {path.name}")
        require(classes.get(RESTORE_MAIN) == RESTORE_MAIN, "Restore entrypoint was renamed")
        for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md"):
            require(jar.read("META-INF/" + name) == (ROOT / name).read_bytes(),
                    f"License/notice mismatch: {path.name}/{name}")
        if helper:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n")
            require(f"Main-Class: {RESTORE_MAIN}\n" in manifest, "Wrong helper Main-Class")
        else:
            require(classes.get(PLUGIN_MAIN) == PLUGIN_MAIN, "Bukkit plugin entrypoint was renamed")
            for name in ("plugin.yml", "config.yml", "revision.txt", "messages/ja_JP.properties",
                         "messages/en_US.properties"):
                require(jar.read(name) == (ROOT / "src/main/resources" / name).read_bytes(),
                        f"Changed/missing resource: {name}")
    return {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size, "classes": len(class_names),
            "renamed_classes": renamed_count, "java_class_major": 69}


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def reflection_probe(java: Path, jar: Path, classpath: str, classes: dict[str, str],
                     members: dict[tuple[str, str], str], plugin: bool) -> str:
    # Use the actual distribution classes as the classpath, never build/classes.
    literals = ",\n".join(java_string(name) for name in classes.values())
    plugin_checks = ""
    if plugin:
        messages = "com.vaultbackup.plugin.Messages"
        text_method = members[messages, "java.lang.String text(java.lang.String,java.lang.String,java.lang.Object[])"]
        plugin_checks = f"""
        Class<?> pluginClass = Class.forName({java_string(PLUGIN_MAIN)}, false, loader);
        check(Modifier.isPublic(pluginClass.getConstructor().getModifiers()), "plugin constructor");
        check(Modifier.isPublic(pluginClass.getDeclaredMethod("onEnable").getModifiers()), "onEnable");
        check(Modifier.isPublic(pluginClass.getDeclaredMethod("onDisable").getModifiers()), "onDisable");
        event({java_string(PLUGIN_MAIN)}, "worldLoaded", "org.bukkit.event.world.WorldLoadEvent");
        event({java_string(classes['com.vaultbackup.plugin.BackupMenus'])}, "click", "org.bukkit.event.inventory.InventoryClickEvent");
        event({java_string(classes['com.vaultbackup.plugin.BackupMenus'])}, "drag", "org.bukkit.event.inventory.InventoryDragEvent");
        event({java_string(classes['com.vaultbackup.plugin.BackupMenus'])}, "close", "org.bukkit.event.inventory.InventoryCloseEvent");
        event({java_string(classes['com.vaultbackup.plugin.BackupMenus'])}, "quit", "org.bukkit.event.player.PlayerQuitEvent");
        Class<?> commands = Class.forName({java_string(classes['com.vaultbackup.plugin.BackupCommands'])}, false, loader);
        method(commands, "onCommand", boolean.class, "org.bukkit.command.CommandSender", "org.bukkit.command.Command", "java.lang.String", "[Ljava.lang.String;");
        method(commands, "onTabComplete", java.util.List.class, "org.bukkit.command.CommandSender", "org.bukkit.command.Command", "java.lang.String", "[Ljava.lang.String;");
        method(Class.forName({java_string(classes['com.vaultbackup.plugin.BackupMenus$Menu'])}, false, loader), "getInventory", Class.forName("org.bukkit.inventory.Inventory", false, loader));
        Class<?> messages = Class.forName({java_string(classes[messages])}, true, loader);
        Method text = messages.getDeclaredMethod({java_string(text_method)}, String.class, String.class, Object[].class);
        text.setAccessible(true);
        Object en = text.invoke(null, "en_US", "common.yes", new Object[0]);
        Object ja = text.invoke(null, "ja_JP", "common.yes", new Object[0]);
        check(en instanceof String && !((String) en).isBlank(), "English resource lookup");
        check(ja instanceof String && !((String) ja).isBlank() && !ja.equals(en), "Japanese resource lookup");
        """
    source = f"""
import java.lang.reflect.*;
import java.lang.annotation.*;
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
        check(first.toString()!=null && !first.toString().isBlank(),"record bootstrap toString "+name);
        for(int i=0;i<types.length;i++) {{ Method accessor=c.getRecordComponents()[i].getAccessor(); accessor.setAccessible(true);
            check(Objects.equals(accessor.invoke(first),values[i]),"record value accessor "+name); }}
    }}
    public static void main(String[] ignored) throws Exception {{
        String[] names = {{{literals}}}; int records=0, enums=0;
        for(String name:names) {{
            Class<?> c=Class.forName(name,false,loader);
            c.getDeclaredConstructors(); c.getDeclaredFields(); c.getDeclaredMethods(); c.getDeclaredAnnotations();
            if(c.isRecord()) {{ records++; for(RecordComponent component:c.getRecordComponents()) {{
                check(component.getAccessor()!=null,"missing record accessor "+name);
                check(component.getAccessor().getName().equals(component.getName()),"record component/accessor mismatch "+name);
                check(component.getAccessor().getReturnType()==component.getType(),"record accessor type "+name);
                check(c.getDeclaredField(component.getName()).getType()==component.getType(),"record component/field mismatch "+name);
            }} }}
            if(c.isEnum()) {{ enums++; check(c.getEnumConstants()!=null,"enum constants "+name); }}
        }}
        check(records>0,"Record attributes not retained");
        Method main=Class.forName({java_string(RESTORE_MAIN)},false,loader).getDeclaredMethod("main",String[].class);
        check(main.getReturnType()==void.class && Modifier.isPublic(main.getModifiers()) && Modifier.isStatic(main.getModifiers()),"helper main");
        recordRoundTrip({java_string(classes['com.vaultbackup.core.BackupRecord'])},"probe",Instant.EPOCH,"label",Path.of("archive.zip"),Path.of("metadata.properties"),1L,2L,1,"digest");
        recordRoundTrip({java_string(classes['com.vaultbackup.restore.RestoreArchive$Entry'])},"world/probe.dat",false,2L,"digest");
        recordRoundTrip({java_string(classes['com.vaultbackup.restore.RestoreArchive$Verified'])},List.of(),0L,"digest");
        {plugin_checks}
        System.out.println("VERIFIED classes="+names.length+" records="+records+" enums="+enums+" recordRoundTrips=3");
    }}
}}
"""
    with tempfile.TemporaryDirectory(prefix="vaultbackup-obfuscation-check-") as directory:
        path = Path(directory) / "VerifyRelease.java"
        path.write_text(source, encoding="utf-8")
        cp = str(jar) + (os.pathsep + classpath if classpath else "")
        result = subprocess.run([str(java), "-Xverify:all", "--class-path", cp, str(path)],
                                capture_output=True, text=True, timeout=120)
        require(result.returncode == 0,
                f"Final-JAR JVM reflection check failed for {jar.name}:\n{result.stdout}\n{result.stderr}")
        require("VERIFIED classes=" in result.stdout, "No JVM verification result")
        return next(line for line in result.stdout.splitlines() if line.startswith("VERIFIED classes="))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, default=ROOT / "build/libs/VaultBackupPro-1.1.0.jar")
    parser.add_argument("--helper", type=Path, default=ROOT / "build/libs/vaultbackup-restore-1.1.0.jar")
    parser.add_argument("--plugin-mapping", type=Path, required=True)
    parser.add_argument("--helper-mapping", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    parser.add_argument("--compile-classpath", required=True,
                        help="Resolved Spigot compileClasspath, separated by the platform path separator")
    parser.add_argument("--report", type=Path, default=ROOT / "build/reports/obfuscation-verification.json")
    args = parser.parse_args()
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    require(java.is_file(), "--java-home / JAVA_HOME must identify a JDK 25 installation")
    plugin, helper = args.plugin.resolve(), args.helper.resolve()
    pm, pmm = mapping(args.plugin_mapping)
    hm, hmm = mapping(args.helper_mapping)
    result = {"plugin": inspect_jar(plugin, pm, False), "helper": inspect_jar(helper, hm, True)}
    with zipfile.ZipFile(plugin) as jar:
        require(jar.read("vaultbackup-restore.jar") == helper.read_bytes(),
                "Embedded helper is not byte-identical to the standalone final helper")
    result["embedded_helper_identical"] = True
    result["helper"]["jvm_probe"] = reflection_probe(java, helper, "", hm, hmm, False)
    result["plugin"]["jvm_probe"] = reflection_probe(java, plugin, args.compile_classpath, pm, pmm, True)
    smoke = subprocess.run([str(java), "-Xverify:all", "-jar", str(helper)],
                           capture_output=True, text=True, timeout=30)
    require(smoke.returncode == 2 and "--job" in smoke.stderr and "RESTORE REFUSED OR FAILED" in smoke.stderr,
            f"Standalone helper did not fail closed on missing arguments: {smoke.returncode}\n{smoke.stderr}")
    result["helper"]["no_argument_exit"] = 2
    result["scope"] = "Final-JAR structure, JVM verification, reflection, record/enum and entrypoint smoke checks; run restoreTest and real-server acceptance separately."
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
