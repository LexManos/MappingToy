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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.minecraftforge.lex.mappingtoy.VersionJson.Download;
import net.minecraftforge.lex.mappingtoy.VersionJson.DownloadInfo;
import net.minecraftforge.mergetool.AnnotationVersion;
import net.minecraftforge.mergetool.Merger;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;
import net.minecraftforge.srgutils.MinecraftVersion;

import static net.minecraftforge.lex.mappingtoy.JarMetadata.makeMetadata;
import static net.minecraftforge.lex.mappingtoy.JarRenamer.makeMappedJar;

public class MappingToy {
    public static final Logger log = Logger.getLogger("MappingToy");

    public static void main(String[] args) throws SecurityException, IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<String>  versionO   = parser.accepts("version").withRequiredArg();
        OptionSpec<Path>    outputO    = parser.accepts("output").withRequiredArg().withValuesConvertedBy(new PathConverter()).defaultsTo(Paths.get("output"));
        OptionSpec<Path>    minecraftO = parser.accepts("mc").withRequiredArg().withValuesConvertedBy(new PathConverter()).defaultsTo(Utils.findMinecraftHome());
        OptionSpec<Void>    allO       = parser.accepts("all");
        OptionSpec<Void>    libsO      = parser.accepts("libs");
        OptionSpec<Path>    logO       = parser.accepts("log").withRequiredArg().withValuesConvertedBy(new PathConverter());
        OptionSpec<Void>    forceO     = parser.accepts("force", "Force rebuilding of everything even if files already exist, Mainly for debugging");

        OptionSet options = parser.parse(args);
        Set<MinecraftVersion> versions = options.valuesOf(versionO).stream().map(MinecraftVersion::from).collect(Collectors.toCollection(TreeSet::new));
        Path         output       = options.valueOf(outputO);
        Path         minecraft    = options.valueOf(minecraftO);
        boolean      all          = options.has(allO);
        boolean      libs         = all || options.has(libsO);
        boolean      force        = options.has(forceO);

