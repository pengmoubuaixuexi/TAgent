package cn.bugstack.ai.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM with owner/ref/version associated data; the master key belongs in deployment secrets. */
@Component
public final class ModelSecretCipher {
    private final byte[] key;
    public ModelSecretCipher(@Value("${agent.model-secrets.master-key:${MODEL_SECRET_MASTER_KEY:}}") String configured) {
        if (configured == null || configured.isBlank()) { key = null; return; }
        try { key = Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("模型密钥主密钥必须为 Base64 编码的32字节", e); }
        if (key.length != 32) throw new IllegalStateException("模型密钥主密钥必须为32字节");
    }
    public boolean configured() { return key != null; }
    public String encrypt(String owner, String ref, int version, String plaintext) {
        requireConfigured();
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, owner, ref, version);
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("模型密钥加密失败"); }
    }
    public String decrypt(String owner, String ref, int version, String encrypted) {
        requireConfigured();
        try {
            String[] parts = encrypted.split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), owner, ref, version)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("模型密钥不可解密，请重新配置连接"); }
    }
    private Cipher cipher(int mode, byte[] nonce, String owner, String ref, int version) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,nonce));
        cipher.updateAAD((owner + ":" + ref + ":" + version).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private void requireConfigured() { if (key == null) throw new IllegalStateException("尚未配置 MODEL_SECRET_MASTER_KEY，暂不能保存或使用个人模型密钥"); }
}
