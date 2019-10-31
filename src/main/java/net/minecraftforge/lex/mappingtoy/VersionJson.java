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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class VersionJson {
    public static final String MOJANG_LIBRARY_URL = "https://libraries.minecraft.net/";
    String id;
    Map<String, Download> downloads;
    AssetIndex assetIndex;
    String assets;
    LibraryInfo[] libraries;
    //arguments
    //logging
    String mainClass;
    int minimumLauncherVersion;
    Date releaseTime;
    Date time;
    String type;
    String inheritsFrom;
    String key;

    public Map<String, Download> getDownloads() {
        return downloads == null ? Collections.emptyMap() : downloads;
    }

    public static class LibraryInfo {
        String name;
        String url;
        Downloads downloads;
    }

    public static class Downloads {
        Map<String, Artifact> classifiers;
        Artifact artifact;
    }

    public static class Download {
        int size;
        String sha1;
        String url;

        protected void copy(Download other) {
            this.size = other.size;
            this.sha1 = other.sha1;
            this.url = other.url;
        }

        @Override
        public String toString() {
            return "DownloadInfo{url=" + url + ";size=" + size + ";sha1=" + sha1 + "}";
        }
    }

    public static class Artifact extends Download {
        String path;

        @Override
        public String toString() {
            return "DownloadInfo{url=" + url + ";size=" + size + ";sha1=" + sha1 + ";path=" + path +"}";
        }
    }

    public static class AssetIndex extends Download {
        String id;
        long totalSize;

        @Override
        public String toString() {
            return "DownloadInfo{url=" + url + ";size=" + size + ";sha1=" + sha1 + ";id=" + id +";totalSize=" + totalSize + "}";
        }
    }

    public List<DownloadInfo> getLibraries() {
        List<DownloadInfo> ret = new ArrayList<>();

        for (LibraryInfo lib : libraries) {
            if (lib.downloads == null) {
                String[] pts = lib.name.split(":");
                String path = pts[0].replace('.', '/') + '/' + pts[1] + '/' + pts[2] + '/' + pts[1] + '-' + pts[2] + (pts.length > 3 ? '-' + pts[3] : "") + ".jar";
                ret.add(new DownloadInfo(path, MOJANG_LIBRARY_URL + path));
                continue;
            }

            if (lib.downloads.artifact != null)
                ret.add(new DownloadInfo(lib.downloads.artifact.path, lib.downloads.artifact));

            if (lib.downloads.classifiers != null) {
                for (Artifact art : lib.downloads.classifiers.values())
                    ret.add(new DownloadInfo(art.path, art));
            }
        }

        return ret;
    }

    public static class DownloadInfo extends Download {
        String path;
        DownloadInfo(String path, String url) {
            this.path = path;
            this.url = url;
        }

        DownloadInfo(String path, Download other) {
            this.path = path;
            this.copy(other);
        }

        @Override
        public String toString() {
            return "DownloadInfo{url=" + url + ";size=" + size + ";sha1=" + sha1 + ";path=" + path +"}";
        }
    }
}