        if (!Files.isDirectory(minecraft)) {
            System.out.println("Specificed --mc directory does not exist: " + minecraft);
            return;
        }

        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);

        if (options.has(logO)) {
            FileHandler filehandler = new FileHandler(options.valueOf(logO).toFile().getAbsolutePath());
            //filehandler.setFormatter(new LogFormatter());
            filehandler.setFormatter(new Formatter() {
                public String format(LogRecord record) {
                    StringBuffer sb = new StringBuffer();
                    String message = this.formatMessage(record);
                    sb.append(record.getLevel().getName());
                    sb.append(": ");
                    sb.append(message);
                    sb.append("\n");
                    if (record.getThrown() != null) {
                        try {
                            StringWriter sw = new StringWriter();
                            try (PrintWriter pw = new PrintWriter(sw)) {
                                record.getThrown().printStackTrace(pw);
                            }
                            sb.append(sw.toString());
                        } catch (Exception ex) {}
                    }
                    return sb.toString();
                }
            });
            log.addHandler(filehandler);
        }
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                System.out.println(String.format(record.getMessage(), record.getParameters()));
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        });

        log.info("The mappings, and data downloaded by this project is (c) Microsoft Corporation. All rights reserved.");
        log.info("That information is provided \"as-is\" and you bear the risk of using it.");
        log.info("This information does not provide you with any legal rights to any intellectual property in any Microsoft product.");
        log.info("You may copy and use this information for your internal, reference purposes.");
        log.info("Microsoft makes no warranties, express or implied, with respect to the information provided.");
        log.info("==================================================================================================================");
        log.info("This means you can only use this program for reference purposes. Please don't be an ass. -Lex");
        log.info("");
        log.info("Output:    " + output);
        log.info("Minecraft: " + minecraft);
        log.info("All:       " + all);
        log.info("Libs:      " + libs);
        log.info("Versions:  " + (versions.isEmpty() ? "All" : versions));
        log.info("Force:     " + force);
        log.info("");

        Files.createDirectories(output);

        ManifestJson manifest_json = downloadLauncherManifest(output);
        if (manifest_json == null)
            return;

        if (versions.isEmpty())
            versions.addAll(manifest_json.getEntries().keySet());

        for (MinecraftVersion ver : versions) {
            log.log(Level.INFO, "Processing " + ver.toString() + ":");

            ManifestJson.Entry mainEntry = manifest_json.getVersion(ver);
            if (mainEntry == null || mainEntry.url == null) {
                log.log(Level.INFO, "  No entry in Launcher Manifest");
                continue;
            }

            Path root = output.resolve(ver.toString());
            Files.createDirectories(root);

            VersionJson manifest = downloadVersionJson(root, mainEntry.url);
            if (manifest == null)
                continue;

            Set<DownloadType> downloaded = downloadMinecraftFiles(root, manifest.getDownloads());

            IMappingFile clientMap = downloaded.contains(DownloadType.CLIENT_MAPPINGS) ? IMappingFile.load(root.resolve(DownloadType.CLIENT_MAPPINGS.getFilename()).toFile()) : null;
            IMappingFile serverMap = downloaded.contains(DownloadType.SERVER_MAPPINGS) ? IMappingFile.load(root.resolve(DownloadType.SERVER_MAPPINGS.getFilename()).toFile()) : null;
            boolean mergeable = canMerge(clientMap, serverMap);

            if (mergeable) {
                writeMappings(root, clientMap, "joined", all, force);
                makeJoinedJar(root, ver, clientMap, true, force);
                makeMappedJar(root, "joined_o_to_n.tsrg", "joined_a", force);
                if (all) {
                    makeJoinedJar(root, ver, clientMap, false, force);
                    makeMappedJar(root, "joined_o_to_n.tsrg", "joined", force);
                }
            }

            if (!mergeable || all){
                if (clientMap != null) {
                    writeMappings(root, clientMap, "client", all, force);
                    makeMappedJar(root, "client_o_to_n.tsrg", "client", force);
                }
                if (serverMap != null) {
                    writeMappings(root, serverMap, "server", all, force);
                    makeMappedJar(root, "server_o_to_n.tsrg", "server", force);
                }
            }

            Collection<Path> libraries = Collections.emptyList();
            if (libs) {
                libraries = downloadLauncherFiles(root, minecraft, ver, manifest);
            }

            if (mergeable) {
                makeMetadata(root, libraries, clientMap, "joined_a", true, force);
                if (all)
                    makeMetadata(root, libraries, clientMap, "joined_a_n", false, force);
            }
        }

        log.info("Finished");
    }

    private static ManifestJson downloadLauncherManifest(Path output) {
        Path manifest = output.resolve("launcher_manifest.json");
        if (!Utils.downloadFileEtag(manifest, ManifestJson.MOJANG_URL, false, "Downloading: ")) {
            log.info("    Failed, Exiting");
            return null;
        }

        log.info("Manifest: " + manifest.toString());

        try {
            return Utils.loadJson(manifest, ManifestJson.class);
        } catch (Exception e) {
            log.log(Level.WARNING, "    Failed to read json file: " + e.getMessage(), e);
            return null;
        }
    }

    private static VersionJson downloadVersionJson(Path output, URL url) {
        Path target = output.resolve("version.json");
        if (!Utils.downloadFileEtag(target, url, false, "  ")) {
            log.info("    Failed, Exiting");
            return null;
        }

        try {
            return Utils.loadJson(target, VersionJson.class);
        } catch (Exception e) {
            log.log(Level.WARNING, "  Failed to read json file: " + e.getMessage(), e);
            return null;
        }
    }

    private static Set<DownloadType> downloadMinecraftFiles(Path output, Map<String, Download> downloads) {
        Set<DownloadType> ret = new HashSet<>();
        for (DownloadType type : DownloadType.getValues()) {
            Path target = output.resolve(type.getFilename());

            Download info = downloads.get(type.getKey());
            if (info == null) {
                log.info("  Manifest Missing: " + type.getKey());
                continue;
            }

            if (Files.isRegularFile(target) && info.sha1 != null) {
                String sha = HashFunction.SHA1.hashSafe(target);
                if (sha != null && info.sha1.equalsIgnoreCase(sha)) {
                    ret.add(type);
                    continue;
                }
            }

            if (!Utils.downloadFileEtag(target, info.url, false, "  "))
                log.info("    Fialed to download: " + target.getFileName());
            else
                ret.add(type);
        }
        return ret;
    }

    private static boolean canMerge(IMappingFile client, IMappingFile server) {
        //Test if the client is a strict super-set of server. If so the client mappings can be used for the joined jar
        final Function<IField,  String> fldToString = fld -> fld.getOriginal() + " " + fld.getDescriptor() + " -> " + fld.getMapped() + " " + fld.getMappedDescriptor();
        final Function<IMethod, String> mtdToString = mtd -> mtd.getOriginal() + " " + mtd.getDescriptor() + " -> " + mtd.getMapped() + " " + mtd.getMappedDescriptor();

        for (IClass clsS : server.getClasses()) {
            IClass clsC = client.getClass(clsS.getOriginal());
            if (clsC == null || !clsS.getMapped().equals(clsC.getMapped()))
                return false;

            Set<String> fldsS = clsS.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> fldsC = clsC.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsS = clsS.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsC = clsC.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));

            fldsS.removeAll(fldsC);
            mtdsS.removeAll(mtdsC);

            if (!fldsS.isEmpty() || !mtdsS.isEmpty())
                return false;
        }

        return true;
    }

    private static void writeMappings(Path output, IMappingFile mapping, String prefix, boolean all, boolean force) {
        for (IMappingFile.Format format : all ? IMappingFile.Format.values() : new IMappingFile.Format[]{ IMappingFile.Format.TSRG }) {
            String ext = format.name().toLowerCase(Locale.ENGLISH);

            Path target = output.resolve(prefix + "_n_to_o." + ext);
            if (force || !Files.isRegularFile(target)) {
                log.info("  " + target.getFileName());
                try {
                    mapping.write(target, format, false);
                } catch (IOException e) {
                    log.info("  " + target.getFileName() + " Failed: " + e.getMessage());
                }
            }

            target = output.resolve(prefix + "_o_to_n." + ext);
            if (force || !Files.isRegularFile(target)) {
                log.info("  " + target.getFileName());
                try {
                    mapping.write(target, format, true);
                } catch (IOException e) {
                    log.info("  " + target.getFileName() + " Failed: " + e.getMessage());
                }
            }
        }
    }

    private static void makeJoinedJar(Path output, MinecraftVersion version, IMappingFile mappings, boolean annotate, boolean force) {
        Path target = output.resolve(annotate ? "joined_a.jar" : "joined.jar");
        if (!force && Files.isRegularFile(target))
            return;

        log.info("  " + target.getFileName());

        AnnotationVersion ann = annotate ? AnnotationVersion.fromVersion(version.toString()) : null;
        Path client = output.resolve("client.jar");
        Path server = output.resolve("server.jar");

        if (!Files.isRegularFile(client) || !Files.isRegularFile(server))
            return;

        try {
            Merger merger = new Merger(client.toFile(), server.toFile(), target.toFile());
            mappings.getClasses().forEach(e -> merger.whitelist(e.getMapped()));
            if (ann != null)
                merger.annotate(ann, true);
            merger.keepData();
            merger.skipMeta();
            merger.process();
        } catch (IOException e) {
            log.info("    Could not make joined jar for: " + version + " " + e.getMessage());
            e.printStackTrace();
        }
        System.gc();
    }

    private static List<Path> downloadLauncherFiles(Path output, Path minecraft, MinecraftVersion version, VersionJson json) {
        String ver = version.toString();
        Path[][] copy = {
            {output.resolve(DownloadType.CLIENT.getFilename()), minecraft.resolve("versions/" + ver + "/" + ver + ".jar" )},
            {output.resolve("version.json"), minecraft.resolve("versions/" + ver + "/" + ver + ".json")}
        };

        for (Path[] files : copy) {
            try {
                String shaO = HashFunction.SHA1.hash(files[0]);
                String shaM = Files.isRegularFile(files[1]) ? HashFunction.SHA1.hash(files[1]) : null;
                if (!shaO.equals(shaM)) {
                    log.info("  Copy " + files[0].getFileName() + " -> " + files[1].getFileName());
                    Files.createDirectories(files[1].getParent());
                    if (Files.isRegularFile(files[1]))
                        Files.delete(files[1]);
                    Files.copy(files[0], files[1]);
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "    Failed: " + e.getMessage(), e);
            }
        }

        Path libs = minecraft.resolve("libraries");
        List<Path> paths = new ArrayList<>();

        List<DownloadInfo> downloads = json.getLibraries();
        for (DownloadInfo dl : downloads) {
            Path target = libs.resolve(dl.path);
            paths.add(target);
            if (Files.isRegularFile(target))
                continue;

            try {
                Files.createDirectories(target.getParent());
                Utils.downloadFile(target, Utils.makeURL(dl.url), "  ");
            } catch (IOException e) {
                log.log(Level.WARNING, "  Could not download library: " + dl.path, e);
            }
        }

        return paths;
    }
}
