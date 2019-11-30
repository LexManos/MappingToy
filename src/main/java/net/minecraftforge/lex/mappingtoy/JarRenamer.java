/*
 * MappingToy
 * Copyright (c) 2019
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.lex.mappingtoy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class JarRenamer {
    public static void makeMappedJar(Path root, String mapping, String prefix, boolean force) {
        Path source = root.resolve(prefix + ".jar");
        Path target = root.resolve(prefix + "_n.jar");
        Path srg    = root.resolve(mapping);

        if (!force && Files.isRegularFile(target))
            return;

        if (!Files.isRegularFile(source) || !Files.isRegularFile(srg))
            return;

        try {
            MappingToy.log.info("  " + target.getFileName());

            try (ZipInputStream  jin = new ZipInputStream(Files.newInputStream(source));
                 ZipOutputStream jout = new ZipOutputStream(Files.newOutputStream(target))) {

                IMappingFile map = IMappingFile.load(srg.toFile());
                SimpleRemapper remapper = new SimpleRemapper(map);

                Set<String> dirs = new HashSet<>();

                ZipEntry entry = null;
                while ((entry = jin.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        if (!dirs.contains(name)) {
                            jout.putNextEntry(Utils.getStableEntry(name));
                            dirs.add(name);
                        }
                    } else if (name.endsWith("MANIFEST.MF")) {
                        BufferedReader buf = new BufferedReader(new InputStreamReader(jin));
                        ByteArrayOutputStream bao = new ByteArrayOutputStream();
                        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(bao));

                        String line = null;
                        boolean lastSpace = false;
                        while((line = buf.readLine()) != null) {
                            if (line.isEmpty()) {
                                if (!lastSpace) {
                                    out.write("\r\n");
                                    lastSpace = true;
                                }
                            } else if (line.startsWith("Name:")) {
                                buf.readLine(); //Kill the SHA1-Digest line
                                buf.readLine(); //kill the empty line
                            } else {
                                out.write(line);
                                out.write("\r\n");
                                lastSpace = false;
                            }
                        }

                        out.flush();
                        startEntry(jout, name, dirs);
                        jout.write(bao.toByteArray());
                    } else if (name.endsWith(".class")) {
                        byte[] data = Utils.readStreamFully(jin);
                        ClassReader reader = new ClassReader(data);
                        ClassWriter writer = new ClassWriter(0);
                        ClassVisitor visitor = new ClassRemapper(writer, remapper);
                        reader.accept(visitor, 0);
                        startEntry(jout, map.remapClass(name.substring(0, name.length() - 6)) + ".class", dirs);
                        jout.write(writer.toByteArray());
                    } else {
                        startEntry(jout, name, dirs);
                        Utils.copy(jin, jout);
                    }
                }
            }
        } catch (IOException e) {
            MappingToy.log.log(Level.WARNING, "    Failed: " + e.getMessage(), e);
        }
    }

    private static void startEntry(ZipOutputStream jout, String filePath, Set<String> dirs) throws IOException {
        int i = filePath.lastIndexOf('/', filePath.length() - 2);
        if(i != -1) {
            String dir = filePath.substring(0, i + 1);
            if (!dirs.contains(dir)) {
                startEntry(jout, dir, dirs);
                dirs.add(dir);
            }
        }
        jout.putNextEntry(Utils.getStableEntry(filePath));
    }

    private static class SimpleRemapper extends Remapper {
        private IMappingFile map;
        private SimpleRemapper(IMappingFile map) {
            this.map = map;
        }

        @Override
        public String mapMethodName(final String owner, final String name, final String descriptor) {
            IClass cls = map.getClass(owner);
            return cls == null ? name : cls.remapMethod(name, descriptor);
        }

        @Override
        public String mapFieldName(final String owner, final String name, final String descriptor) {
            IClass cls = map.getClass(owner);
            return cls == null ? name : cls.remapField(name);
        }

        @Override
        public String mapPackageName(final String name) {
          return map.remapPackage(name);
        }

        @Override
        public String map(final String internalName) {
            return map.remapClass(internalName);
        }
    }
}
