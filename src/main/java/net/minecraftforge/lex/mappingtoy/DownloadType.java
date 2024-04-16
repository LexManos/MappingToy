/*
 * Copyright (c) LexManos
 * SPDX-License-Identifier: LGPL-2.1-only
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
