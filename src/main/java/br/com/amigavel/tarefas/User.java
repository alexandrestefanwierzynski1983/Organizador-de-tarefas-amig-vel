package br.com.amigavel.tarefas;

import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class User implements AutoCloseable {
    private final long id;
    private final String username;
    private final byte[] encryptionKey;

    User(long id, String username, byte[] encryptionKey) {
        this.id = id;
        this.username = username;
        this.encryptionKey = encryptionKey;
    }

    public long id() {
        return id;
    }

    public String username() {
        return username;
    }

    String encryptTask(String title) throws GeneralSecurityException {
        return DataEncryption.encryptTask(title, encryptionKey, id);
    }

    String decryptTask(String ciphertext) throws GeneralSecurityException {
        return DataEncryption.decryptTask(ciphertext, encryptionKey, id);
    }

    @Override
    public void close() {
        Arrays.fill(encryptionKey, (byte) 0);
    }
}
