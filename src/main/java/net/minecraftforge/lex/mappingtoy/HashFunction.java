/*
 * Copyright (c) LexManos
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.lex.mappingtoy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

//These are all standard hashing functions the JRE is REQUIRED to have, so add a nice factory that doesnt require catching annoying exceptions;
public enum HashFunction {
    MD5("md5", 32),
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64);

    private String algo;
    private String pad;

    private HashFunction(String algo, int length) {
        this.algo = algo;
        this.pad = String.format("%0" + length + "d", 0);
    }

    public String getExtension() {
         return this.name().toLowerCase(Locale.ENGLISH);
    }

    public MessageDigest get() {
        try {
            return MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); //Never happens
        }
    }

    public String hash(File file) throws IOException {
        try (FileInputStream fin = new FileInputStream(file)) {
            return hash(fin);
        }
    }

    public String hash(Path file) throws IOException {
        try (InputStream fin = Files.newInputStream(file)) {
            return hash(fin);
        }
    }

    public String hashSafe(Path file) {
        try {
            return hash(file);
        } catch (IOException e) {
            return null;
        }
    }

    public String hash(String data) {
        return hash(data.getBytes(StandardCharsets.UTF_8));
    }

    public String hash(InputStream stream) throws IOException {
        MessageDigest digest = get();

        int nRead;
        byte[] data = new byte[1024 * 5];
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
            digest.update(data, 0, nRead);
        }

        String hash = new BigInteger(1, digest.digest()).toString(16);
        return (pad + hash).substring(hash.length());
    }

    public String hash(byte[] data) {
        String hash = new BigInteger(1, get().digest(data)).toString(16);
        return (pad + hash).substring(hash.length());
    }
}
