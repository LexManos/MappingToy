/*
 * Copyright (c) LexManos
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.lex.mappingtoy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraftforge.lex.mappingtoy.JarMetadata.ClassInfo.FieldInfo;
import net.minecraftforge.lex.mappingtoy.JarMetadata.ClassInfo.MethodInfo;
import net.minecraftforge.lex.mappingtoy.JarMetadata.ClassInfo.RecordInfo;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class JarMetadata {
    private static boolean DEBUG = Boolean.parseBoolean(System.getProperty("toy.debugLambdas", "false"));
    private static final Handle LAMBDA_METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",       "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle LAMBDA_ALTMETAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle RECORD_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/ObjectMethods", "bootstrap", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;", false);

    public static void makeMetadata(Path output, Collection<Path> libraries, IMappingFile n2o, String type, boolean obfed, boolean force) {
        Path target = output.resolve(type + "_meta.json");
        if (!force && Files.isRegularFile(target))
            return;

        MappingToy.log.info("  " + target.getFileName());

        IMappingFile o2n = n2o.reverse();
        Tree tree = new Tree();

        Set<String> classes = tree.load(output.resolve(type + ".jar"), false);

        for (Path lib : libraries)
            tree.load(lib, true);

        for (String cls : classes)
            resolveBouncers(tree, tree.getInfo(cls));

        for (String cls : classes)
            resolve(tree, cls, obfed, o2n, n2o);

        for (String cls : classes)
            resolveTransitive(tree, tree.getInfo(cls));

        Map<String, ClassInfo> data = new TreeMap<>();
        for (String cls : classes)
            data.put(cls, tree.getInfo(cls));

        try {
            Utils.writeJson(target, data);
        } catch (IOException e) {
            MappingToy.log.log(Level.SEVERE, "    Failed to save meta: " + e.toString());
        }
    }

    private static void resolveBouncers(Tree tree, ClassInfo cls) {
        if (cls == null || cls.methods == null)
            return;
        
        for (MethodInfo mtd : cls.methods.values()) {
            if (mtd.bouncer != null) {
                Method target = mtd.bouncer.target;
                ClassInfo cls2 = tree.getInfo(target.owner);
                if (cls2 != null && cls2.methods != null) {
                    MethodInfo m = cls2.methods.get(target.getName() + target.getDesc());
                    if (m != null) {
                        m.getTargetsThis().add(new Method(mtd.getOwnerName(), mtd.getName(), mtd.getDesc()));
                    }
                }
            }
        }
    }

    //Recursive, but should be fine as we don't have class super complex class trees
    private static void resolve(Tree tree, String cls, boolean obfed, IMappingFile o2n, IMappingFile n2o) {
        ClassInfo info = tree.getInfo(cls);
        if (info == null || info.resolved)
            return;

        if (info.getSuper() != null)
            resolve(tree, info.getSuper(), obfed, o2n, n2o);

        if (info.interfaces != null)
            for (String intf : info.interfaces)
                resolve(tree, intf, obfed, o2n, n2o);

        //Gather official enum names, we know these names and can use them as they are in the bytecode itself. It's also required to make enums compile correctly.
        if (info.isEnum()) {
            IClass mcls = obfed ? o2n.getClass(cls) : n2o.getClass(cls);

            if (info.fields != null) {
                final int FLAG = Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;

                for (FieldInfo fld : info.fields.values()) {
                    String official = obfed ? mcls == null ? null : mcls.remapField(fld.name) : fld.name;
                    if (official != null && ((fld.getAccess() & FLAG) == FLAG || "$VALUES".equals(official))) {
                        fld.forceName(official);
                    }
                }
            }

            if (info.methods != null) {
                for (MethodInfo mtd : info.methods.values()) {
                    String official = obfed ? mcls == null ? null : mcls.remapMethod(mtd.getName(), mtd.getDesc()) : mtd.getName();
                    if ("values".equals(official) && mtd.getDesc().equals("()[L" + info.name + ';'))
                        mtd.forceName("values");
                    else if ("valueOf".equals(official) && mtd.getDesc().equals("(Ljava/lang/String;)L" + info.name + ';'))
                        mtd.forceName("valueOf");
                }
            }
        }
        //TODO: Synthetics/Accessors?

        if (info.methods != null) {
            //Synthetic Bouncers!
            for (MethodInfo mtd : info.methods.values()) {
                if (mtd.bouncer != null) {
                    if (!mtd.bouncer.target.owner.equals(info.name)) {
                        Method target = findMethodContent(tree, mtd.bouncer.target);
                        if (target != null)
                            mtd.bouncer.setTarget(target);
                    }
                    
                    Method owner = walkBouncers(tree, mtd, info.name);
                    if (owner != null && !owner.owner.equals(info.name))
                        mtd.bouncer.setOwner(owner);
                }
            }

            //Resolve the 'root' owner of each method.
            for (MethodInfo mtd : info.methods.values()) {
                mtd.setOverrides(findOverrides(tree, mtd, info.name, new TreeSet<>()));
                mtd.setParent(findFirstParent(tree, mtd, info.name));
            }
        }

        if (!info.isAbstract()) {
            resolveAbstract(tree, info);
        }

        resolveRecord(info);

        info.resolved = true;
    }

    private static Method walkBouncers(Tree tree, MethodInfo mtd, String owner) {
        ClassInfo info = tree.getInfo(owner);

        if (info == null)
            return null;

        if (info.methods != null) {
            MethodInfo mine = info.methods.get(mtd.getName() + mtd.getDesc());
            if (mine != null && ((mine.getAccess() & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == 0 || owner.equals(mtd.getOwnerName()))) {
                if (mine.bouncer == null) {
                    Set<Method> owners = findOverrides(tree, mine, owner, new HashSet<>());
                    if (owners.isEmpty())
                        return new Method(owner, mine.getName(), mine.getDesc());
                    else if (owners.size() == 1)
                        return owners.iterator().next();
                    else //We can't find just one owner... something's fucky...
                        return owners.iterator().next(); //Just pick one...
                }

                for (Method mtd2 : mine.getTargetsThis()) {
                    ClassInfo info2 = tree.getInfo(mtd2.owner);
                    if (info2 != null && info2.methods != null) {
                        MethodInfo m2 = info2.methods.get(mtd2.getName() + mtd2.getDesc());
                        if (m2.bouncer.owner != null)
                            return m2.bouncer.owner;

                        Method ret = walkBouncers(tree, m2, owner);
                        if (ret != null && !ret.owner.equals(owner)) {
                            m2.bouncer.setOwner(ret);
                            return ret;
                        } else {
                            MappingToy.log.warning("    Unable to walk: " + m2.getName() + ' ' + m2.getDesc() + " for " + owner + '/' + mine.getName() + ' ' + mine.getDesc());
                        }
                    }
                }
            }
        }

        if (info.getSuper() != null) {
            Method ret = walkBouncers(tree, mtd, info.getSuper());
            if (ret != null)
                return ret;
        }

        if (info.interfaces != null) {
            for (String intf : info.interfaces) {
                Method ret = walkBouncers(tree, mtd, intf);
                if (ret != null)
                    return ret;
            }
        }

        return null;
    }

    private static Set<Method> findOverrides(Tree tree, MethodInfo mtd, String owner, Set<Method> overrides) {
        if (mtd.isStatic() || mtd.isPrivate() || mtd.getName().startsWith("<"))
            return overrides;

        ClassInfo info = tree.getInfo(owner);

        if (info == null)
            return overrides;

        for (Method mtd2 : mtd.getTargetsThis()) {
            ClassInfo info2 = tree.getInfo(mtd2.owner);
            if (info2 != null && info2.methods != null) {
                MethodInfo m = info2.methods.get(mtd2.getName() + mtd2.getDesc());
                //overrides.add(new Method(info.name, m.name, m.desc)); //Don't add overrides for self-methods
                findOverrides(tree, m, info.name, overrides);
            }
        }

        if (info.methods != null) {
            MethodInfo mine = info.methods.get(mtd.getName() + mtd.getDesc());
            if (mine != null && mine != mtd && (mine.getAccess() & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == 0) {
                if (mine.getOverrides().isEmpty()) {
                    overrides.add(new Method(info.name, mine.getName(), mine.getDesc()));
                } else {
                    overrides.addAll(mine.getOverrides());
                }
            }
        }

        if (info.getSuper() != null)
            findOverrides(tree, mtd, info.getSuper(), overrides);

        if (info.interfaces != null) {
            for (String intf : info.interfaces)
                findOverrides(tree, mtd, intf, overrides);
        }

        return overrides;
    }
    
    private static Method findMethodContent(Tree tree, Method mtd) {
        ClassInfo info = tree.getInfo(mtd.owner);
        
        if (info == null)
            return null;
        
        MethodInfo mine = info.methods.get(mtd.getName() + mtd.getDesc());
        if (mine != null) {
            return new Method(info.name, mtd.getName(), mtd.getDesc());
        }
        
        if (info.getSuper() != null) {
            Method superMtd = findMethodContent(tree, new Method(info.getSuper(), mtd.getName(), mtd.getDesc()));
            if (superMtd != null)
                return superMtd;
        }
        
        if (info.interfaces != null) {
            for (String intf : info.interfaces) {
                Method intMtd = findMethodContent(tree, new Method(intf, mtd.getName(), mtd.getDesc()));
                if (intMtd != null)
                    return intMtd;
            }
        }
        
        return null;
        
    }

    private static Method findFirstParent(Tree tree, MethodInfo mtd, String owner) {
        if (mtd.isStatic() || mtd.isPrivate() || mtd.getName().startsWith("<"))
            return null;

        ClassInfo info = tree.getInfo(owner);

        if (info == null)
            return null;

        if (info.methods != null) {
            MethodInfo mine = info.methods.get(mtd.getName() + mtd.getDesc());
            if (info.isLocal() && mine != null && mine != mtd && (mine.getAccess() & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == 0)
                return new Method(info.name, mine.getName(), mine.getDesc());

        }

        for (Method mtd2 : mtd.getTargetsThis()) {
            ClassInfo info2 = tree.getInfo(mtd2.owner);
            if (info2 != null && info2.methods != null) {
                MethodInfo m = info2.methods.get(mtd2.getName() + mtd2.getDesc());
                Method ret = findFirstParent(tree, m, info.name);
                if (ret != null)
                    return ret;
            }
        }

        if (info.getSuper() != null) {
            Method ret = findFirstParent(tree, mtd, info.getSuper());
            if (ret != null)
                return ret;
        }

        if (info.interfaces != null) {
            for (String intf : info.interfaces) {
                Method ret = findFirstParent(tree, mtd, intf);
                if (ret != null)
                    return ret;
            }
        }

        return null;
    }

    private static void resolveAbstract(Tree tree, ClassInfo cls) {
        Map<String, String> abs = new HashMap<>();
        Set<String> known = new TreeSet<>();
        Queue<String> que = new LinkedList<>();
        Consumer<String> add = c -> {
            if (!known.contains(c)) {
                que.add(c);
                known.add(c);
            }
        };

        add.accept(cls.name);

        while (!que.isEmpty()) {
            ClassInfo info = tree.getInfo(que.poll());
            if (info == null)
                continue;

            if (info.methods != null)
                info.methods.values().stream()
                .filter(MethodInfo::isAbstract)
                .filter(mtd -> mtd.overrides == null) //We only want the roots
                .forEach(mtd -> abs.put(mtd.getName() + mtd.getDesc(), info.name));

            if (info.getSuper() != null)
                add.accept(info.getSuper());

            if (info.interfaces != null)
                info.interfaces.forEach(add);
        }

        known.clear();
        add.accept(cls.name);

        while (!que.isEmpty()) {
            ClassInfo info = tree.getInfo(que.poll());
            if (info == null)
                continue;

            if (info.methods != null) {
                for (MethodInfo mtd : info.methods.values()) {
                    if (mtd.isAbstract())
                        continue;

                    String towner = abs.remove(mtd.getName() + mtd.getDesc());
                    if (towner == null)
                        continue;
                    Method target = new Method(towner, mtd.getName(), mtd.getDesc());

                    if (mtd.overrides != null) {
                        /* What was this doing in the first place?
                        for (Method omh : mtd.overrides) {
                            ClassInfo ocls = tree.getInfo(omh.owner);
                            if (towner.equals(omh.owner) || ocls == null) //Error?
                                continue;
                            MethodInfo omtd = ocls.methods == null ? null : ocls.methods.get(omh.name + omh.desc);
                            if (omtd == null) //Error?
                                continue;
                            if (omtd.overrides != null)
                                omtd.overrides.add(target);
                            else
                                omtd.setOverrides(new HashSet<>(Arrays.asList(target)));
                            break;
                        }
                        */
                        mtd.overrides.add(target);
                    } else {
                        mtd.setOverrides(new HashSet<>(Arrays.asList(target)));
                    }
                }
            }

            if (info.getSuper() != null)
                add.accept(info.getSuper());

            if (info.interfaces != null)
                info.interfaces.forEach(add);
        }

        if (!abs.isEmpty()) {
            MappingToy.log.log(Level.SEVERE, "    Unresolved abstracts for: " + cls.name);
            abs.forEach((mtd,c) -> MappingToy.log.log(Level.SEVERE, "      " + c + "/" + mtd));
        }
    }

    private static void resolveTransitive(Tree tree, ClassInfo cls) {
        if (!cls.isInterface() || cls.methods == null)
            return;

        Set<ClassInfo> children = new HashSet<>();
        for (ClassInfo info : tree.classes.values())
            if (info != cls && tree.instanceOf(info, cls))
                children.add(info);

        for (MethodInfo myMtd : cls.methods.values()) {
            if (myMtd.isStatic() || myMtd.isPrivate())
                continue;

            Set<MethodInfo> overrides = new HashSet<>();
            Set<MethodInfo> applyForcedName = new HashSet<>();

            for (ClassInfo child : children) {
                Queue<ClassInfo> que = new LinkedList<>(Arrays.asList(child));
                Set<String> seen = new HashSet<>(Arrays.asList(child.name));

                while (!que.isEmpty()) {
                    ClassInfo c = que.poll();
                    if (c.getSuper() != null && !seen.contains(c.getSuper())) {
                        que.add(tree.getInfo(c.getSuper()));
                        seen.add(c.getSuper());
                    }

                    if (c.interfaces != null) {
                        for (String inf : c.interfaces) {
                            if (!seen.contains(inf)) {
                                que.add(tree.getInfo(inf));
                                seen.add(inf);
                            }
                        }
                    }

                    if (c.methods != null) {
                        for (MethodInfo mtd : c.methods.values()) {
                            if (mtd.isStatic() || mtd.isPrivate() || mtd.getName().startsWith("<"))
                                continue;
                            if (!mtd.getName().equals(myMtd.getName()) || !mtd.getDesc().equals(myMtd.getDesc()))
                                continue;
                            if (!c.isInterface() || tree.instanceOf(c, cls)) {
                                overrides.add(mtd);
                            }
                            applyForcedName.add(mtd);
                        }
                    }
                }
            }

            if (!overrides.isEmpty()) {
                for (MethodInfo m : overrides) {
                    if (m.getOwner() != cls) {
                        Set<Method> ovs = new TreeSet<>(m.getOverrides());
                        ovs.add(myMtd.getMethod());
                        m.setOverrides(ovs);
                    }
                }
                
                String forcedName = null;
                for (MethodInfo override : applyForcedName) {
                    if (!override.getOwner().isLocal()) {
                        forcedName = override.getName();
                        break;
                    }
                }
                
                for (MethodInfo m : applyForcedName) {
                    if (forcedName != null) {
                        m.forceName(forcedName);
                    }
                }
            }
        }
    }

    private static void resolveRecord(ClassInfo cls) {
        if (!cls.isRecord || cls.records == null)
            return;

        if (cls.fields == null)
            cls.records = null;
        else {
            for (RecordInfo rec : cls.records) {
                FieldInfo fld = cls.fields.get(rec.field);
                if (fld != null && fld.getters != null)
                    rec.methods = fld.getters.stream().map(m -> m.method.name).collect(Collectors.toList());
                if (rec.methods != null && rec.methods.size() > 1)
                    System.out.println("Record: " + cls.name);
            }
        }
    }

    private static class Tree {
        private Map<String, ClassInfo> classes = new HashMap<>();
        private Set<String> negative = new HashSet<>();
        private Map<String, byte[]> sources = new HashMap<>();
        private Set<String> local = new HashSet<>();

        public Set<String> load(Path path, boolean library) {
            try (ZipInputStream jin = new ZipInputStream(Files.newInputStream(path))) {
                Set<String> classes = new TreeSet<>();

                ZipEntry entry = null;
                while ((entry = jin.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class"))
                        continue;

                    String cls = name.substring(0, name.length() - 6);
                    if (!sources.containsKey(cls)) {
                        byte[] data = Utils.readStreamFully(jin);
                        sources.put(cls, data);
                        classes.add(cls);
                        if (!library)
                            local.add(cls);
                    }
                }

                return classes;
            } catch (IOException e) {
                MappingToy.log.log(Level.SEVERE, "Failed to load: " + path.toString(), e);
            }
            return Collections.emptySet();
        }

        private ClassInfo getInfo(String cls) {
            if (negative.contains(cls))
                return null;

            ClassInfo ret = classes.get(cls);
            if (ret == null) {
                byte[] data = sources.remove(cls);
                if (data == null) {
                    try (InputStream in = JarMetadata.class.getClassLoader().getResourceAsStream(cls + ".class")) {
                        if (in == null) {
                            MappingToy.log.info("    Failed to find class: " + cls);
                            negative.add(cls);
                            return null;
                        }
                        data = Utils.readStreamFully(in);
                    } catch (Throwable e) {
                        MappingToy.log.info("    Failed to find class: " + cls);
                        negative.add(cls);
                        return null;
                    }
                }
                ClassNode classNode = new ClassNode();
                ClassReader classReader = new ClassReader(data);
                classReader.accept(classNode, 0);
                ret = new ClassInfo(classNode, local.contains(cls));
                classes.put(cls, ret);
            }
            return ret;
        }

        public boolean instanceOf(ClassInfo child, ClassInfo target) {
            Queue<ClassInfo> que = new LinkedList<>();
            Set<String> seen = new HashSet<>();
            que.add(child);
            seen.add(child.name);
            while (!que.isEmpty()) {
                ClassInfo info = que.poll();
                if (info == target)
                    return true;

                String sup = info.getSuper();
                if (sup != null && !seen.contains(sup)) {
                    que.add(getInfo(sup));
                    seen.add(sup);
                }

                if (info.interfaces != null) {
                    for (String inf : info.interfaces) {
                        if (!seen.contains(inf)) {
                            que.add(getInfo(inf));
                            seen.add(inf);
                        }
                    }
                }
            }

            return false;
        }
    }

    private static interface IAccessible {
        int getAccess();

        default boolean isInterface() {
            return ((getAccess() & Opcodes.ACC_INTERFACE) != 0);
        }

        default boolean isAbstract() {
            return ((getAccess() & Opcodes.ACC_ABSTRACT) != 0);
        }

        default boolean isSynthetic() {
            return ((getAccess() & Opcodes.ACC_SYNTHETIC) != 0);
        }

        default boolean isAnnotation() {
            return ((getAccess() & Opcodes.ACC_ANNOTATION) != 0);
        }

        default boolean isEnum() {
            return ((getAccess() & Opcodes.ACC_ENUM) != 0);
        }

        default boolean isPackagePrivate() {
            return (getAccess() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0;
        }

        default boolean isPublic() {
            return (getAccess() & Opcodes.ACC_PUBLIC) != 0;
        }

        default boolean isPrivate() {
            return (getAccess() & Opcodes.ACC_PRIVATE) != 0;
        }

        default boolean isProtected() {
            return (getAccess() & Opcodes.ACC_PROTECTED) != 0;
        }

        default boolean isStatic() {
            return (getAccess() & Opcodes.ACC_STATIC) != 0;
        }

        default boolean isFinal() {
            return (getAccess() & Opcodes.ACC_FINAL) != 0;
        }
    }

    @SuppressWarnings("unused")
    public static class ClassInfo implements IAccessible {
        private final transient String name;
        private final transient boolean local;
        private final transient boolean isRecord;
        private final String superName;
        private final List<String> interfaces;
        private final Integer access;
        private final String signature;
        private final Map<String, FieldInfo> fields;
        private final Map<String, MethodInfo> methods;
        private transient boolean resolved = false;
        private List<RecordInfo> records;

        private ClassInfo(ClassNode node, boolean local) {
            this.local = local;
            this.name = node.name;
            this.superName = "java/lang/Object".equals(node.superName) ? null : node.superName;
            this.isRecord = "java/lang/Record".equals(this.superName);
            this.interfaces = node.interfaces != null && !node.interfaces.isEmpty() ? new ArrayList<>(node.interfaces) : null;
            this.access = node.access == 0 ? null : node.access;
            this.signature = node.signature;

            if (node.fields == null || node.fields.isEmpty()) {
                this.fields = null;
            } else {
                this.fields = new TreeMap<>();
                node.fields.stream().forEach(fld -> this.fields.put(fld.name, new FieldInfo(fld)));
            }

            if (node.methods == null || node.methods.isEmpty()) {
                this.methods = null;
            } else {
                //Gather Lambda methods so we can skip them in bouncers?
                Set<String> lambdas = new HashSet<>();
                for (MethodNode mtd : node.methods) {
                    for (AbstractInsnNode asn : (Iterable<AbstractInsnNode>)() -> mtd.instructions.iterator()) {
                        if (asn instanceof InvokeDynamicInsnNode) {
                            Handle target = getLambdaTarget((InvokeDynamicInsnNode)asn);
                            if (target != null) {
                                lambdas.add(target.getOwner() + '/' + target.getName() + target.getDesc());
                            }
                        }
                    }
                }

                this.methods = new TreeMap<>();
                for (MethodNode mtd : node.methods) {
                    String key = mtd.name + mtd.desc;
                    this.methods.put(key, new MethodInfo(mtd, lambdas.contains(this.name + '/' + key)));
                    if (DEBUG && mtd.name.startsWith("lambda$") && !lambdas.contains(this.name + '/' + key)) {
                        MappingToy.log.log(Level.INFO, "Bad lambda: " + node.name + '/' + mtd.name + ' ' + mtd.desc);
                        MappingToy.log.log(Level.INFO, Utils.toString(mtd.instructions));
                    }
                }
            }
        }

        private Handle getLambdaTarget(InvokeDynamicInsnNode idn) {
            if (LAMBDA_METAFACTORY.equals(idn.bsm)    && idn.bsmArgs != null && idn.bsmArgs.length == 3 && idn.bsmArgs[1] instanceof Handle)
                return ((Handle)idn.bsmArgs[1]);
            if (LAMBDA_ALTMETAFACTORY.equals(idn.bsm) && idn.bsmArgs != null && idn.bsmArgs.length == 5 && idn.bsmArgs[1] instanceof Handle)
                return ((Handle)idn.bsmArgs[1]);
            return null;
        }

        public boolean isLocal() {
            return this.local;
        }

        public String getSuper() {
            return this.superName == null && !"java/lang/Object".equals(this.name) ? "java/lang/Object" : this.superName;
        }

        @Override
        public int getAccess() {
            return access == null ? 0 : access;
        }

        @Override
        public String toString() {
            return Utils.getAccess(getAccess()) + ' ' + this.name;
        }

        public void addRecord(String field, String desc) {
            if (this.records == null)
                this.records = new ArrayList<>();
            this.records.add(new RecordInfo(field, desc));
        }

        public class FieldInfo implements IAccessible {
            private final transient String name;
            private final String desc;
            private final Integer access;
            private final String signature;
            private String force;
            private transient List<MethodInfo> getters;

            private FieldInfo(FieldNode node) {
                this.name = node.name;
                this.desc = node.desc;
                this.access = node.access == 0 ? null : node.access;
                this.signature = node.signature;

                if (ClassInfo.this.isRecord && (node.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == Opcodes.ACC_FINAL) {
                    ClassInfo.this.addRecord(this.name, this.desc);
                }
            }

            public void forceName(String name) {
                this.force = name;
            }

            @Override
            public int getAccess() {
                return access == null ? 0 : access;
            }

            @Override
            public String toString() {
                return Utils.getAccess(getAccess()) + ' ' + this.desc + ' ' + this.name;
            }

            private void addGetter(MethodInfo mtd) {
                if (this.getters == null)
                    this.getters = new ArrayList<>();
                this.getters.add(mtd);
            }
        }

        public class MethodInfo implements IAccessible {
            private final transient boolean isLambda;
            private final transient Method method;
            private final Integer access;
            private final String signature;
            private final Bounce bouncer;
            private String force;
            private Set<Method> overrides;
            private Method parent;
            private transient Set<Method> targetsThis = new HashSet<>();

            private MethodInfo(MethodNode node, boolean lambda) {
                this.method = new Method(ClassInfo.this.name, node.name, node.desc);
                this.access = node.access == 0 ? null : node.access;
                this.signature = node.signature;
                this.isLambda = lambda;

                Bounce bounce = null;
                if (!lambda && (node.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 && (node.access & Opcodes.ACC_STATIC) == 0) {
                    AbstractInsnNode start = node.instructions.getFirst();
                    if (start instanceof LabelNode && start.getNext() instanceof LineNumberNode)
                        start = start.getNext().getNext();

                    if (start instanceof VarInsnNode) {
                        VarInsnNode n = (VarInsnNode)start;
                        if (n.var == 0 && n.getOpcode() == Opcodes.ALOAD) {
                            AbstractInsnNode end = node.instructions.getLast();
                            if (end instanceof LabelNode)
                                end = end.getPrevious();

                            if (end.getOpcode() >= Opcodes.IRETURN && end.getOpcode() <= Opcodes.RETURN)
                                end = end.getPrevious();

                            if (end instanceof MethodInsnNode) {
                                Type[] args = Type.getArgumentTypes(node.desc);
                                int var = 1;
                                int index = 0;
                                start = start.getNext();
                                while (start != end) {
                                    if (start instanceof VarInsnNode) {
                                        if (((VarInsnNode)start).var != var || index + 1 > args.length) {
                                            //Arguments are switched around, so seems like lambda!
                                            end = null;
                                            break;
                                        }
                                        var += args[index++].getSize();
                                    } else if (start.getOpcode() == Opcodes.INSTANCEOF || start.getOpcode() == Opcodes.CHECKCAST) {
                                        //Valid!
                                    } else {
                                        // Anything else is invalid in a bouncer {As far as I know}, so we're most likely a lambda
                                        end = null;
                                        break;
                                    }
                                    start = start.getNext();
                                }

                                MethodInsnNode mtd = (MethodInsnNode)end;
                                if (end != null && (mtd.owner.equals(ClassInfo.this.name) || mtd.owner.equals(ClassInfo.this.superName)) && Type.getArgumentsAndReturnSizes(node.desc) == Type.getArgumentsAndReturnSizes(mtd.desc))
                                    bounce = new Bounce(new Method(mtd.owner, mtd.name, mtd.desc));
                            }
                        }
                    }
                }
                this.bouncer = bounce;

                if (ClassInfo.this.isRecord && (node.access & Opcodes.ACC_STATIC) == 0 && this.method.desc.contains("()") && ClassInfo.this.fields != null) {
                    AbstractInsnNode start = node.instructions.getFirst();
                    if (start instanceof LabelNode && start.getNext() instanceof LineNumberNode)
                        start = start.getNext().getNext();

                    if (start instanceof VarInsnNode) {
                        VarInsnNode n = (VarInsnNode)start;
                        if (n.var == 0 && n.getOpcode() == Opcodes.ALOAD) {
                            if (start.getNext() instanceof FieldInsnNode) {
                                FieldInsnNode fld = (FieldInsnNode)start.getNext();
                                if (fld.owner.equals(ClassInfo.this.name) && fld.getNext() != null) {
                                    AbstractInsnNode ret = fld.getNext();
                                    if (ret.getOpcode() >= Opcodes.IRETURN && ret.getOpcode() <= Opcodes.RETURN) {
                                        FieldInfo fldI = ClassInfo.this.fields.get(fld.name);
                                        if (fldI != null)
                                            fldI.addGetter(this);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public int getAccess() {
                return access == null ? 0 : access;
            }

            public void forceName(String value) {
                this.force = value;
            }

            public void setOverrides(Set<Method> value) {
                this.overrides = value.isEmpty() ? null : value;
            }

            public Set<Method> getOverrides() {
                return this.overrides == null ? Collections.emptySet() : this.overrides;
            }

            public Method getParent() {
                return this.parent;
            }

            public void setParent(Method value) {
                this.parent = value;
            }

            public ClassInfo getOwner() {
                return ClassInfo.this;
            }

            public String getOwnerName() {
                return ClassInfo.this.name;
            }

            public Method getMethod() {
                return this.method;
            }

            public String getName() {
                return this.method.getName();
            }

            public String getDesc() {
                return this.method.getDesc();
            }

            public Set<Method> getTargetsThis() {
                return targetsThis;
            }

            @Override
            public String toString() {
                return Utils.getAccess(getAccess()) + ' ' + this.method.toString();
            }
        }

        public class RecordInfo {
            private final String field;
            private final String desc;
            private List<String> methods;

            private RecordInfo(String field, String desc) {
                this.field = field;
                this.desc = desc;
            }
        }
    }

    private static class Method implements Comparable<Method> {
        private final String owner;
        private final String name;
        private final String desc;

        private Method(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        public String getName() {
            return this.name;
        }

        public String getDesc() {
            return this.desc;
        }

        @Override
        public String toString() {
            return this.owner + '/' + this.name + this.desc;
        }

        @Override
        public int hashCode() {
            return this.toString().hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Method && o.toString().equals(toString());
        }

        private int compare(int a, int b) {
            return a != 0 ? a : b;
        }

        @Override
        public int compareTo(Method o) {
            return compare(owner.compareTo(o.owner), compare(name.compareTo(o.name), desc.compareTo(o.desc)));
        }
    }

    private static class Bounce {
        private Method target;
        private Method owner;

        private Bounce(Method target) {
            this.target = target;
        }

        public void setOwner(Method value) {
            this.owner = value;
        }
        
        public void setTarget(Method value) {
            this.target = value;
        }

        @Override
        public String toString() {
            return this.target + " -> " + this.owner;
        }
    }
}
