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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.zip.ZipEntry;

import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import static org.objectweb.asm.Opcodes.*;

public class Utils {
    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(Date.class, new DateTypeAdapter()).setPrettyPrinting().create();
    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    public static boolean downloadFileEtag(Path file, String url)                               { return downloadFileEtag(file, url,          false        ); }
    public static boolean downloadFileEtag(Path file, String url, boolean force)                { return downloadFileEtag(file, url,          force, "  "  ); }
    public static boolean downloadFileEtag(Path file, String url, boolean force, String prefix) { return downloadFileEtag(file, makeURL(url), force, prefix); }
    public static boolean downloadFileEtag(Path file, URL url)                                  { return downloadFileEtag(file, url,          false        ); }
    public static boolean downloadFileEtag(Path file, URL url, boolean force)                   { return downloadFileEtag(file, url,          force, "  "  ); }
    public static boolean downloadFileEtag(Path file, URL url, boolean force, String prefix) {
        try {
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setUseCaches(false);
            connection.setDefaultUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store,max-age=0,no-cache");
            connection.setRequestProperty("Expires", "0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            Path etagFile = file.getParent().resolve(file.getFileName() + ".etag");
            String foundEtag = Files.isRegularFile(etagFile) ? new String(readStreamFully(etagFile)) : "-";
            if (!force && Files.isRegularFile(file))
                connection.setRequestProperty("If-None-Match", '"' + foundEtag + '"');

            connection.connect();

            String etag = connection.getHeaderField("ETag");
            if (etag == null)
                etag = "-";
            else if ((etag.startsWith("\"")) && (etag.endsWith("\"")))
                etag = etag.substring(1, etag.length() - 1);

            int response = connection.getResponseCode();
            if (response == HttpURLConnection.HTTP_NOT_MODIFIED)
                return true;

            MappingToy.log.info(prefix + file.toString() + " From: " + url.toString());

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(file);) {
                copy(in, out);
            }

            if (etag.indexOf('-') != -1) return true; //No-etag, don't store it

            Files.write(etagFile, etag.getBytes());
            
            if (!connection.getHeaderField("server").equals("AmazonS3")) return true; // Etag is not from AmazonS3 which uses plain md5 hashes, assume valid
            String md5 = HashFunction.MD5.hash(file);

            if (!etag.equalsIgnoreCase(md5)) {
                MappingToy.log.info(prefix  + "  ETag: " + etag);
                MappingToy.log.info(prefix  + "  MD5:  " + md5);
                return false;
            }

            return true;
        } catch (Exception e) {
            MappingToy.log.log(Level.SEVERE, prefix + "  Error: " + file.toString(), e);
            return false;
        }
    }

    public static boolean downloadFile(Path file, URL url, String prefix) {
        try {
            MappingToy.log.info(prefix + file.toString() + " From: " + url.toString());
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setUseCaches(false);
            connection.setDefaultUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store,max-age=0,no-cache");
            connection.setRequestProperty("Expires", "0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();


            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(file);) {
                copy(in, out);
            }

            return true;
        } catch (Exception e) {
            MappingToy.log.log(Level.SEVERE, prefix + "  Error: " + file.toString(), e);
            return false;
        }
    }

    public static URL makeURL(String string) {
        try {
            return new URL(string);
        } catch (MalformedURLException e) {
            MappingToy.log.log(Level.SEVERE, "Malformed URL: "  + string, e);
            return null;
        }
    }

    public static byte[] readStreamFully(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return readStreamFully(in);
        }
    }

    public static byte[] readStreamFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, is.available()));
        byte[] buffer = new byte[8192];
        int read;
        while((read = is.read(buffer)) >= 0) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    public static int copy(InputStream in, OutputStream out) throws IOException {
        int count = 0;
        int c = 0;
        byte[] buf = new byte[1024 * 5];
        while ((c = in.read(buf, 0, buf.length)) != -1) {
            out.write(buf, 0, c);
            count += c;
        }
        return count;
    }

    public static <T> T loadJson(Path target, Class<T> clz) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), clz);
        }
    }

    public static void writeJson(Path target, Object obj) throws IOException {
        Files.write(target, GSON.toJson(obj).getBytes());
    }

    public static Path findMinecraftHome() {
        String userHomeDir = System.getProperty("user.home", ".");
        String osType = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);

        if (osType.contains("win") && System.getenv("APPDATA") != null)
            return new File(System.getenv("APPDATA"), ".minecraft").toPath();
        else if (osType.contains("mac"))
            return new File(new File(new File(userHomeDir, "Library"), "Application Support"), "minecraft").toPath();
        else
            return new File(userHomeDir, ".minecraft").toPath();
    }

    public static ZipEntry getStableEntry(String name) {
        TimeZone _default = TimeZone.getDefault();
        TimeZone.setDefault(GMT);
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(628041600000L);
        TimeZone.setDefault(_default);
        return ret;
    }

    private static int[]    FLAGS = new int[]   {ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_STATIC, ACC_FINAL, ACC_SUPER, ACC_SYNCHRONIZED, ACC_VOLATILE, ACC_BRIDGE, ACC_VARARGS, ACC_TRANSIENT, ACC_NATIVE, ACC_INTERFACE, ACC_ABSTRACT, ACC_STRICT, ACC_SYNTHETIC, ACC_ANNOTATION, ACC_ENUM};
    private static String[] NAMES = new String[]{  "public",   "private",   "protected",   "static",   "final",   "super",   "synchronized",   "volitize",   "bridge",   "varargs",   "transient",   "native",   "interface",   "abstract",   "strict",   "synthetic",   "annotation",   "enum"};
    public static String getAccess(int access) {
        StringBuilder out = new StringBuilder();
        for (int x = 0; x < FLAGS.length; x++) {
            if ((access & FLAGS[x]) == FLAGS[x])
                out.append(NAMES[x]).append(" ");
        }
        if (out.length() > 0)
            return out.toString().trim();
        return "default";
    }

    public static String toString(InsnList lst) {
        Printer printer = new Textifier();
        lst.accept(new TraceMethodVisitor(printer));
        Writer writer = new StringWriter();
        printer.print(new PrintWriter(writer));
        return writer.toString();
    }
}
