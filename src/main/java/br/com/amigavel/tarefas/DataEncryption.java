package br.com.amigavel.tarefas;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class DataEncryption {
    private static final int KEY_SIZE_BITS = 256;
    private static final int SALT_SIZE_BYTES = 16;
    private static final int NONCE_SIZE_BYTES = 12;
    private static final int GCM_TAG_SIZE_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DataEncryption() {
    }

    static byte[] newSalt() {
        byte[] salt = new byte[SALT_SIZE_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static byte[] deriveKey(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec).getEncoded();
        } finally {
            keySpec.clearPassword();
        }
    }

    static String encryptTask(String plaintext, byte[] key, long userId) throws GeneralSecurityException {
        byte[] context = ("tarefas-amigaveis:tasks:v1:user:" + userId).getBytes(StandardCharsets.UTF_8);
        return PREFIX + Base64.getEncoder().encodeToString(encrypt(plaintext.getBytes(StandardCharsets.UTF_8), key, context));
    }

    static String decryptTask(String ciphertext, byte[] key, long userId) throws GeneralSecurityException {
        if (!ciphertext.startsWith(PREFIX)) {
            throw new GeneralSecurityException("Formato criptografado de tarefa desconhecido.");
        }
        byte[] context = ("tarefas-amigaveis:tasks:v1:user:" + userId).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
        return new String(decrypt(encrypted, key, context), StandardCharsets.UTF_8);
    }

    static byte[] encryptTransfer(byte[] plaintext, byte[] key) throws GeneralSecurityException {
        return encrypt(plaintext, key, "tarefas-amigaveis:transfer:v2".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] decryptTransfer(byte[] ciphertext, byte[] key) throws GeneralSecurityException {
        return decrypt(ciphertext, key, "tarefas-amigaveis:transfer:v2".getBytes(StandardCharsets.UTF_8));
    }

    static boolean isEncryptedTask(String value) {
        return value.startsWith(PREFIX);
    }

    private static byte[] encrypt(byte[] plaintext, byte[] key, byte[] associatedData)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_SIZE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce));
        cipher.updateAAD(associatedData);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
        return result;
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] associatedData)
            throws GeneralSecurityException {
        if (ciphertext.length <= NONCE_SIZE_BYTES) {
            throw new GeneralSecurityException("Conteúdo criptografado inválido.");
        }
        byte[] nonce = Arrays.copyOf(ciphertext, NONCE_SIZE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(ciphertext, NONCE_SIZE_BYTES, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(encrypted);
    }
}
