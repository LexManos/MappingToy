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

import java.util.Locale;

public enum DownloadType {
    CLIENT("client.jar"),
    SERVER("server.jar"),
    CLIENT_MAPPINGS("client.txt"),
    SERVER_MAPPINGS("server.txt");
    private static final DownloadType[] values = values();

    private final String filename;
    private final String key;

    private DownloadType(String filename) {
        this.filename = filename;
        this.key = this.name().toLowerCase(Locale.ENGLISH);
    }

    public String getFilename() {
        return this.filename;
    }

    public String getKey() {
        return this.key;
    }

    public static DownloadType[] getValues() {
        return DownloadType.values;
    }
}
